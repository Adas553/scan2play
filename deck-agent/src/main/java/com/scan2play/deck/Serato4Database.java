package com.scan2play.deck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sqlite.SQLiteConfig;

/**
 * Serato DJ 4.x and later: its library and history in one SQLite database, {@code master.sqlite} ({@code %LOCALAPPDATA%} or
 * {@code %APPDATA%\Serato\Library} on Windows — Serato DJ Lite 5.1 uses the first —,
 * {@code ~/Library/Application Support/Serato/Library} on macOS — SSLScrobbler, What's Now Playing). The songs are the
 * {@code history_entry} rows of the newest session ({@link #query}) that played ({@link #played}). Opened read-only, never
 * written: Serato keeps writing it while it runs (its journal, {@code master.sqlite-wal}, changes with each song).
 */
final class Serato4Database implements HistorySource {

    /** At most this many songs a read: a night's set, never the whole history of a database without sessions. */
    static final int MAX_SONGS = 2000;

    private final List<Path> databases;
    private Path current;
    /** Where the library's play counts were taken as the session's start ({@link #read}); one database, one session at a time. */
    private final PlayCounts counted = new PlayCounts();

    Serato4Database(List<Path> databases) {
        this.databases = databases;
    }

    @Override
    public String program() {
        return "Serato";
    }

    @Override
    public boolean present() {
        return databases.stream().anyMatch(Files::isRegularFile);
    }

    @Override
    public String version() throws IOException {
        current = databases.stream().filter(Files::isRegularFile).max(java.util.Comparator.comparing(Serato4Database::writtenAt)).orElse(null);
        if (current == null) {
            return "";
        }
        Path journal = current.resolveSibling(current.getFileName() + "-wal");
        return NewestFile.version(current) + (Files.isRegularFile(journal) ? "|" + NewestFile.version(journal) : "");
    }

