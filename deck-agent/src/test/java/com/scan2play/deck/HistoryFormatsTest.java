package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DJ programs' histories as the open-source readers describe them (docs/ideas.md item 18). */
class HistoryFormatsTest {

    private static final long NIGHT = 1795300000L;

    @Test
    void virtualDj_theExtvdjLines_withTheirTimeAndEntities() {
        String file = "\uFEFF#EXTM3U\r\n"
                + "#EXTVDJ:<time>21:39</time><lastplaytime>" + NIGHT + "</lastplaytime><artist>Kayah &amp; Bregović</artist>"
                + "<title>Prawy do lewego</title>\r\nC:\\Muzyka\\kayah.mp3\r\n"
                + "#EXTVDJ:<time>21:43</time><artist>Sanah</artist><title>Szampan</title><remix>Radio</remix>\r\nnetsearch://dz1\r\n"
                + "#EXTVDJ:<time>21:44</time>\r\nempty.mp3\r\n";

        List<Played> songs = VirtualDjHistory.parse(file);

        assertEquals(List.of(new Played(Instant.ofEpochSecond(NIGHT), "Kayah & Bregović", "Prawy do lewego"),
                new Played(null, "Sanah", "Szampan")), songs);
    }

    @Test
    void serato_onlyThePlayedSongs_byTheirStart() {
        byte[] session = concat(chunk("vrsn", utf16("1.0/Serato Scratch LIVE Review")),
                song(NIGHT + 300, "Zenon Martyniuk", "Przekorny los", true),
                song(NIGHT + 60, "Preview", "Tylko załadowana", false),
                song(NIGHT, "Sanah", "Szampan", true));

        assertEquals(List.of(new Played(Instant.ofEpochSecond(NIGHT), "Sanah", "Szampan"),
                new Played(Instant.ofEpochSecond(NIGHT + 300), "Zenon Martyniuk", "Przekorny los")), SeratoSessions.parse(session));
    }

    @Test
    void serato_aFileCutInTheMiddle_givesWhatIsWhole() {
        byte[] whole = concat(song(NIGHT, "Sanah", "Szampan", true), song(NIGHT + 60, "Dżem", "Wehikuł czasu", true));
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 7);

        assertEquals("Sanah", SeratoSessions.parse(cut).getFirst().artist());
    }

    @Test
    void serato4_thePlayedSongsOfTheOpenSession(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("master.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE history_session (id INTEGER PRIMARY KEY, start_time INTEGER, end_time INTEGER)");
            sql.executeUpdate("CREATE TABLE history_entry (id INTEGER PRIMARY KEY, session_id INTEGER, artist TEXT, name TEXT,"
                    + " start_time INTEGER, played INTEGER, deck INTEGER)");
            sql.executeUpdate("INSERT INTO history_session VALUES (1, " + (NIGHT - 90000) + ", " + (NIGHT - 80000) + ")");
            sql.executeUpdate("INSERT INTO history_session VALUES (2, " + (NIGHT - 60) + ", -1)");
            sql.executeUpdate("INSERT INTO history_entry (session_id, artist, name, start_time, played, deck) VALUES"
                    + " (1, 'Wczoraj', 'Inny dzień', " + (NIGHT - 85000) + ", 1, 1),"
                    + " (2, 'Dawid Podsiadło', 'Małomiasteczkowy', " + (NIGHT + 200) + ", 1, 2),"
                    + " (2, 'Preview', 'Nie zagrana', " + (NIGHT + 30) + ", 0, 1),"
                    + " (2, 'Sanah', 'Szampan', " + NIGHT + ", 1, 1)");
        }

        assertEquals(List.of(new Played(Instant.ofEpochSecond(NIGHT), "Sanah", "Szampan"),
                new Played(Instant.ofEpochSecond(NIGHT + 200), "Dawid Podsiadło", "Małomiasteczkowy")), Serato4Database.read(database));
    }

    @Test
    void times_inMilliseconds_andFrom2001_areRead() {
        assertEquals(Instant.ofEpochSecond(NIGHT), Played.fromEpoch(NIGHT * 1000));
        assertEquals(Instant.ofEpochSecond(NIGHT), Played.fromEpoch(NIGHT - 978_307_200L));
        assertEquals(null, Played.fromEpoch(0));
    }

    @Test
    void theNewestFile_ofTheFirstFolderThatHasOne(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("AppData/Local/VirtualDJ/History"));
        Path documents = Files.createDirectories(dir.resolve("Documents/VirtualDJ/History"));
        Files.writeString(documents.resolve("2026-11-20.m3u"), "x");
        Files.writeString(documents.resolve("tracklist.txt"), "x");
        Path today = Files.writeString(documents.resolve("2026-11-21.m3u"), "x");
        Files.setLastModifiedTime(documents.resolve("2026-11-20.m3u"), java.nio.file.attribute.FileTime.fromMillis(1000));

        assertEquals(today, NewestFile.in(List.of(empty, documents), ".m3u").orElseThrow());
    }

    @Test
    void windows_theFoldersAsADjsLaptopHasThem() {
        Platform windows = new Platform(Path.of("C:\\Users\\dj"), Map.of("LOCALAPPDATA", "C:\\Users\\dj\\AppData\\Local",
                "APPDATA", "C:\\Users\\dj\\AppData\\Roaming"), true);
        assertTrue(windows.seratoSessionFolders().getFirst().endsWith(Path.of("Music", "_Serato_", "History", "Sessions")));
        assertEquals(Path.of("C:\\Users\\dj\\Documents\\VirtualDJ"), Platform.homeFolderOf(
                "\r\nHKEY_CURRENT_USER\\Software\\VirtualDJ\r\n    HomeFolder    REG_SZ    C:\\Users\\dj\\Documents\\VirtualDJ\r\n").orElseThrow());
    }

    // ---- Serato's chunks ----

    private static byte[] utf16(String text) {
        return text.getBytes(StandardCharsets.UTF_16BE);
    }

    private static byte[] chunk(String name, byte[] body) {
        return ByteBuffer.allocate(8 + body.length).put(name.getBytes(StandardCharsets.ISO_8859_1)).putInt(body.length).put(body).array();
    }

    private static byte[] field(int id, byte[] body) {
        return ByteBuffer.allocate(8 + body.length).putInt(id).putInt(body.length).put(body).array();
    }

    private static byte[] song(long start, String artist, String title, boolean played) {
        return chunk("oent", chunk("adat", concat(field(6, utf16(title)), field(7, utf16(artist)),
                field(28, ByteBuffer.allocate(4).putInt((int) start).array()), field(31, ByteBuffer.allocate(4).putInt(1).array()),
                field(50, new byte[]{(byte) (played ? 1 : 0)}))));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
