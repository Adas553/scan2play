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
import java.util.Objects;
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

    /**
     * One session's plays: the library's counts when it was first read, and the rows given its rises since. Kept between reads, so
     * a play stays with its row — also once the row falls out of the {@value #MAX_SONGS} read.
     */
    private static final class PlayCounts {
        private Path database;
        private Long session;
        private final Map<Long, Long> atStart = new HashMap<>();
        /** Per song (asset id), how many of its rises were given to a row. */
        private final Map<Long, Long> risesGiven = new HashMap<>();
        /** The history rows (history_entry.id) that played. */
        private final Set<Long> playedRows = new HashSet<>();
    }

    /** A history row as read: its song, and with the library's play count its song's id and count (null without, or no such song). */
    private record Row(long id, Played song, Long asset, Long plays) {
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
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(query(entry, session, byPlayCount))) {
                while (result.next()) {
                    long start = result.getLong(4);
                    Played song = new Played(result.wasNull() ? null : Played.fromEpoch(start), result.getString(2), result.getString(3));
                    if (!song.isEmpty()) {
                        rows.add(new Row(result.getLong(1), song, longOrNull(result, 5), longOrNull(result, 6)));
                    }
                }
            }
            if (byPlayCount) {
                givePlays(rows, counted);
                rows.removeIf(row -> !counted.playedRows.contains(row.id()));
            }
            return rows.stream().map(Row::song).toList();
        } catch (SQLException e) {
            throw new IOException("Serato: " + e.getMessage(), e);
        }
    }

    private static Long longOrNull(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /**
     * Serato 5.x writes a history row when a song is loaded on a deck, already with played = 1 (seen in DJ Lite 5.1): a song the DJ
     * only previewed or loaded by mistake would mark a guest's request as played. Its library's count (asset.dj_play_count) rises
     * only on Play, a few seconds after it, so a rise since the session was first read is a play — given to the song's newest row
     * not given one yet: the load just played, read seconds later (an earlier row of it is a preview, whose time would read as
     * "before the request"). A song played in the seconds before that first read is missed: never a song that did not play. A
     * count that fell (the DJ reset it) counts on from there.
     */
    private static void givePlays(List<Row> rows, PlayCounts counted) {
        Map<Long, Long> counts = new HashMap<>();
        for (Row row : rows) {
            if (row.asset() != null && row.plays() != null) {
                counts.put(row.asset(), row.plays());
            }
        }
        counts.forEach((asset, count) -> {
            long given = counted.risesGiven.getOrDefault(asset, 0L);
            long rises = count - counted.atStart.getOrDefault(asset, 0L);
            if (rises < given) {
                counted.atStart.put(asset, count - given);
                rises = given;
            }
            long left = rises - given;
            for (int i = rows.size() - 1; i >= 0 && left > 0; i--) {
                Row row = rows.get(i);
                if (asset.equals(row.asset()) && counted.playedRows.add(row.id())) {
                    left--;
                }
            }
            // a rise with no row left for it (two Plays of one load) is spent, not kept for the song's next load
            counted.risesGiven.put(asset, rises);
        });
    }

    /** The library's counts as the session starts being read; again for another session (Serato restarted renumbers its songs). */
    private static void startCounting(Statement statement, Path database, Long session, PlayCounts counted) throws SQLException {
        if (database.equals(counted.database) && Objects.equals(session, counted.session)) {
            return;
        }
        counted.atStart.clear();
        counted.risesGiven.clear();
        counted.playedRows.clear();
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
        return "SELECT id, artist, name, at, asset_id, plays FROM (" + newest + ") ORDER BY at, id";
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
