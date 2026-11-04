package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.model.LibraryMark;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DJ's library (V41, V42, V57) on a real PostgreSQL: an upload replaces the whole library, two at the same moment end as one of
 * them (never a mix, never an error), each song is kept as the file writes it beside its key, the queue asks for its few keys at once
 * and gets the tempo and the key of the DJ's tracks, and the library goes with the party.
 */
class DjLibraryIT extends PostgresIntegrationTest {

    @Autowired DjLibraryService libraries;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private String party() {
        String code = "L" + ThreadLocalRandom.current().nextInt(1000, 10000);
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("lib-" + code).active(true).build());
        return code;
    }

    private static Library library(String... songs) {
        Set<LibraryKey> keys = new HashSet<>();
        for (String song : songs) {
            keys.add(LibraryKey.of(song));
        }
        return new Library(Format.M3U, songs.length, keys, null);
    }

    private static LibraryKey key(String song) {
        return LibraryKey.of(song);
    }

    private int rows(String partyCode) {
        return jdbc.queryForObject("SELECT count(*) FROM dj_library_track WHERE party_code = ?", Integer.class, partyCode);
    }

    @Test
    void anUpload_replacesTheLibrary_andTheQueueFindsItsSongs() {
        String code = party();
        libraries.replace(code, library("Wilki - Baśka", "ABBA - Dancing Queen"));
        libraries.replace(code, library("Daft Punk - One More Time", "Queen - Don't Stop Me Now", "Dżem - Sen o Victorii"));

        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.tracks()).isEqualTo(3));
        assertThat(rows(code)).isEqualTo(3);
        assertThat(libraries.marks(code, List.of(key("Daft Punk - One More Time (Radio Edit)"), key("Wilki - Baśka"),
                key("Dzem - Sen o Victorii"), key("Lady Pank - Sen o Victorii"))))
                .containsEntry(key("Daft Punk - One More Time (Radio Edit)"), LibraryMark.HAVE)
                .containsEntry(key("Wilki - Baśka"), LibraryMark.MISSING)
                .containsEntry(key("Dzem - Sen o Victorii"), LibraryMark.HAVE)
                .containsEntry(key("Lady Pank - Sen o Victorii"), LibraryMark.SIMILAR);
        assertThat(libraries.marks(code, List.of())).isEmpty();
    }

    @Test
    void aBigLibrary_isSavedWhole() {
        String code = party();
        Set<LibraryKey> keys = new HashSet<>();
        for (int i = 0; i < 12_345; i++) {
            keys.add(LibraryKey.of("Artist " + i, "Title " + i));
        }
        libraries.replace(code, new Library(Format.REKORDBOX, keys.size(), keys, "Kluby / Piątek"));

        assertThat(rows(code)).isEqualTo(12_345);
        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.playlist()).isEqualTo("Kluby / Piątek"));
        assertThat(libraries.marks(code, List.of(key("Artist 12344 - Title 12344"), key("Nobody - Nothing there"))).values())
                .containsExactlyInAnyOrder(LibraryMark.HAVE, LibraryMark.MISSING);
    }

    /** Two uploads at once (two windows, a double click): one of them stands whole, the other waited for it under the party's lock. */
    @Test
    void twoUploadsAtOnce_endAsOneOfThem() throws Exception {
        String code = party();
        Library first = library("A - One", "A - Two");
        Library second = library("B - One", "B - Two", "B - Three");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> uploads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Library library = i % 2 == 0 ? first : second;
                uploads.add(pool.submit(() -> libraries.replace(code, library)));
            }
            for (Future<?> upload : uploads) {
                upload.get();
            }
        } finally {
            pool.shutdown();
        }
        int tracks = libraries.info(code).orElseThrow().tracks();
        assertThat(rows(code)).isEqualTo(tracks);
        assertThat(libraries.marks(code, (tracks == 2 ? first : second).keys()).values()).containsOnly(LibraryMark.HAVE);
    }

    /** An M3U's names read the other way round (V43): saved apart, "✓" by their title and artist, never "?" by the title alone. */
    @Test
    void theSwappedKeys_sayHave_neverSimilar() {
        String code = party();
        libraries.replace(code, new Library(Format.M3U, 1, Set.of(key("Perfect - Autobiografia")), Set.of(key("Autobiografia - Perfect")), null));

        assertThat(rows(code)).isEqualTo(2);
        assertThat(libraries.marks(code, List.of(key("Ed Sheeran - Perfect"), key("Autobiografia - Perfect"), key("Perfect - Autobiografia"))))
                .containsEntry(key("Ed Sheeran - Perfect"), LibraryMark.MISSING)
                .containsEntry(key("Autobiografia - Perfect"), LibraryMark.HAVE)
                .containsEntry(key("Perfect - Autobiografia"), LibraryMark.HAVE);
    }

    /**
     * A rekordbox collection read and saved (V57): each song with its artist and title as written — the shortest of its versions —,
     * its tempo, key, genre and year; a track never tagged ("Wilki - Baśka" for its name) by its name both ways round. The queue's
     * "✓" and "?" get the tempo and the key of the track they were found as; "✗" none.
     */
    @Test
    void aCollection_keepsItsTracksAsWritten_andTheQueueGetsTheirTempoAndKey() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DJ_PLAYLISTS Version="1.0.0"><COLLECTION Entries="4">
                  <TRACK TrackID="1" Name="One More Time (Extended Mix)" Artist="Daft Punk" AverageBpm="122.00" Tonality="5A" Genre="House" Year="2000"/>
                  <TRACK TrackID="2" Name="One More Time" Artist="Daft Punk" AverageBpm="122.50" Tonality="5A" Genre="House" Year="2000"/>
                  <TRACK TrackID="3" Name="Wilki - Baśka" Artist="" AverageBpm="128.00" Tonality="8B" Year="0"/>
                  <TRACK TrackID="4" Name="Sen o Victorii" Artist="Dżem" AverageBpm="0.00" Tonality="" Genre=""/>
                </COLLECTION></DJ_PLAYLISTS>
                """;
        Library collection = DjLibraryParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), 100, null);
        String code = party();
        libraries.replace(code, collection);

        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.withDetails()).isTrue());
        assertThat(jdbc.queryForMap("SELECT artist_name, title_name, bpm, tonality, genre, year FROM dj_library_track"
                + " WHERE party_code = ? AND title_key = 'onemoretime'", code))
                .containsEntry("artist_name", "Daft Punk").containsEntry("title_name", "One More Time")
                .containsEntry("bpm", new BigDecimal("122.50")).containsEntry("tonality", "5A").containsEntry("genre", "House")
                .containsEntry("year", 2000);
        assertThat(jdbc.queryForMap("SELECT artist_name, title_name, bpm, tonality, genre, year FROM dj_library_track"
                + " WHERE party_code = ? AND title_key = 'senovictorii'", code))
                .as("what the file does not say is null: a tempo of 0, an empty key and genre")
                .containsEntry("artist_name", "Dżem").containsEntry("bpm", null).containsEntry("tonality", null)
                .containsEntry("genre", null).containsEntry("year", null);
        assertThat(jdbc.queryForList("SELECT artist_name || ' / ' || title_name || ' / ' || swapped FROM dj_library_track"
                + " WHERE party_code = ? AND bpm = 128", String.class, code))
                .as("an untagged track by its name, both ways round").containsExactlyInAnyOrder("Wilki / Baśka / false", "Baśka / Wilki / true");

        var matches = libraries.matches(code, List.of(key("Daft Punk - One More Time"), key("Lady Pank - Sen o Victorii"),
                key("Wilki - Baśka"), key("ABBA - SOS")));
        assertThat(matches.get(key("Daft Punk - One More Time"))).isEqualTo(new DjLibraryService.Match(LibraryMark.HAVE, new BigDecimal("122.50"), "5A"));
        assertThat(matches.get(key("Lady Pank - Sen o Victorii"))).isEqualTo(new DjLibraryService.Match(LibraryMark.SIMILAR, null, null));
        assertThat(matches.get(key("Wilki - Baśka"))).isEqualTo(new DjLibraryService.Match(LibraryMark.HAVE, new BigDecimal("128.00"), "8B"));
        assertThat(matches.get(key("ABBA - SOS"))).isEqualTo(new DjLibraryService.Match(LibraryMark.MISSING, null, null));
        assertThat(libraries.marks(code, List.of(key("Wilki - Baśka"))))
                .as("the marks alone, as the summary asks for them").containsEntry(key("Wilki - Baśka"), LibraryMark.HAVE);
    }

    /** A library uploaded before V57 (its keys alone): its marks as before, no tempo nor key, and it says it has no details. */
    @Test
    void aLibraryOfBefore_marksAsItDid() {
        String code = party();
        jdbc.update("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES (?, 1, 'REKORDBOX', now())", code);
        jdbc.update("INSERT INTO dj_library_track (party_code, title_key, artists) VALUES (?, 'baska', 'wilki')", code);

        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.withDetails()).isFalse());
        assertThat(libraries.matches(code, List.of(key("Wilki - Baśka"))))
                .containsEntry(key("Wilki - Baśka"), new DjLibraryService.Match(LibraryMark.HAVE, null, null));
    }

    /** A playlist's name is only shown: one longer than its column is cut, never an error of the upload. */
    @Test
    void aLongPlaylistName_isCut() {
        String code = party();
        String name = "Wesela / ".repeat(30) + "Kowalscy";
        libraries.replace(code, new Library(Format.REKORDBOX, 1, Set.of(key("Wilki - Baśka")), name));
        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.playlist()).isEqualTo(name.substring(0, 200)));
    }

    /** The panel's poll reads the library from memory (DjLibraryService.CACHE): an upload and a deletion are seen at once all the same. */
    @Test
    void theCachedLibrary_followsAnUploadAndADeletion() {
        String code = party();
        assertThat(libraries.info(code)).isEmpty();
        libraries.replace(code, library("Wilki - Baśka"));
        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.tracks()).isEqualTo(1));
        libraries.replace(code, library("Wilki - Baśka", "ABBA - SOS"));
        assertThat(libraries.info(code)).hasValueSatisfying(info -> assertThat(info.tracks()).isEqualTo(2));
        libraries.delete(code);
        assertThat(libraries.info(code)).isEmpty();
    }

    @Test
    void theLibrary_goesWhenDeleted_andWithTheParty() {
        String code = party();
        libraries.replace(code, library("Wilki - Baśka"));
        libraries.delete(code);
        assertThat(libraries.info(code)).isEmpty();
        assertThat(rows(code)).isZero();

        libraries.replace(code, library("Wilki - Baśka"));
        parties.delete(parties.findByPartyCode(code).orElseThrow());
        assertThat(rows(code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dj_library WHERE party_code = ?", Integer.class, code)).isZero();
    }
}
