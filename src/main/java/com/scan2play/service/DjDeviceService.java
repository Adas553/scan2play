package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Program na komputerze" (docs/ideas.md item 19, V78): the DJ's computer paired with a party. The panel makes a 6-digit code
 * ("Połącz komputer", valid {@value #CODE_MINUTES} minutes, kept in memory, used once); the program swaps it for a token of 256 bits
 * of its own — the DJ's login never leaves the browser. Only the token's SHA-256 is stored, so a copy of the table opens no party.
 * "Odłącz" deletes the row. A party has at most {@value #MAX_DEVICES}: pairing one more forgets the one seen longest ago.
 * <p>
 * Limits, all in memory: {@value #PAIR_TRIES_PER_CLIENT} tries of a code per address and {@value #PAIR_FAILURES} wrong codes in all per
 * {@value #CODE_MINUTES} minutes (a code is one of a million and lives {@value #CODE_MINUTES} minutes, a party has one at a time:
 * guessing one needs far more, and a code guessed opens only the marking of one party's queue — which its DJ sees in the list of
 * computers and takes away). The limit in all is there to stop a guessing run, not a person: reaching it takes a hundred addresses,
 * and it says so in the log once. A device may call {@value #CALLS_PER_MINUTE} times a minute.
 */
@Slf4j
@Service
public class DjDeviceService {

    public static final int CODE_MINUTES = 10;
    static final int MAX_DEVICES = 3;
    static final int PAIR_TRIES_PER_CLIENT = 10;
    static final int PAIR_FAILURES = 1000;
    static final int CALLS_PER_MINUTE = 30;
    /** The longest device name kept (the column). */
    static final int NAME_MAX = 60;
    /** The longest program name kept (V81's column). */
    static final int PROGRAM_MAX = 30;
    /**
     * A gap this long without a song ends a run of songs (V82): the next song starts a new one. Longer than any pause the DJ makes
     * mid-set on purpose (speeches, the cake), shorter than the hours between a test before the party and the set.
     */
    public static final Duration RUN_GAP = Duration.ofMinutes(30);
    /**
     * A run that was a set (see {@link #setSql}) goes on after a longer gap too, up to this long — a dinner without music, a band's
     * set: the music is back, a program that fails then is still worth a warning. Shorter than the night between two parties: the
     * next day's soundcheck starts its own run.
     */
    static final Duration SET_BREAK_MAX = Duration.ofHours(4);
    /** A run this long, with a guest's request in it, is a set; shorter, a test of the program (a song or two). */
    public static final Duration RUN_MIN = Duration.ofMinutes(10);
    /**
     * A run this long is a set even without a request in it (the set played before the first guest asked); a soundcheck before the
     * party is shorter, and has no guests.
     */
    public static final Duration RUN_LONG = Duration.ofMinutes(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALL_FAILURES = "*";

    private final JdbcTemplate jdbc;
    /** A code → the party it pairs with. */
    private final Cache<String, String> codes;
    private final Cache<String, AtomicInteger> pairTries;
    private final Cache<Long, AtomicInteger> calls;

    public DjDeviceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.codes = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(CODE_MINUTES)).maximumSize(10_000).build();
        this.pairTries = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(CODE_MINUTES)).maximumSize(100_000).build();
        this.calls = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100_000).build();
    }

    /**
     * A device of a party: {@code lastPlayAt} the start of the newest song its program sent (V81; null: none yet), {@code playRunFrom}
     * the start of the run of songs it belongs to (V82; null: none since V82), {@code program} what it read it from (null: a program
     * too old to say).
     */
    public record Device(long id, String name, Instant createdAt, Instant lastSeenAt, Instant lastPlayAt, Instant playRunFrom,
                         String program) {

        /** Its last call, or its pairing when it never called since. */
        Instant heardAt() {
            return lastSeenAt == null ? createdAt : lastSeenAt;
        }
    }

    /** What a token opens: the device and its party. */
    public record Paired(long deviceId, String partyCode) {
    }

    /** Why a code was not taken. */
    public enum PairRefusal { WRONG_CODE, TOO_MANY_TRIES }

    /** A pairing: the token for the program, or why not. */
    public record Pairing(String token, PairRefusal refusal) {
    }

    /**
     * A new code for the party (the panel's "Połącz komputer"): 6 digits, valid {@value #CODE_MINUTES} minutes, used once. The party's
     * earlier code dies with it: a party has one code at a time, so pressing the button again (or a loop of requests) neither leaves
     * two codes to be guessed nor fills the memory the other parties' codes live in.
     */
    public String newCode(String partyCode) {
        codes.asMap().values().removeIf(partyCode::equals);
        String code;
        do {
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (codes.asMap().putIfAbsent(code, partyCode) != null);
        return code;
    }

    /**
     * The program's code swapped for its token; the party's device seen longest ago forgotten when it has {@value #MAX_DEVICES}
     * already. A code is taken once: a second program with it hears "wrong code".
     */
    @Transactional
    public Pairing pair(String code, String name, String client, Instant now) {
        if (count(pairTries, client) > PAIR_TRIES_PER_CLIENT || count(pairTries, ALL_FAILURES, false) >= PAIR_FAILURES) {
            return new Pairing(null, PairRefusal.TOO_MANY_TRIES);
        }
        String plain = code == null ? "" : code.replaceAll("\\D", "");
        String partyCode = plain.length() == 6 ? codes.asMap().remove(plain) : null;
        if (partyCode == null) {
            if (count(pairTries, ALL_FAILURES) == PAIR_FAILURES) {
                log.warn("{} wrong pairing codes in {} minutes: pairing is refused to everyone until the window ends", PAIR_FAILURES,
                        CODE_MINUTES);
            }
            return new Pairing(null, PairRefusal.WRONG_CODE);
        }
        // the party's row locked: two computers paired at once count each other; gone (deleted, its code changed since): no pairing
        if (jdbc.queryForList("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode).isEmpty()) {
            return new Pairing(null, PairRefusal.WRONG_CODE);
        }
        List<Long> devices = jdbc.queryForList("SELECT id FROM dj_device WHERE party_code = ?"
                + " ORDER BY COALESCE(last_seen_at, created_at) DESC, id DESC", Long.class, partyCode);
        if (devices.size() >= MAX_DEVICES) {
            devices.subList(MAX_DEVICES - 1, devices.size()).forEach(id -> jdbc.update("DELETE FROM dj_device WHERE id = ?", id));
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("INSERT INTO dj_device (party_code, token_hash, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                partyCode, hash(token), cleanName(name), Timestamp.from(now), Timestamp.from(now));
        log.info("A computer paired with party {}", partyCode);
        return new Pairing(token, null);
    }

    /** The device a token opens, its last call noted (one write a minute at most); empty for a token unknown or revoked. */
    public Optional<Paired> device(String token, Instant now) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        List<Paired> found = jdbc.query("SELECT id, party_code FROM dj_device WHERE token_hash = ?",
                (row, n) -> new Paired(row.getLong(1), row.getString(2)), hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Paired paired = found.getFirst();
        jdbc.update("UPDATE dj_device SET last_seen_at = ? WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at < ?)",
                Timestamp.from(now), paired.deviceId(), Timestamp.from(now.minus(Duration.ofMinutes(1))));
        return Optional.of(paired);
    }

    /** One call of a device counted: false past {@value #CALLS_PER_MINUTE} a minute. */
    public boolean mayCall(long deviceId) {
        return count(calls, deviceId) <= CALLS_PER_MINUTE;
    }

    /** The party's devices, the one seen last first. */
    public List<Device> devices(String partyCode) {
        return jdbc.query("SELECT id, name, created_at, last_seen_at, last_play_at, play_run_from, last_play_program FROM dj_device"
                        + " WHERE party_code = ?"
                        + " ORDER BY COALESCE(last_seen_at, created_at) DESC, id DESC LIMIT " + MAX_DEVICES,
                (row, n) -> new Device(row.getLong(1), row.getString(2), row.getTimestamp(3).toInstant(), instant(row.getTimestamp(4)),
                        instant(row.getTimestamp(5)), instant(row.getTimestamp(6)), row.getString(7)), partyCode);
    }

    /**
     * The songs a device sent, from {@code oldestAt} to {@code newestAt}: the newest one's start kept (never earlier than what is kept
     * — a song sent again after a restart moves nothing back), the program it came from, and the run of songs it belongs to — a new
     * run from {@code oldestAt} when the last song kept is more than {@link #RUN_GAP} before it, unless that run was a set and the gap
     * shorter than {@link #SET_BREAK_MAX} (a break in the party: the set goes on). Only times and the program's name: the songs
     * themselves are not kept.
     */
    public void notePlay(long deviceId, Instant oldestAt, Instant newestAt, String program) {
        String name = program == null || program.isBlank() ? null : cleanName(program);
        // the SET's expressions read the row as it was: last_play_at and play_run_from below are the ones kept before this call
        jdbc.update("UPDATE dj_device SET play_run_from = CASE WHEN last_play_at IS NULL OR play_run_from IS NULL THEN ?"
                        + " WHEN last_play_at >= ?::timestamptz - " + interval(RUN_GAP) + " THEN play_run_from"
                        + " WHEN last_play_at >= ?::timestamptz - " + interval(SET_BREAK_MAX) + " AND " + setSql("dj_device")
                        + " THEN play_run_from ELSE ? END,"
                        + " last_play_at = ?, last_play_program = COALESCE(?, last_play_program)"
                        + " WHERE id = ? AND (last_play_at IS NULL OR last_play_at < ?)",
                Timestamp.from(oldestAt), Timestamp.from(oldestAt), Timestamp.from(oldestAt), Timestamp.from(oldestAt),
                Timestamp.from(newestAt), name == null ? null : name.substring(0, Math.min(PROGRAM_MAX, name.length())), deviceId,
                Timestamp.from(newestAt));
    }

    /**
     * SQL: whether the run of songs of the {@code dj_device} row {@code row} (its {@code play_run_from} to {@code last_play_at}) was a
     * set, not a test of the program or a soundcheck: {@link #RUN_LONG} of songs, or {@link #RUN_MIN} with a guest's request in it (the
     * DJ's own "+ Dodaj piosenkę" is no guest). One rule for the run kept across a break ({@link #notePlay}) and the deck watch.
     */
    public static String setSql(String row) {
        String run = row + ".last_play_at - " + row + ".play_run_from";
        return "(" + run + " >= " + interval(RUN_LONG) + " OR (" + run + " >= " + interval(RUN_MIN)
                + " AND EXISTS (SELECT 1 FROM song_requests r WHERE r.party_code = " + row + ".party_code AND NOT r.added_by_dj"
                + " AND r.requested_at BETWEEN " + row + ".play_run_from AND " + row + ".last_play_at)))";
    }

    /** A constant duration as SQL. */
    static String interval(Duration duration) {
        return "interval '" + duration.toMinutes() + " minutes'";
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    /** "Odłącz": the device of this party forgotten, its token dead at once. */
    public boolean remove(String partyCode, long deviceId) {
        return jdbc.update("DELETE FROM dj_device WHERE id = ? AND party_code = ?", deviceId, partyCode) > 0;
    }

    /**
     * The program's own "Odłącz ten komputer": its row forgotten by its token — in any plan, as the panel's "Odłącz" (without Pro the
     * panel shows no list to take it from). False for a token unknown.
     */
    public boolean removeByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return false;
        }
        return jdbc.update("DELETE FROM dj_device WHERE token_hash = ?", hash(token)) > 0;
    }

    static String cleanName(String name) {
        String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.isEmpty()) {
            return "Komputer";
        }
        return clean.length() > NAME_MAX ? clean.substring(0, NAME_MAX) : clean;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <K> int count(Cache<K, AtomicInteger> cache, K key) {
        return count(cache, key, true);
    }

    private static <K> int count(Cache<K, AtomicInteger> cache, K key, boolean add) {
        AtomicInteger counter = cache.get(key, k -> new AtomicInteger());
        return add ? counter.incrementAndGet() : counter.get();
    }
}
