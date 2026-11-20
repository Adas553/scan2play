package com.scan2play.service;

import com.scan2play.util.SongNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Trendy próśb" (docs/ideas.md item 16): the songs the guests of every party asked for in the last {@value #DAYS} days, counted by
 * parties, not by votes — one big room with forty 👍 is one party. Only what the AI let through (accepted, played, skipped or
 * cleared later — not an AI's refusal), checked by the AI, never the DJ's own additions or the demo's. A song by its
 * {@link SongNames#comparable} name, shown as it was written most often.
 * <p>
 * The thresholds are what keeps a list anonymous before anyone but the owner sees it: a song on fewer than
 * {@value #MIN_PARTIES_PER_SONG} parties is left out (no single party can be read off the list), and with fewer than
 * {@value #MIN_PARTIES} parties in the period the list is only noise ({@link Result#belowMinimum}). For now only the owner's daily
 * report shows it — whether the data is enough for a DJ's card.
 */
@Service
@Slf4j
public class SongTrendsService {

    static final int DAYS = 30;
    /** "Rising": the parties of the last {@value #HALF_DAYS} days against the {@value #HALF_DAYS} before them. */
    static final int HALF_DAYS = 14;
    static final int MIN_PARTIES_PER_SONG = 3;
    static final int MIN_PARTIES = 10;
    static final int TOP = 20;
    /**
     * The most (party, song) rows one count reads. 30 days of requests are deleted nightly, so it is far above what there is; reached,
     * the count says so ({@link Result#truncated}) and the newest rows are the ones kept.
     */
    static final int MAX_ROWS = 50_000;

    /**
     * The count: how many parties had a request the AI let through, how many songs pass the threshold, whether the period has too few
     * parties to mean anything, whether the rows were cut at {@value #MAX_ROWS}, and the top {@value #TOP}.
     */
    public record Result(int parties, int songs, boolean belowMinimum, boolean truncated, List<Trend> top) {
    }

    /**
     * One song: its name, on how many parties in the {@value #DAYS} days, in the last {@value #HALF_DAYS} and in the
     * {@value #HALF_DAYS} before, and which way it goes: "new" (none before), "up", "down" or "flat".
     */
    public record Trend(String song, int parties, int recent, int earlier, String direction) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public SongTrendsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    SongTrendsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The trends of the {@value #DAYS} days before now. */
    public Result trends() {
        return trends(clock.instant());
    }

    /** One row of the query: a song of a party, and whether the party asked for it in the recent half, the earlier one. */
    record Row(String partyCode, String song, boolean recent, boolean earlier) {
    }

    Result trends(Instant now) {
        Instant since = now.minus(Duration.ofDays(DAYS));
        Instant recent = now.minus(Duration.ofDays(HALF_DAYS));
        Instant earlier = now.minus(Duration.ofDays(2L * HALF_DAYS));
        // idx_song_requests_requested_at finds the period; an AI's refusal is "rejected" with no skip and no clearing
        List<Row> rows = jdbc.query("SELECT party_code, song_name, bool_or(requested_at >= ?), bool_or(requested_at >= ? AND requested_at < ?)"
                        + " FROM song_requests WHERE requested_at >= ? AND requested_at < ? AND checked AND NOT added_by_dj"
                        + " AND party_code NOT IN (?, ?)"
                        + " AND NOT (decision = 'rejected' AND skipped_at IS NULL AND cleared_at IS NULL)"
                        + " GROUP BY party_code, song_name ORDER BY max(requested_at) DESC LIMIT ?",
                (row, n) -> new Row(row.getString(1), row.getString(2), row.getBoolean(3), row.getBoolean(4)),
                Timestamp.from(recent), Timestamp.from(earlier), Timestamp.from(recent), Timestamp.from(since), Timestamp.from(now),
                DemoParty.CODE, DemoParty.CODE_EN, MAX_ROWS + 1);
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            log.warn("Song trends: more than {} (party, song) rows in {} days; the oldest left out", MAX_ROWS, DAYS);
            rows = rows.subList(0, MAX_ROWS);
        }
        return count(rows, truncated);
    }

    /** The rows counted by song: parties, the two halves, the name written most often (the first of a tie by the alphabet). */
    static Result count(List<Row> rows, boolean truncated) {
        Set<String> parties = new HashSet<>();
        Map<String, Song> songs = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = SongNames.comparable(row.song());
            if (key.isEmpty()) {
                continue;
            }
            parties.add(row.partyCode());
            songs.computeIfAbsent(key, k -> new Song()).add(row);
        }
        List<Song> passing = songs.values().stream().filter(song -> song.parties.size() >= MIN_PARTIES_PER_SONG).toList();
        List<Trend> top = passing.stream().map(Song::trend)
                .sorted(Comparator.comparingInt(Trend::parties).reversed().thenComparing(Comparator.comparingInt(Trend::recent).reversed())
                        .thenComparing(Trend::song))
                .limit(TOP).toList();
        return new Result(parties.size(), passing.size(), parties.size() < MIN_PARTIES, truncated, top);
    }

    private static final class Song {
        final Set<String> parties = new HashSet<>();
        final Set<String> recent = new HashSet<>();
        final Set<String> earlier = new HashSet<>();
        final Map<String, Set<String>> names = new HashMap<>();

        void add(Row row) {
            parties.add(row.partyCode());
            if (row.recent()) {
                recent.add(row.partyCode());
            }
            if (row.earlier()) {
                earlier.add(row.partyCode());
            }
            names.computeIfAbsent(row.song(), name -> new HashSet<>()).add(row.partyCode());
        }

        Trend trend() {
            String name = names.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, Set<String>>>comparingInt(e -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).orElseThrow();
            String direction = earlier.isEmpty() ? (recent.isEmpty() ? "flat" : "new")
                    : recent.size() > earlier.size() ? "up" : recent.size() < earlier.size() ? "down" : "flat";
            return new Trend(name, parties.size(), recent.size(), earlier.size(), direction);
        }
    }
}
