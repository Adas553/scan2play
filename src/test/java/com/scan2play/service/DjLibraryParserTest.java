package com.scan2play.service;

import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryParser.UnreadableLibraryException;
import com.scan2play.util.SongNames;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DJ's library as the programs export it: a rekordbox collection (XML) or a playlist (M3U / M3U8). Only the songs' keys are
 * kept ({@link SongNames#libraryKey}) — never the paths, which name the DJ's computer and account.
 */
class DjLibraryParserTest {

    private static Library parse(String fixture) throws IOException {
        try (InputStream in = new ClassPathResource("library/" + fixture).getInputStream()) {
            return DjLibraryParser.parse(in, 1000);
        }
    }

    private static Library parse(String text, int maxTracks) throws IOException {
        return DjLibraryParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), maxTracks);
    }

    private static String key(String song) {
        return SongNames.libraryKey(song);
    }

    @Test
    void aRekordboxCollection_isItsTracks_notItsPlaylistsAgain() throws IOException {
        Library library = parse("rekordbox.xml");

        assertThat(library.format()).isEqualTo(Format.REKORDBOX);
        assertThat(library.tracks()).as("the collection's 12, the playlist's references not again").isEqualTo(12);
        assertThat(library.keys()).contains(key("Daft Punk - One More Time"), key("Wilki - Baśka"), key("Golec uOrkiestra - Ściernisco"),
                key("Mark Ronson - Uptown Funk"), key("Calvin Harris - This Is What You Came For"), key("Intro"));
        assertThat(library.keys()).noneMatch(k -> k.contains("users") || k.contains("mp3"));
    }

    @Test
    void anM3uPlaylist_isItsTitles_orItsFileNames() throws IOException {
        Library library = parse("playlist.m3u8");

        assertThat(library.format()).isEqualTo(Format.M3U);
        assertThat(library.tracks()).isEqualTo(6);
        assertThat(library.keys()).contains(key("Daft Punk - One More Time"), key("Wilki - Baśka"), key("ABBA - Dancing Queen"),
                key("Akcent - Przez Twe Oczy Zielone"), key("Dżem - Sen o Victorii"), key("Dua Lipa - Levitating"));
        assertThat(library.keys()).as("the title, not the file of #EXTINF's track").doesNotContain(key("01 Wilki"));
        assertThat(library.keys()).noneMatch(k -> k.contains("users") || k.contains("mp3"));
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

        Library library = DjLibraryParser.parse(new ByteArrayInputStream(file), 10);

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
        assertThatThrownBy(() -> DjLibraryParser.parse(new ByteArrayInputStream(song), 10))
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
}
