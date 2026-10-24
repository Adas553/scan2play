package com.scan2play.service;

import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The DJ's library (V41, plan Pro): the keys of the songs the DJ has, so the queue marks each request "✓" or "✗". Plain SQL: the
 * library is a set of keys, written whole and asked a few keys at a time — nothing an entity would add.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DjLibraryService {

    /** The most tracks of one file: a large DJ's collection, with room; a file of more is refused, not cut. */
    public static final int MAX_TRACKS = 50_000;

    /** Keys per statement of an upload: one array each, so 50 000 tracks are a dozen statements, not 50 000. */
    static final int INSERT_CHUNK = 5_000;

    /** What the settings show of the library; {@code uploadedAt} also tells the panel the marks changed. */
    public record Info(int tracks, Format format, Instant uploadedAt) {
    }

    private final JdbcTemplate jdbc;

    /**
     * The party's library is now {@code library}, whole: the old one goes in the same transaction. Under the party row's lock, so
     * two uploads at once end as one of them, never a mix of both or a duplicate key.
     */
    @Transactional
    public Info replace(String partyCode, Library library) {
        jdbc.queryForObject("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode);
        jdbc.update("DELETE FROM dj_library WHERE party_code = ?", partyCode);   // its tracks go with it (ON DELETE CASCADE)
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES (?, ?, ?, ?)",
                partyCode, library.tracks(), library.format().name(), Timestamp.from(now));
        List<String> keys = new ArrayList<>(library.keys());
        for (int from = 0; from < keys.size(); from += INSERT_CHUNK) {
            Object[] chunk = keys.subList(from, Math.min(from + INSERT_CHUNK, keys.size())).toArray();
            jdbc.update(connection -> {
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO dj_library_track (party_code, match_key) SELECT ?, unnest(?::varchar[])");
                insert.setString(1, partyCode);
                insert.setArray(2, connection.createArrayOf("varchar", chunk));
                return insert;
            });
        }
        log.info("Party [{}]: library of {} tracks ({} songs) uploaded as {}", partyCode, library.tracks(), keys.size(),
                library.format());
        return new Info(library.tracks(), library.format(), now);
    }

    /** The party's library as the settings show it; empty when it has none. */
    public Optional<Info> info(String partyCode) {
        return jdbc.query("SELECT track_count, format, uploaded_at FROM dj_library WHERE party_code = ?",
                (row, n) -> new Info(row.getInt(1), Format.valueOf(row.getString(2)), row.getTimestamp(3).toInstant()),
                partyCode).stream().findFirst();
    }

    @Transactional
    public void delete(String partyCode) {
        if (jdbc.update("DELETE FROM dj_library WHERE party_code = ?", partyCode) > 0) {
            log.info("Party [{}]: library deleted", partyCode);
        }
    }

    /** Which of {@code keys} — the queue's few songs, at most its 300 — are in the party's library: one statement. */
    public Set<String> found(String partyCode, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        Object[] asked = keys.toArray();
        return new HashSet<>(jdbc.query(connection -> {
            PreparedStatement select = connection.prepareStatement(
                    "SELECT match_key FROM dj_library_track WHERE party_code = ? AND match_key = ANY (?::varchar[])");
            select.setString(1, partyCode);
            Array array = connection.createArrayOf("varchar", asked);
            select.setArray(2, array);
            return select;
        }, (row, n) -> row.getString(1)));
    }
}
