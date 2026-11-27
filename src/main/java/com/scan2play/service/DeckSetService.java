package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.model.LibraryMark;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.LibraryKey;
import com.scan2play.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The night's set (docs/ideas.md item 21, 4; V83 {@code deck_play}): every song the DJ's program sends is kept for the party — not
 * only those that answer a guest's request ({@link DeckHistoryService}) — while "Zapisuj set wieczoru" is on (the program's card, on
 * by default). What it is for:
 * <ul>
 *     <li>"Już leciało o 21:30" ({@link #playedLately}): a guest asks for a song among the DJ's last "Bez powtórek z ostatnich N" of the
 *     set — told so at once, before the AI is asked (no Gemini call);</li>
 *     <li>"Podsumowanie wieczoru" ({@link #evening}): the whole set, not only the requests;</li>
 *     <li>the guests' "Co to było?" ({@link #lastPlayed}): the last {@value #GUESTS_SHOWN} songs, fetched only when unfolded.</li>
 * </ul>
 * Never in "Trendy próśb" (the DJ's set is the DJ's, not the guests' wishes). Kept {@value #KEPT_DAYS} days, as the requests
 * ({@link SongRequestRetentionService}); switched off, the party's kept set is deleted at once.
 * <p>
 * Bounded: at most {@value DeckHistoryService#MAX_PLAYS} songs a call (the API's limit), {@value #DAILY_PER_DEVICE} kept a day per
 * computer (in memory; a program gone wild fills no table), every read one party's newest rows by {@code idx_deck_play_party_started}
 * with a LIMIT. A song sent again — the program restarted and read its last minutes again, the clock's correction moved it by a
 * second — is kept once: the same keys within {@link #SAME_PLAY} of a kept one are dropped, under the party's advisory lock.
 */
@Slf4j
@Service
public class DeckSetService {

    public static final int KEPT_DAYS = 30;
    public static final int DAILY_PER_DEVICE = 600;
    /** "Co to było?": the songs the guests see. */
    public static final int GUESTS_SHOWN = 10;
    /** The longest "Bez powtórek z ostatnich N" looked at (the setting's own maximum is 50). */
    static final int WINDOW_MAX = 100;
    /** The most songs of one evening read for its summary: more than a night's set (~20 an hour). */
    public static final int EVENING_MAX = 1000;
    static final Duration SAME_PLAY = Duration.ofMinutes(2);
    /** "Tonight": what the guests and "Już leciało" look at — as old as a song the program may still send. */
    static final Duration TONIGHT = DeckHistoryService.OLDEST;
    /** How long the guests' "Co to było?" is kept in memory: a party's guests opening it at once ask the database once. */
    static final Duration GUESTS_CACHED = Duration.ofSeconds(30);
    /** The longest artist or title kept: as the keys' columns ({@link LibraryKey#MAX}). */
    static final int FIELD_MAX = 200;
    static final int PROGRAM_MAX = 30;

    /** One song of the set: when it started, its artist and title as the program wrote them, the program. */
    public record SetSong(Instant startedAt, String artist, String title, String program) {

        /** "Artist - Title", as the queue writes a song; the title alone when the file had no artist (Serato's "Artist - Title"). */
        public String name() {
            return artist == null || artist.isBlank() ? title : artist + " - " + title;
        }

        LibraryKey key() {
            return artist == null || artist.isBlank() ? LibraryKey.of(title) : LibraryKey.of(artist, title);
        }
    }

    private final JdbcTemplate jdbc;
    /** A computer and a day → the songs kept for it that day. */
    private final Cache<String, AtomicInteger> keptToday;
    /** A party → its last songs, for the guests. */
    private final Cache<String, List<SetSong>> forGuests;

    public DeckSetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.keptToday = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(25)).maximumSize(100_000).build();
        this.forGuests = Caffeine.newBuilder().expireAfterWrite(GUESTS_CACHED).maximumSize(10_000).build();
    }

    /**
     * Keeps the songs of one call of a computer's program — unless the party switched it off; the songs older than tonight, those with
     * no title and those past the computer's {@value #DAILY_PER_DEVICE} of the day are left out. One statement for the call, under the
     * party's lock (two calls at once — two computers — keep a song once).
     *
     * @param songs the songs on the server's clock (a song with no time: now)
     * @return how many were kept
     */
    @Transactional
    public int keep(long deviceId, String partyCode, List<SetSong> songs, Instant now) {
        List<SetSong> fresh = new ArrayList<>();
        for (SetSong song : songs) {
            Instant at = song.startedAt() == null || song.startedAt().isAfter(now) ? now : song.startedAt();
            SetSong cut = new SetSong(at, cut(song.artist(), FIELD_MAX), cut(song.title(), FIELD_MAX), cut(song.program(), PROGRAM_MAX));
            if (cut.title() != null && !cut.key().isEmpty() && !at.isBefore(now.minus(TONIGHT))
                    && fresh.stream().noneMatch(kept -> samePlay(kept, cut))) {
                fresh.add(cut);
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        AtomicInteger today = keptToday.get(deviceId + ":" + LocalDate.ofInstant(now, Times.DISPLAY_ZONE), key -> new AtomicInteger());
        int room = DAILY_PER_DEVICE - today.get();
        if (room <= 0) {
            return 0;   // said in the log once, when it was reached
        }
        List<SetSong> sent = fresh.size() > room ? fresh.subList(0, room) : fresh;
        lock(partyCode);
        int kept = jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO deck_play (party_code, started_at, artist, title, title_key, artists_key, program)"
                            + " SELECT ?, s.started_at, s.artist, s.title, s.title_key, s.artists_key, s.program"
                            + " FROM unnest(?::timestamptz[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[])"
                            + " AS s(started_at, artist, title, title_key, artists_key, program)"
                            + " WHERE EXISTS (SELECT 1 FROM party_settings p WHERE p.party_code = ? AND p.deck_set_kept)"
                            + " AND NOT EXISTS (SELECT 1 FROM deck_play d WHERE d.party_code = ?"
                            + " AND d.started_at BETWEEN s.started_at - interval '" + SAME_PLAY.toSeconds() + " seconds'"
                            + " AND s.started_at + interval '" + SAME_PLAY.toSeconds() + " seconds'"
                            + " AND d.title_key = s.title_key AND d.artists_key = s.artists_key)");
            statement.setString(1, partyCode);
            statement.setArray(2, connection.createArrayOf("timestamptz", sent.stream().map(s -> Timestamp.from(s.startedAt())).toArray()));
            statement.setArray(3, texts(connection, sent.stream().map(SetSong::artist).toList()));
            statement.setArray(4, texts(connection, sent.stream().map(SetSong::title).toList()));
            statement.setArray(5, texts(connection, sent.stream().map(s -> s.key().title()).toList()));
            statement.setArray(6, texts(connection, sent.stream().map(s -> s.key().artists()).toList()));
            statement.setArray(7, texts(connection, sent.stream().map(SetSong::program).toList()));
            statement.setString(8, partyCode);
            statement.setString(9, partyCode);
            return statement;
        });
        if (kept > 0) {
            // after the commit: a guest's read before it would put the old list back for half a minute
            AfterCommit.run(() -> forGuests.invalidate(partyCode));
            if (today.addAndGet(kept) >= DAILY_PER_DEVICE) {
                log.warn("Computer {} of party {} reached {} songs kept today: no more of its set kept until tomorrow", deviceId,
                        partyCode, DAILY_PER_DEVICE);
            }
        }
        return kept;
    }

    /** The party's set, one writer at a time: two computers' calls, a call and "off" (whose delete would miss a song kept meanwhile). */
    private void lock(String partyCode) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) l", Integer.class, "deck_play:" + partyCode);
    }

    private static Array texts(java.sql.Connection connection, List<String> values) throws java.sql.SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    private static boolean samePlay(SetSong one, SetSong other) {
        return one.key().equals(other.key()) && Duration.between(one.startedAt(), other.startedAt()).abs().compareTo(SAME_PLAY) <= 0;
    }

    /**
     * "Już leciało": the start of the song among the party's last {@code window} of tonight's set — since its first guest's request —
     * that the guest's {@code songName}
     * is — its title and an artist in common ("✓", as a request is marked played); a title alone matches nothing (another artist's
     * song of that name is no repeat). Empty with {@code window} 0 ("Bez powtórek" off: repeats are the DJ's choice).
     */
    public Optional<Instant> playedLately(String partyCode, String songName, int window, Instant now) {
        LibraryKey wanted = LibraryKey.of(songName);
        if (window <= 0 || wanted.isEmpty() || wanted.artists().isEmpty()) {
            return Optional.empty();
        }
        Timestamp tonight = Timestamp.from(now.minus(TONIGHT));
        // only what the guests heard: the songs since the evening's first guest's request (a soundcheck before they came is none;
        // the evening's very first request checks nothing) — the DJ's own "+ Dodaj piosenkę" is no guest
        List<Kept> same = jdbc.query("SELECT started_at, title_key, artists_key FROM (SELECT started_at, title_key, artists_key"
                        + " FROM deck_play WHERE party_code = ? AND started_at >= ? AND started_at >= COALESCE((SELECT min(r.requested_at)"
                        + " FROM song_requests r WHERE r.party_code = ? AND r.requested_at >= ? AND NOT r.added_by_dj), 'infinity')"
                        + " ORDER BY started_at DESC LIMIT ?) last WHERE title_key = ? ORDER BY started_at DESC",
                (row, n) -> new Kept(row.getTimestamp(1).toInstant(), new LibraryKey(row.getString(2), row.getString(3))),
                partyCode, tonight, partyCode, tonight, Math.min(window, WINDOW_MAX), wanted.title());
        return same.stream().filter(kept -> wanted.matchIn(List.of(kept.key()), Set.of()).mark() == LibraryMark.HAVE)
                .map(Kept::startedAt).findFirst();
    }

    private record Kept(Instant startedAt, LibraryKey key) {
    }

    /** "Co to było?": tonight's last {@value #GUESTS_SHOWN} songs, the newest first; kept {@link #GUESTS_CACHED} in memory. */
    public List<SetSong> lastPlayed(String partyCode, Instant now) {
        return forGuests.get(partyCode, code -> songs("SELECT started_at, artist, title, program FROM deck_play"
                + " WHERE party_code = ? AND started_at >= ? ORDER BY started_at DESC LIMIT " + GUESTS_SHOWN,
                code, Timestamp.from(now.minus(TONIGHT))));
    }

    /** The set of the evening from {@code from} to {@code to}, the first song first, at most {@value #EVENING_MAX}. */
    public List<SetSong> evening(String partyCode, Instant from, Instant to) {
        return songs("SELECT started_at, artist, title, program FROM deck_play WHERE party_code = ? AND started_at >= ?"
                        + " AND started_at < ? ORDER BY started_at LIMIT " + EVENING_MAX,
                partyCode, Timestamp.from(from), Timestamp.from(to));
    }

    /** How many songs the evening from {@code from} to {@code to} has, counted up to {@value #EVENING_MAX} (the morning e-mail). */
    public int countEvening(String partyCode, Instant from, Instant to) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM deck_play WHERE party_code = ? AND started_at >= ?"
                + " AND started_at < ? LIMIT " + EVENING_MAX + ") s", Integer.class, partyCode, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    private List<SetSong> songs(String sql, Object... args) {
        return jdbc.query(sql, (row, n) -> new SetSong(row.getTimestamp(1).toInstant(), row.getString(2), row.getString(3),
                row.getString(4)), args);
    }

    /** "Zapisuj set wieczoru" as the party has it. */
    public boolean kept(String partyCode) {
        List<Boolean> found = jdbc.queryForList("SELECT deck_set_kept FROM party_settings WHERE party_code = ?", Boolean.class,
                partyCode);
        return !found.isEmpty() && Boolean.TRUE.equals(found.getFirst());
    }

    /** "Zapisuj set wieczoru" switched; off deletes what the party kept (the DJ said: do not keep my set). */
    @Transactional
    public void setKept(String partyCode, boolean on) {
        lock(partyCode);
        jdbc.update("UPDATE party_settings SET deck_set_kept = ? WHERE party_code = ?", on, partyCode);
        if (!on) {
            int deleted = jdbc.update("DELETE FROM deck_play WHERE party_code = ?", partyCode);
            log.info("Party {} keeps no set any more: {} song(s) deleted", partyCode, deleted);
        }
        AfterCommit.run(() -> forGuests.invalidate(partyCode));
    }

    /** Deletes up to {@code batch} songs that started before {@code cutoff}; the nightly purge calls it until fewer come. */
    public int deleteStartedBefore(Instant cutoff, int batch) {
        return jdbc.update("DELETE FROM deck_play WHERE id IN (SELECT id FROM deck_play WHERE started_at < ? LIMIT ?)",
                Timestamp.from(cutoff), batch);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String one = value.strip();
        return one.isEmpty() ? null : one.length() > max ? one.substring(0, max) : one;
    }
}
