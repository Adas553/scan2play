package com.scan2play.service;

import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryParser.UnreadableLibraryException;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DJ's library as the programs export it: a rekordbox collection (XML) — whole, or one of its playlists — or a playlist
 * (M3U / M3U8). Only the songs' keys are kept ({@link LibraryKey}) — never the paths, which name the DJ's computer and account.
 */
class DjLibraryParserTest {

    private static Library parse(String fixture) throws IOException {
        try (InputStream in = new ClassPathResource("library/" + fixture).getInputStream()) {
            return DjLibraryParser.parse(in, 1000, null);
        }
    }

    private static Library parsePlaylist(String fixture, String playlist) throws IOException {
        try (InputStream in = new ClassPathResource("library/" + fixture).getInputStream()) {
            return DjLibraryParser.parse(in, 1000, playlist);
        }
    }

    private static Library parse(String text, int maxTracks) throws IOException {
        return DjLibraryParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), maxTracks, null);
    }

    private static LibraryKey key(String song) {
        return LibraryKey.of(song);
    }

    /** No key holds a part of a path: the computer's user, the folders, the file's type. */
    private static void noPaths(Library library) {
        assertThat(library.keys()).noneMatch(k -> (k.title() + k.artists()).matches(".*(users|music|mp3|flac).*"));
    }

    @Test
    void aRekordboxCollection_isItsTracks_notItsPlaylistsAgain() throws IOException {
        Library library = parse("rekordbox.xml");

        assertThat(library.format()).isEqualTo(Format.REKORDBOX);
        assertThat(library.tracks()).as("the collection's 12, the playlists' references not again").isEqualTo(12);
        assertThat(library.playlist()).isNull();
        assertThat(library.keys()).contains(key("Daft Punk - One More Time"), key("Wilki - Baśka"), key("Golec uOrkiestra - Ściernisco"),
                key("Mark Ronson - Uptown Funk"), key("Calvin Harris feat. Rihanna - This Is What You Came For"), key("Intro"));
        noPaths(library);
    }

    @Test
    void anM3uPlaylist_isItsTitles_orItsFileNames() throws IOException {
        Library library = parse("playlist.m3u8");

        assertThat(library.format()).isEqualTo(Format.M3U);
        assertThat(library.tracks()).isEqualTo(6);
        assertThat(library.keys()).contains(key("Daft Punk - One More Time"), key("Wilki - Baśka"), key("ABBA - Dancing Queen"),
                key("Akcent - Przez Twe Oczy Zielone"), key("Dżem - Sen o Victorii"), key("Dua Lipa - Levitating"));
        assertThat(library.keys()).as("the title, not the file of #EXTINF's track").doesNotContain(key("01 Wilki"));
        noPaths(library);
    }

    @Test
    void anM3uLine_mayNameTheTitleFirst() throws IOException {
        Library library = parse("#EXTM3U\n#EXTINF:200,One More Time - Daft Punk\nC:\\a.mp3\n", 10);

        assertThat(library.keys()).contains(key("Daft Punk - One More Time"));
    }

    /** An old Windows playlist (Winamp, VirtualDJ) is in Windows-1250, not UTF-8: its Polish letters are read all the same. */
    @Test
    void anM3uInWindows1250_isReadToo() throws IOException {
        byte[] file = "#EXTM3U\r\n#EXTINF:245,Wilki - Baśka\r\nC:\\Muzyka\\wilki.mp3\r\nC:\\Muzyka\\Dżem - Sen o Victorii.mp3\r\n"
                .getBytes(java.nio.charset.Charset.forName("windows-1250"));

        Library library = DjLibraryParser.parse(new ByteArrayInputStream(file), 10, null);

        assertThat(library.keys()).contains(key("Wilki - Baśka"), key("Dżem - Sen o Victorii"));
    }

    @Test
    void tooManyTracks_isRefused() {
        assertThatThrownBy(() -> parse("#EXTM3U\nA - 1.mp3\nA - 2.mp3\nA - 3.mp3\n", 2))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.TOO_MANY));
    }

    @Test
    void anotherFile_isNotALibrary() {
        assertThatThrownBy(() -> parse("<html><body>hi</body></html>", 10))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.UNKNOWN_FORMAT));
        assertThatThrownBy(() -> parse("<DJ_PLAYLISTS><COLLECTION><TRACK Name=", 10))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.UNKNOWN_FORMAT));
        byte[] song = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 'I', 'D', '3', 0x03, '\n', 'x'};
        assertThatThrownBy(() -> DjLibraryParser.parse(new ByteArrayInputStream(song), 10, null))
                .as("a song file, not a playlist")
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.UNKNOWN_FORMAT));
        assertThatThrownBy(() -> parse("#EXTM3U\n\n", 10))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.EMPTY));
    }

    /** An XML file is the DJ's, not ours: its DTD and entities are never read (XXE) — no file of the server ends up a "song". */
    @Test
    void anXmlEntity_isNeverRead() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE DJ_PLAYLISTS [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <DJ_PLAYLISTS><COLLECTION><TRACK Name="&secret;" Artist="x"/></COLLECTION></DJ_PLAYLISTS>
                """;
        assertThatThrownBy(() -> parse(xxe, 10)).isInstanceOf(UnreadableLibraryException.class);
    }

    // ---- one playlist of a rekordbox collection ----

    @Test
    void onePlaylist_isItsTracks_byTheCollection() throws IOException {
        Library wedding = parsePlaylist("rekordbox.xml", "Wesele");

        assertThat(wedding.tracks()).isEqualTo(3);
        assertThat(wedding.playlist()).isEqualTo("Wesele");
        assertThat(wedding.keys()).containsExactlyInAnyOrder(key("Wilki - Baśka"), key("ABBA - Dancing Queen"),
                key("Akcent - Przez Twe Oczy Zielone"));
        noPaths(wedding);
    }

    /** A playlist in a folder is named by its folders, as rekordbox shows the tree: "Kluby / Piątek". */
    @Test
    void aPlaylistInAFolder_isNamedByItsPath() throws IOException {
        assertThat(parsePlaylist("rekordbox.xml", "Kluby / Piątek").keys())
                .containsExactlyInAnyOrder(key("Daft Punk - One More Time"), key("Dua Lipa - Levitating"));
    }

    @Test
    void aPlaylistNotThere_orEmpty_isSaidSo() {
        assertThatThrownBy(() -> parsePlaylist("rekordbox.xml", "Sylwester"))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.NO_PLAYLIST));
        assertThatThrownBy(() -> parsePlaylist("rekordbox.xml", "Kluby / Pusta"))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.EMPTY));
    }

    /** The most tracks count what is kept: a large collection, one playlist of it chosen, is read. */
    @Test
    void theMostTracks_countThePlaylist_notTheCollection() throws IOException {
        try (InputStream in = new ClassPathResource("library/rekordbox.xml").getInputStream()) {
            assertThat(DjLibraryParser.parse(in, 3, "Wesele").tracks()).isEqualTo(3);
        }
        try (InputStream in = new ClassPathResource("library/rekordbox.xml").getInputStream()) {
            assertThatThrownBy(() -> DjLibraryParser.parse(in, 3, null)).isInstanceOfSatisfying(UnreadableLibraryException.class,
                    e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.TOO_MANY));
        }
    }

    @Test
    void anM3u_hasNoPlaylistToChoose() throws IOException {
        try (InputStream in = new ClassPathResource("library/playlist.m3u8").getInputStream()) {
            Library library = DjLibraryParser.parse(in, 1000, "Wesele");
            assertThat(library.tracks()).isEqualTo(6);
            assertThat(library.playlist()).isNull();
        }
    }
}
