package com.scan2play.deck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.sqlite.SQLiteConfig;

/**
 * Serato DJ 4.x and later: its library and history in one SQLite database, {@code master.sqlite} ({@code %LOCALAPPDATA%} or
 * {@code %APPDATA%\Serato\Library} on Windows — Serato DJ Lite 5.1 uses the first —,
 * {@code ~/Library/Application Support/Serato/Library} on macOS — SSLScrobbler, What's Now Playing). The songs are the
 * {@code history_entry} rows marked played of the session still open ({@code history_session.end_time = -1}). Opened read-only, never
 * written: Serato keeps writing it while it runs (its journal, {@code master.sqlite-wal}, changes with each song).
 */
final class Serato4Database implements HistorySource {

    static final String QUERY = "SELECT h.artist, h.name, h.start_time FROM history_entry h"
            + " WHERE h.session_id = (SELECT id FROM history_session WHERE end_time = -1 ORDER BY start_time DESC LIMIT 1)"
            + " AND h.played = 1 ORDER BY h.start_time LIMIT 2000";

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
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(QUERY)) {
            List<Played> songs = new ArrayList<>();
            while (rows.next()) {
                long start = rows.getLong(3);
                Played song = new Played(rows.wasNull() ? null : Played.fromEpoch(start), rows.getString(1), rows.getString(2));
                if (!song.isEmpty()) {
                    songs.add(song);
                }
            }
            return songs;
        } catch (SQLException e) {
            throw new IOException("Serato 4: " + e.getMessage(), e);
        }
    }
}
