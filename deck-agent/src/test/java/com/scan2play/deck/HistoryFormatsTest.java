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
    void theNewestFile_withTheExtension_anEmptyFolderSkipped(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("AppData/Local/VirtualDJ/History"));
        Path documents = Files.createDirectories(dir.resolve("Documents/VirtualDJ/History"));
        Files.writeString(documents.resolve("2026-11-20.m3u"), "x");
        Files.writeString(documents.resolve("tracklist.txt"), "x");
        Path today = Files.writeString(documents.resolve("2026-11-21.m3u"), "x");
        Files.setLastModifiedTime(documents.resolve("2026-11-20.m3u"), java.nio.file.attribute.FileTime.fromMillis(1000));

        assertEquals(today, NewestFile.in(List.of(empty, documents), ".m3u").orElseThrow());
    }

    /**
     * An old install's history left in one folder (the registry's HomeFolder, the Documents) while VirtualDJ writes tonight's in
     * another (AppData): the file written last is tonight's, whichever folder comes first.
     */
    @Test
    void theNewestFile_ofAllTheFolders_notTheFirstOneWithAFile(@TempDir Path dir) throws Exception {
        Path old = Files.createDirectories(dir.resolve("Documents/VirtualDJ/History"));
        Path appData = Files.createDirectories(dir.resolve("AppData/Local/VirtualDJ/History"));
        Files.writeString(old.resolve("2024-05-01.m3u"), "x");
        Files.setLastModifiedTime(old.resolve("2024-05-01.m3u"), java.nio.file.attribute.FileTime.fromMillis(1_714_000_000_000L));
        Path today = Files.writeString(appData.resolve("2026-11-21.m3u"), "x");

        assertEquals(today, NewestFile.in(List.of(old, appData), ".m3u").orElseThrow());
    }

    /** Windows with OneDrive's backup of the folders: the Documents and the Music are under %OneDrive%, the old ones stay listed. */
    @Test
    void windows_withOneDrive_looksInItsDocumentsAndMusicToo() {
        Platform windows = new Platform(Path.of("C:\\Users\\dj"), Map.of("LOCALAPPDATA", "C:\\Users\\dj\\AppData\\Local",
                "OneDrive", "C:\\Users\\dj\\OneDrive"), true);
        assertTrue(windows.seratoSessionFolders().contains(Path.of("C:\\Users\\dj\\OneDrive", "Music", "_Serato_", "History", "Sessions")));
        assertTrue(windows.seratoSessionFolders().contains(Path.of("C:\\Users\\dj", "Music", "_Serato_", "History", "Sessions")));
        assertTrue(windows.virtualDjFolders().contains(Path.of("C:\\Users\\dj\\OneDrive", "Documents", "VirtualDJ", "History")));
        assertEquals(1, new Platform(Path.of("/Users/dj"), Map.of("OneDrive", "/x"), false).seratoSessionFolders().size(),
                "not on a Mac");
    }

    /**
     * Windows' own answer where the Music and the Documents are (OneDrive's backup, a folder named in Polish, a user name with a Polish
     * letter) and VirtualDJ's HomeFolder — PowerShell's lines in UTF-8, its byte order mark first: those folders are looked in too.
     */
    @Test
    void windows_theKnownFoldersOfWindowsItself_areLookedInToo() {
        Map<String, Path> known = Platform.knownFolders("\uFEFFmusic=C:\\Users\\Michał\\OneDrive\\Muzyka\r\n"
                + "documents=C:\\Users\\Michał\\OneDrive\\Dokumenty\r\nvirtualdj=\r\nnonsense\r\n");
        assertEquals(Map.of("music", Path.of("C:\\Users\\Michał\\OneDrive\\Muzyka"),
                "documents", Path.of("C:\\Users\\Michał\\OneDrive\\Dokumenty")), known, "an empty value is no folder");

        Platform windows = new Platform(Path.of("C:\\Users\\Michał"), Map.of(), true, known);
        assertTrue(windows.seratoSessionFolders().contains(
                Path.of("C:\\Users\\Michał\\OneDrive\\Muzyka", "_Serato_", "History", "Sessions")));
        assertTrue(windows.virtualDjFolders().contains(
                Path.of("C:\\Users\\Michał\\OneDrive\\Dokumenty", "VirtualDJ", "History")));
        assertEquals(Path.of("C:\\dj", "History"), new Platform(Path.of("C:\\Users\\x"), Map.of(), true,
                Platform.knownFolders("virtualdj=C:\\dj\n")).virtualDjFolders().getFirst(), "VirtualDJ's own setting first");
        Platform plain = new Platform(Path.of("C:\\Users\\dj"), Map.of(), true, Platform.knownFolders("music=C:\\Users\\dj\\Music"));
        assertEquals(plain.seratoSessionFolders().size(), plain.seratoSessionFolders().stream().distinct().count(), "each folder once");
    }

    @Test
    void windows_theFoldersAsADjsLaptopHasThem() {
        Platform windows = new Platform(Path.of("C:\\Users\\dj"), Map.of("LOCALAPPDATA", "C:\\Users\\dj\\AppData\\Local",
                "APPDATA", "C:\\Users\\dj\\AppData\\Roaming"), true);
        assertTrue(windows.seratoSessionFolders().getFirst().endsWith(Path.of("Music", "_Serato_", "History", "Sessions")));
        assertEquals(Path.of("C:\\Users\\dj\\Documents\\VirtualDJ"), Platform.homeFolderOf(
                "\r\nHKEY_CURRENT_USER\\Software\\VirtualDJ\r\n    HomeFolder    REG_SZ    C:\\Users\\dj\\Documents\\VirtualDJ\r\n").orElseThrow());
    }

    /**
     * Serato DJ Lite 5.1 on the owner's Windows 11 keeps master.sqlite under %LOCALAPPDATA%, not %APPDATA% as the readers of Serato 4
     * say: both are looked in, and of two the one Serato wrote last is read (an old install's database must not hide tonight's).
     */
    @Test
    void windows_serato_theDatabaseUnderLocalAppData_andTheNewestOfTwo(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("Local");
        Path roaming = dir.resolve("Roaming");
        Platform windows = new Platform(dir, Map.of("LOCALAPPDATA", local.toString(), "APPDATA", roaming.toString()), true);
        Path tonight = local.resolve("Serato").resolve("Library").resolve("master.sqlite");
        assertTrue(windows.serato4Databases().contains(tonight), "Serato 5's place");

        Path old = roaming.resolve("Serato").resolve("Library").resolve("master.sqlite");
        Files.createDirectories(old.getParent());
        Files.createDirectories(tonight.getParent());
        Files.writeString(old, "old");
        Files.writeString(tonight, "new");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond(NIGHT - 86400 * 30)));
        Files.setLastModifiedTime(tonight, java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond(NIGHT)));
        Serato4Database serato = new Serato4Database(windows.serato4Databases());
        serato.version();
        assertEquals(tonight, serato.current(), "the database written last");
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

    @Test
    void theCall_carriesTheComputersClock_soTheServerCanCorrectIt() {
        String json = Json.plays(List.of(new Played(Instant.ofEpochSecond(1795300000L), "Sanah", "Szampan")),
                Instant.ofEpochSecond(1795300100L));

        assertEquals("{\"plays\":[{\"at\":1795300000,\"artist\":\"Sanah\",\"title\":\"Szampan\"}],\"sentAt\":1795300100}", json);
    }
}
