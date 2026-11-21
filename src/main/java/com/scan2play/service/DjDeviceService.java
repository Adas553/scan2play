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
 * {@value #CODE_MINUTES} minutes (a code is one of a million and lives {@value #CODE_MINUTES} minutes: guessing one needs far more), and
 * {@value #CALLS_PER_MINUTE} calls a minute per device.
 */
@Slf4j
@Service
public class DjDeviceService {

    public static final int CODE_MINUTES = 10;
    static final int MAX_DEVICES = 3;
    static final int PAIR_TRIES_PER_CLIENT = 10;
    static final int PAIR_FAILURES = 200;
    static final int CALLS_PER_MINUTE = 30;
    /** The longest device name kept (the column). */
    static final int NAME_MAX = 60;

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

    /** A device of a party as the panel shows it. */
    public record Device(long id, String name, Instant createdAt, Instant lastSeenAt) {
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
            count(pairTries, ALL_FAILURES);
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
        return jdbc.query("SELECT id, name, created_at, last_seen_at FROM dj_device WHERE party_code = ?"
                        + " ORDER BY COALESCE(last_seen_at, created_at) DESC, id DESC LIMIT " + MAX_DEVICES,
                (row, n) -> new Device(row.getLong(1), row.getString(2), row.getTimestamp(3).toInstant(),
                        row.getTimestamp(4) == null ? null : row.getTimestamp(4).toInstant()), partyCode);
    }

    /** "Odłącz": the device of this party forgotten, its token dead at once. */
    public boolean remove(String partyCode, long deviceId) {
        return jdbc.update("DELETE FROM dj_device WHERE id = ? AND party_code = ?", deviceId, partyCode) > 0;
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
