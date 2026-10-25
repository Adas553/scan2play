package com.scan2play.service;

import com.scan2play.model.LibraryMark;
import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.LibraryKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The DJ's library (V41, V42, plan Pro): the titles and artists ({@link LibraryKey}) of the songs the DJ has, so the queue marks each
 * request "✓", "?" or "✗". Plain SQL: the library is a set of keys, written whole and asked a few titles at a time — nothing an entity
 * would add.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DjLibraryService {

    /** The most tracks of one file: a large DJ's collection, with room; a file of more is refused, not cut. */
    public static final int MAX_TRACKS = 50_000;

    /** Keys per statement of an upload: two arrays each, so 50 000 tracks are a dozen statements, not 50 000. */
    static final int INSERT_CHUNK = 5_000;

    /**
     * The parties' {@link Info} by the party code: the panel's poll asks for it every 3 seconds (its ETag), so it is read from memory,
     * the row only after an upload or a deletion (evicted after their commit) or the party's deletion ({@code AccountDeletionService}).
     */
    public static final String CACHE = "djLibrary";

    /** The longest playlist's name kept, as the column {@code dj_library.playlist}: it is only shown, so a longer one is cut. */
    static final int PLAYLIST_MAX = 200;

    /**
     * What the settings show of the library; {@code uploadedAt} also tells the panel the marks changed; {@code playlist} the rekordbox
     * playlist it is, null for a whole collection or an M3U.
     */
    public record Info(int tracks, Format format, Instant uploadedAt, String playlist) {
    }

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;

    /**
     * The party's library is now {@code library}, whole: the old one goes in the same transaction. Under the party row's lock, so
     * two uploads at once end as one of them, never a mix of both or a duplicate key.
     */
    @Transactional
    public Info replace(String partyCode, Library library) {
        jdbc.queryForObject("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode);
        jdbc.update("DELETE FROM dj_library WHERE party_code = ?", partyCode);   // its tracks go with it (ON DELETE CASCADE)
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String playlist = library.playlist() == null || library.playlist().length() <= PLAYLIST_MAX
                ? library.playlist() : library.playlist().substring(0, PLAYLIST_MAX);
        jdbc.update("INSERT INTO dj_library (party_code, track_count, format, uploaded_at, playlist) VALUES (?, ?, ?, ?, ?)",
                partyCode, library.tracks(), library.format().name(), Timestamp.from(now), playlist);
        insert(partyCode, new ArrayList<>(library.keys()), false);
        insert(partyCode, new ArrayList<>(library.swapped()), true);
        evictAfterCommit(partyCode);
        log.info("Party [{}]: library of {} tracks ({} songs) uploaded as {}{}", partyCode, library.tracks(), library.keys().size(),
                library.format(), playlist == null ? "" : " (one playlist)");
        return new Info(library.tracks(), library.format(), now, playlist);
    }

    private void insert(String partyCode, List<LibraryKey> keys, boolean swapped) {
        for (int from = 0; from < keys.size(); from += INSERT_CHUNK) {
            List<LibraryKey> chunk = keys.subList(from, Math.min(from + INSERT_CHUNK, keys.size()));
            Object[] titles = chunk.stream().map(LibraryKey::title).toArray();
            Object[] artists = chunk.stream().map(LibraryKey::artists).toArray();
            jdbc.update(connection -> {
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO dj_library_track (party_code, title_key, artists, swapped)"
                        + " SELECT ?, title, artists, ? FROM unnest(?::varchar[], ?::varchar[]) AS track (title, artists)");
                insert.setString(1, partyCode);
                insert.setBoolean(2, swapped);
                insert.setArray(3, connection.createArrayOf("varchar", titles));
                insert.setArray(4, connection.createArrayOf("varchar", artists));
                return insert;
            });
        }
    }

    /**
     * The party's library as the settings show it; empty when it has none. From {@link #CACHE}: the row is read inside the cache's own
     * load of the key, so an eviction after an upload's commit never leaves the old library behind (as {@code PartySettingsQueryService}).
     */
    public Optional<Info> info(String partyCode) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE);
        return Objects.requireNonNull(cache.get(partyCode, () -> jdbc.query(
                "SELECT track_count, format, uploaded_at, playlist FROM dj_library WHERE party_code = ?",
                (row, n) -> new Info(row.getInt(1), Format.valueOf(row.getString(2)), row.getTimestamp(3).toInstant(), row.getString(4)),
                partyCode).stream().findFirst()));
    }

    @Transactional
    public void delete(String partyCode) {
        if (jdbc.update("DELETE FROM dj_library WHERE party_code = ?", partyCode) > 0) {
            log.info("Party [{}]: library deleted", partyCode);
        }
        evictAfterCommit(partyCode);
    }

    /** After the commit, not before: a poll that read the library while the change was still open would put the old one back. */
    private void evictAfterCommit(String partyCode) {
        AfterCommit.run(() -> Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE).evict(partyCode));
    }

    /**
     * Each of the songs asked for against the party's library ({@link LibraryKey#markIn}): one statement for the tracks of all their
     * titles — a queue's at most 300, an evening's few hundred. "✗" for each when the party has no library: the caller asks
     * {@link #info} first.
     */
    public Map<LibraryKey, LibraryMark> marks(String partyCode, Collection<LibraryKey> asked) {
        Object[] titles = asked.stream().filter(key -> !key.isEmpty()).map(LibraryKey::title).distinct().toArray();
        Map<String, List<LibraryKey>> byTitle = new HashMap<>();
        Map<String, List<LibraryKey>> swappedByTitle = new HashMap<>();
        if (titles.length > 0) {
            jdbc.query(connection -> {
                PreparedStatement select = connection.prepareStatement("SELECT title_key, artists, swapped FROM dj_library_track"
                        + " WHERE party_code = ? AND title_key = ANY (?::varchar[])");
                select.setString(1, partyCode);
                select.setArray(2, connection.createArrayOf("varchar", titles));
                return select;
            }, row -> {
                LibraryKey track = new LibraryKey(row.getString(1), row.getString(2));
                (row.getBoolean(3) ? swappedByTitle : byTitle).computeIfAbsent(track.title(), title -> new ArrayList<>()).add(track);
            });
        }
        Map<LibraryKey, LibraryMark> marks = new LinkedHashMap<>();
        asked.forEach(key -> marks.put(key, key.markIn(byTitle.getOrDefault(key.title(), List.of()),
                swappedByTitle.getOrDefault(key.title(), List.of()))));
        return marks;
    }
}
