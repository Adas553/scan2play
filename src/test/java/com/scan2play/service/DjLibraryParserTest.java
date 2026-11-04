package com.scan2play.service;

import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryParser.Track;
import com.scan2play.service.DjLibraryParser.UnreadableLibraryException;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DJ's library as the programs export it: a rekordbox collection (XML) — whole, or one of its playlists — or a playlist
 * (M3U / M3U8). Each song's key ({@link LibraryKey}) and its track as the file writes it ({@link Track}: the artist, the title, the
 * tempo, the key, the genre, the year — V57) — never the paths, which name the DJ's computer and account.
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

        assertThat(library.swapped()).contains(key("Daft Punk - One More Time"));
        assertThat(key("Daft Punk - One More Time").markIn(library.keys(), library.swapped()))
                .isEqualTo(com.scan2play.model.LibraryMark.HAVE);
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

    /**
     * The collection is held in memory until its playlists say which tracks to keep, so it has a limit of its own: 64 MB of tiny
     * tracks would be half a gigabyte of the server's memory, and the server stops when its memory runs out.
     */
    @Test
    void aCollectionTooLarge_withAPlaylist_isRefused() throws IOException {
        try (InputStream in = new ClassPathResource("library/rekordbox.xml").getInputStream()) {
            assertThatThrownBy(() -> DjLibraryParser.parse(in, 1000, 11, "Wesele", 1)).isInstanceOfSatisfying(UnreadableLibraryException.class,
                    e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.COLLECTION_TOO_LARGE));
        }
        try (InputStream in = new ClassPathResource("library/rekordbox.xml").getInputStream()) {
            assertThat(DjLibraryParser.parse(in, 1000, 12, "Wesele", 1).tracks()).as("its 12 are allowed").isEqualTo(3);
        }
        try (InputStream in = new ClassPathResource("library/rekordbox.xml").getInputStream()) {
            assertThat(DjLibraryParser.parse(in, 1000, 1, null, 1).tracks()).as("the whole collection is counted by its tracks")
                    .isEqualTo(12);
        }
    }

    /** A playlist's tree deeper than any DJ's: refused, not walked (each playlist is named by all its folders). */
    @Test
    void aTreeTooDeep_isRefused() throws IOException {
        String folders = "<NODE Type=\"0\" Name=\"f\">";
        String file = "<DJ_PLAYLISTS><COLLECTION><TRACK TrackID=\"1\" Name=\"Baśka\" Artist=\"Wilki\"/></COLLECTION><PLAYLISTS>"
                + folders.repeat(DjLibraryParser.MAX_DEPTH) + "<NODE Type=\"1\" Name=\"x\"><TRACK Key=\"1\"/></NODE>"
                + "</NODE>".repeat(DjLibraryParser.MAX_DEPTH) + "</PLAYLISTS></DJ_PLAYLISTS>";
        assertThatThrownBy(() -> DjLibraryParser.parse(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), 10, "f / x"))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.UNKNOWN_FORMAT));
        String shallower = file.replaceFirst(java.util.regex.Pattern.quote(folders), "").replaceFirst("</NODE>", "");
        String path = String.join(" / ", java.util.Collections.nCopies(DjLibraryParser.MAX_DEPTH - 1, "f")) + " / x";
        assertThat(DjLibraryParser.parse(new ByteArrayInputStream(shallower.getBytes(StandardCharsets.UTF_8)), 10, path).tracks())
                .as("as deep as allowed: read").isEqualTo(1);
    }

    /** Two playlists of one name in one folder (rekordbox allows it): the occurrence asked for, not always the first. */
    @Test
    void theSecondPlaylistOfAName_isRead() throws IOException {
        String file = "<DJ_PLAYLISTS><COLLECTION><TRACK TrackID=\"1\" Name=\"Baśka\" Artist=\"Wilki\"/>"
                + "<TRACK TrackID=\"2\" Name=\"SOS\" Artist=\"ABBA\"/><TRACK TrackID=\"x\" Name=\"Sen\" Artist=\"Dżem\"/></COLLECTION>"
                + "<PLAYLISTS><NODE Type=\"0\" Name=\"ROOT\"><NODE Name=\"Wesele\" Type=\"1\"><TRACK Key=\"1\"/></NODE>"
                + "<NODE Name=\"Wesele\" Type=\"1\"><TRACK Key=\"2\"/><TRACK Key=\"x\"/></NODE></NODE></PLAYLISTS></DJ_PLAYLISTS>";
        byte[] bytes = file.getBytes(StandardCharsets.UTF_8);
        assertThat(DjLibraryParser.parse(new ByteArrayInputStream(bytes), 10, "Wesele").keys()).containsExactly(key("Wilki - Baśka"));
        assertThat(DjLibraryParser.parse(new ByteArrayInputStream(bytes), 10, DjLibraryParser.MAX_COLLECTION, "Wesele", 2).keys())
                .as("the second; a track whose number is none is no playlist's").containsExactly(key("ABBA - SOS"));
        assertThatThrownBy(() -> DjLibraryParser.parse(new ByteArrayInputStream(bytes), 10, DjLibraryParser.MAX_COLLECTION, "Wesele", 3))
                .isInstanceOfSatisfying(UnreadableLibraryException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnreadableLibraryException.Reason.NO_PLAYLIST));
    }

    /** An M3U's "A - B" is kept the other way round too, apart: read that way an artist's name is the title. */
    @Test
    void anM3uName_theOtherWayRound_isASwappedKey() throws IOException {
        Library library = parse("#EXTM3U\n#EXTINF:300,Perfect - Autobiografia\nC:\\m\\a.mp3\n"
                + "#EXTINF:200,Kombi - Słodkiego miłego życia\nC:\\m\\b.mp3\n#EXTINF:200,Słodkiego miłego życia - Kombi\nC:\\m\\c.mp3\n", 10);
        assertThat(library.keys()).contains(key("Perfect - Autobiografia")).doesNotContain(key("Autobiografia - Perfect"));
        assertThat(library.swapped()).contains(key("Autobiografia - Perfect"))
                .as("a name the playlist has both ways round is a key as it is, not a swapped one")
                .doesNotContain(key("Kombi - Słodkiego miłego życia"), key("Słodkiego miłego życia - Kombi"));
        assertThat(parse("rekordbox.xml").swapped()).as("a collection's artist and title are fields of their own").isEmpty();
    }

    // ---- each song as the file writes it (V57) ----

    private static Library xml(String tracks) throws IOException {
        return parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<DJ_PLAYLISTS Version=\"1.0.0\"><COLLECTION>" + tracks
                + "</COLLECTION></DJ_PLAYLISTS>", 100);
    }

    @Test
    void aCollectionsTrack_keepsItsArtistTitleTempoKeyGenreAndYear() throws IOException {
        assertThat(parse("rekordbox.xml").songs().get(key("Daft Punk - One More Time")))
                .isEqualTo(new Track("Daft Punk", "One More Time (Radio Edit)", new BigDecimal("122.00"), "5A", "House", null));
        assertThat(xml("<TRACK TrackID=\"1\" Name=\"Baśka\" Artist=\"Wilki\" AverageBpm=\"128.5\" Tonality=\"Am\" Genre=\"Rock\" Year=\"1992\""
                + " Comments=\"na koniec wesela\" Rating=\"255\" PlayCount=\"40\" Location=\"file://localhost/C:/Users/dj/w.mp3\"/>")
                .songs().get(key("Wilki - Baśka")))
                .as("its notes, rating, play count and path are never read")
                .isEqualTo(new Track("Wilki", "Baśka", new BigDecimal("128.50"), "Am", "Rock", 1992));
    }

    /** What the file does not say, or says as no number, is null — the track is kept all the same. */
    @Test
    void aTracksMissingOrWrongNumbers_areNone_notARefusal() throws IOException {
        Library library = xml("<TRACK TrackID=\"1\" Name=\"Baśka\" Artist=\"Wilki\" AverageBpm=\"0.00\" Tonality=\"\" Genre=\" \" Year=\"0\"/>"
                + "<TRACK TrackID=\"2\" Name=\"SOS\" Artist=\"ABBA\" AverageBpm=\"fast\" Year=\"1975a\"/>"
                + "<TRACK TrackID=\"3\" Name=\"Kiler\" Artist=\"Elektryczne Gitary\" AverageBpm=\"1200\" Year=\"-5\"/>");

        assertThat(library.songs().values()).hasSize(3).allSatisfy(track -> {
            assertThat(track.bpm()).isNull();
            assertThat(track.tonality()).isNull();
            assertThat(track.genre()).isNull();
            assertThat(track.year()).isNull();
        });
    }

    /** A song in several versions is one key, shown as the shortest title: the plain song, not one of its mixes. */
    @Test
    void aSongsVersions_areOneSong_shownAsTheShortest() throws IOException {
        Library library = xml("<TRACK TrackID=\"1\" Name=\"Levels (Extended Mix)\" Artist=\"Avicii\" AverageBpm=\"126.00\"/>"
                + "<TRACK TrackID=\"2\" Name=\"Levels\" Artist=\"Avicii\" AverageBpm=\"126.00\"/>"
                + "<TRACK TrackID=\"3\" Name=\"Levels - Skrillex Remix\" Artist=\"Avicii\" AverageBpm=\"140.00\"/>");

        assertThat(library.tracks()).isEqualTo(3);
        assertThat(library.songs()).hasSize(1).containsEntry(key("Avicii - Levels"),
                new Track("Avicii", "Levels", new BigDecimal("126.00"), null, null, null));
    }

    /** rekordbox's own field of the mix: added to the title when the title does not say it, so the DJ sees which version it is. */
    @Test
    void aTracksMix_isAddedToItsTitle_once() throws IOException {
        Library library = xml("<TRACK TrackID=\"1\" Name=\"Believe\" Mix=\"Almighty Definitive Mix\" Artist=\"Cher\"/>"
                + "<TRACK TrackID=\"2\" Name=\"Get Lucky (Radio Edit)\" Mix=\"Radio Edit\" Artist=\"Daft Punk\"/>");

        assertThat(library.songs().get(key("Cher - Believe")).title()).isEqualTo("Believe (Almighty Definitive Mix)");
        assertThat(library.songs().get(key("Daft Punk - Get Lucky")).title()).isEqualTo("Get Lucky (Radio Edit)");
    }

    /**
     * A file never tagged: rekordbox names it by the file ("Wilki - Baśka") and has no artist. It was "✗" for every request; read as
     * an M3U's name, both ways round, it is the song it names.
     */
    @Test
    void anUntaggedTrack_isReadByItsName_bothWaysRound() throws IOException {
        Library library = xml("<TRACK TrackID=\"1\" Name=\"Wilki - Baśka\" Artist=\"\" AverageBpm=\"128.00\"/>"
                + "<TRACK TrackID=\"2\" Name=\"Sen o Victorii - Dżem\"/>");

        assertThat(library.songs().get(key("Wilki - Baśka")))
                .isEqualTo(new Track("Wilki", "Baśka", new BigDecimal("128.00"), null, null, null));
        assertThat(key("Wilki - Baśka").markIn(library.keys(), library.swapped())).isEqualTo(com.scan2play.model.LibraryMark.HAVE);
        assertThat(key("Dżem - Sen o Victorii").markIn(library.keys(), library.swapped()))
                .as("the title first: the swapped key").isEqualTo(com.scan2play.model.LibraryMark.HAVE);
        assertThat(library.swapped()).contains(key("Dżem - Sen o Victorii"));
    }

    /** One playlist of a collection keeps its tracks' details too. */
    @Test
    void aPlaylistsTracks_keepTheirDetails() throws IOException {
        assertThat(parsePlaylist("rekordbox.xml", "Wesele").songs().get(key("Wilki - Baśka")))
                .isEqualTo(new Track("Wilki", "Baśka", new BigDecimal("128.00"), null, "Rock", null));
    }

    @Test
    void anM3usName_isShownAsArtistAndTitle_itsSwappedKeyTheOtherWayRound() throws IOException {
        Library library = parse("#EXTM3U\n#EXTINF:200,Perfect - Autobiografia\nC:\\a.mp3\nC:\\m\\Intro.mp3\n", 10);

        assertThat(library.songs()).containsEntry(key("Perfect - Autobiografia"), new Track("Perfect", "Autobiografia", null, null, null, null))
                .containsEntry(key("Intro"), new Track(null, "Intro", null, null, null, null));
        assertThat(library.swappedSongs()).containsEntry(key("Autobiografia - Perfect"),
                new Track("Autobiografia", "Perfect", null, null, null, null));
    }

    /**
     * A file's name as M3U playlists of file names write them: a track's number before it was taken for the artist ("01"), underscores
     * kept the dash from parting artist and title — both made every request "✗". A number followed by a space alone is a name's.
     */
    @Test
    void anM3usFileName_losesItsTrackNumberAndUnderscores() throws IOException {
        assertThat(DjLibraryParser.fileName("C:\\Muzyka\\01 - Wilki - Baśka.mp3")).isEqualTo("Wilki - Baśka");
        assertThat(DjLibraryParser.fileName("/m/07. Dżem - Sen o Victorii.flac")).isEqualTo("Dżem - Sen o Victorii");
        assertThat(DjLibraryParser.fileName("/m/12-ABBA - SOS.mp3")).isEqualTo("ABBA - SOS");
        assertThat(DjLibraryParser.fileName("/m/Wilki_-_Baska.mp3")).isEqualTo("Wilki - Baska");
        assertThat(DjLibraryParser.fileName("/m/50 Cent - In da Club.mp3")).isEqualTo("50 Cent - In da Club");
        assertThat(DjLibraryParser.fileName("/m/4 Non Blondes - What's Up.mp3")).isEqualTo("4 Non Blondes - What's Up");
        assertThat(DjLibraryParser.fileName("/m/1999.mp3")).as("a number alone: no artist and title after it").isEqualTo("1999");

        Library library = parse("#EXTM3U\nC:\\Muzyka\\01 - Wilki - Baśka.mp3\nC:\\Muzyka\\Golec_uOrkiestra_-_Sciernisco.mp3\n", 10);
        assertThat(key("Wilki - Baśka").markIn(library.keys(), library.swapped())).isEqualTo(com.scan2play.model.LibraryMark.HAVE);
        assertThat(key("Golec uOrkiestra - Ściernisco").markIn(library.keys(), library.swapped()))
                .isEqualTo(com.scan2play.model.LibraryMark.HAVE);
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