    /** When Serato last wrote the database: its journal ({@code -wal}) changes with each song, the file itself less often. */
    private static java.nio.file.attribute.FileTime writtenAt(Path database) {
        try {
            Path journal = database.resolveSibling(database.getFileName() + "-wal");
            java.nio.file.attribute.FileTime file = Files.getLastModifiedTime(database);
            if (Files.isRegularFile(journal)) {
                java.nio.file.attribute.FileTime wal = Files.getLastModifiedTime(journal);
                return wal.compareTo(file) > 0 ? wal : file;
            }
            return file;
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    /** The database read (the tests). */
    Path current() {
        return current;
    }

    @Override
    public String reading() {
        return current == null ? "" : current.getFileName().toString();
    }

    @Override
    public List<Played> songs() throws IOException {
        return current == null ? List.of() : read(current, counted);
    }

    private static final class PlayCounts {
        private Path database;
        private Long session;
        private final Map<Long, Long> atStart = new HashMap<>();
    }

    /** A database read without remembering anything (the tests of the tables without play counts). */
    static List<Played> read(Path database) throws IOException {
        return read(database, new PlayCounts());
    }

    private static List<Played> read(Path database, PlayCounts counted) throws IOException {
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(3000);
        try (Connection connection = options.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            Set<String> entry = columns(statement, "history_entry");
            Set<String> session = columns(statement, "history_session");
            Set<String> asset = columns(statement, "asset");
            boolean byPlayCount = entry.contains("asset_id") && asset.contains("id") && asset.contains("dj_play_count");
            if (byPlayCount) {
                startCounting(statement, database, newestSession(statement, entry, session), counted);
            }
            List<Played> songs = new ArrayList<>();
            Map<Long, Long> playsGiven = new HashMap<>();
            try (ResultSet rows = statement.executeQuery(query(entry, session, byPlayCount))) {
                while (rows.next()) {
                    long start = rows.getLong(3);
                    Played song = new Played(rows.wasNull() ? null : Played.fromEpoch(start), rows.getString(1), rows.getString(2));
                    if (song.isEmpty() || byPlayCount && !played(rows, counted, playsGiven)) {
                        continue;
                    }
                    songs.add(song);
                }
            }
            return songs;
        } catch (SQLException e) {
            throw new IOException("Serato: " + e.getMessage(), e);
        }
    }

    /**
     * Serato 5.x writes a history row when a song is loaded on a deck, already with played = 1 (seen in DJ Lite 5.1): a song the DJ
     * only previewed or loaded by mistake would mark a guest's request as played. Its library's count (asset.dj_play_count) rises
     * only on Play, so a row is a play when its song's count has risen since the session was first read — each rise given to one
     * row, the earliest first (a song played twice, two rows). A song played in the seconds before that first read is missed: never
     * a song that did not play.
     */
    private static boolean played(ResultSet row, PlayCounts counted, Map<Long, Long> playsGiven) throws SQLException {
        long asset = row.getLong(4);
        if (row.wasNull()) {
            return false;
        }
        long count = row.getLong(5);
        long given = playsGiven.getOrDefault(asset, 0L);
        if (count - counted.atStart.getOrDefault(asset, 0L) <= given) {
            return false;
        }
        playsGiven.put(asset, given + 1);
        return true;
    }

    /** The library's counts as the session starts being read; again for another session (Serato restarted renumbers its songs). */
    private static void startCounting(Statement statement, Path database, Long session, PlayCounts counted) throws SQLException {
        if (database.equals(counted.database) && java.util.Objects.equals(session, counted.session)) {
            return;
        }
        counted.atStart.clear();
        try (ResultSet rows = statement.executeQuery("SELECT id, dj_play_count FROM asset WHERE dj_play_count > 0")) {
            while (rows.next()) {
                counted.atStart.put(rows.getLong(1), rows.getLong(2));
            }
        }
        counted.database = database;
        counted.session = session;
    }

    private static Long newestSession(Statement statement, Set<String> entry, Set<String> session) throws SQLException {
        String newest = newestSessionQuery(entry, session);
        if (newest == null) {
            return null;
        }
        try (ResultSet rows = statement.executeQuery(newest)) {
            return rows.next() ? rows.getLong(1) : null;
        }
    }

    private static String newestSessionQuery(Set<String> entry, Set<String> session) {
        return entry.contains("session_id") && session.contains("id")
                ? "SELECT id FROM history_session ORDER BY " + (session.contains("start_time") ? "start_time DESC, " : "") + "id DESC LIMIT 1"
                : null;
    }

    /**
     * The query from the columns the tables have: Serato changes them between versions (5.1's open session may end in NULL rather
     * than -1). The songs of the newest session — the one Serato writes tonight —, by their start; "played" where Serato has the column
     * (4: a song only loaded on a deck is 0; 5.1: always 1, hence the library's play count as well, {@link #played}). Without artist and
     * name nothing can be read: an error, which the window shows as "nie da się odczytać".
     */
    static String query(Set<String> entry, Set<String> session, boolean byPlayCount) throws SQLException {
        if (!entry.contains("artist") || !entry.contains("name")) {
            throw new SQLException("history_entry without artist and name");
        }
        String time = entry.contains("start_time") ? "h.start_time" : "NULL";
        List<String> where = new ArrayList<>();
        String newestSession = newestSessionQuery(entry, session);
        if (newestSession != null) {
            where.add("h.session_id = (" + newestSession + ")");
        }
        if (entry.contains("played")) {
            where.add("h.played = 1");
        }
        String counts = byPlayCount ? ", h.asset_id, a.dj_play_count AS plays" : ", NULL AS asset_id, NULL AS plays";
        // the newest songs (by id: Serato adds them in order), then in the order they played
        String newest = "SELECT h.id, h.artist, h.name, " + time + " AS at" + counts + " FROM history_entry h"
                + (byPlayCount ? " LEFT JOIN asset a ON a.id = h.asset_id" : "")
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY h.id DESC LIMIT " + MAX_SONGS;
        return "SELECT artist, name, at, asset_id, plays FROM (" + newest + ") ORDER BY at, id";
    }

    private static Set<String> columns(Statement statement, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                names.add(rows.getString("name").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return names;
    }
}
