package com.scan2play.deck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.sqlite.SQLiteConfig;

/**
 * Serato DJ 4.x and later: its library and history in one SQLite database, {@code master.sqlite} ({@code %LOCALAPPDATA%} or
 * {@code %APPDATA%\Serato\Library} on Windows — Serato DJ Lite 5.1 uses the first —,
 * {@code ~/Library/Application Support/Serato/Library} on macOS — SSLScrobbler, What's Now Playing). The songs are the
 * {@code history_entry} rows of the newest session ({@link #query}). Opened read-only, never
 * written: Serato keeps writing it while it runs (its journal, {@code master.sqlite-wal}, changes with each song).
 */
final class Serato4Database implements HistorySource {

    /** At most this many songs a read: a night's set, never the whole history of a database without sessions. */
    static final int MAX_SONGS = 2000;

    private final List<Path> databases;
    private Path current;

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
        return current == null ? List.of() : read(current);
    }

    static List<Played> read(Path database) throws IOException {
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(3000);
        try (Connection connection = options.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            String query = query(columns(statement, "history_entry"), columns(statement, "history_session"));
            List<Played> songs = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    long start = rows.getLong(3);
                    Played song = new Played(rows.wasNull() ? null : Played.fromEpoch(start), rows.getString(1), rows.getString(2));
                    if (!song.isEmpty()) {
                        songs.add(song);
                    }
                }
            }
            return songs;
        } catch (SQLException e) {
            throw new IOException("Serato: " + e.getMessage(), e);
        }
    }

    /**
     * The query from the columns the tables have: Serato changes them between versions (5.1 dropped some of 4's, and its open session
     * may end in NULL rather than -1). The songs of the newest session — the one Serato writes tonight —, by their start; "played" only
     * where Serato has the column (4: a song only loaded on a deck is 0). Without artist and name nothing can be read: an error, which
     * the window shows as "nie da się odczytać".
     */
    static String query(Set<String> entry, Set<String> session) throws SQLException {
        if (!entry.contains("artist") || !entry.contains("name")) {
            throw new SQLException("history_entry without artist and name");
        }
        String time = entry.contains("start_time") ? "h.start_time" : "NULL";
        List<String> where = new ArrayList<>();
        if (entry.contains("session_id") && session.contains("id")) {
            where.add("h.session_id = (SELECT id FROM history_session ORDER BY "
                    + (session.contains("start_time") ? "start_time DESC, " : "") + "id DESC LIMIT 1)");
        }
        if (entry.contains("played")) {
            where.add("h.played = 1");
        }
        // the newest songs (by id: Serato adds them in order), then in the order they played
        String newest = "SELECT h.id, h.artist, h.name, " + time + " AS at FROM history_entry h"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY h.id DESC LIMIT " + MAX_SONGS;
        return "SELECT artist, name, at FROM (" + newest + ") ORDER BY at, id";
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
