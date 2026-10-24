package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.DjLibraryParser.Format;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.util.SongNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * The DJ's library (V41) on a real PostgreSQL: an upload replaces the whole library, two at the same moment end as one of them
 * (never a mix, never an error), the queue asks for its few keys at once, and the library goes with the party.
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
        Set<String> keys = new HashSet<>();
        for (String song : songs) {
            keys.add(SongNames.libraryKey(song));
        }
        return new Library(Format.M3U, songs.length, keys);
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
        assertThat(libraries.found(code, List.of(SongNames.libraryKey("Daft Punk - One More Time (Radio Edit)"),
                SongNames.libraryKey("Wilki - Baśka"), SongNames.libraryKey("Dzem - Sen o Victorii"))))
                .containsExactlyInAnyOrder(SongNames.libraryKey("Daft Punk - One More Time"), SongNames.libraryKey("Dżem - Sen o Victorii"));
        assertThat(libraries.found(code, List.of())).isEmpty();
    }

    @Test
    void aBigLibrary_isSavedWhole() {
        String code = party();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 12_345; i++) {
            keys.add("artist" + i + "title" + i);
        }
        libraries.replace(code, new Library(Format.REKORDBOX, keys.size(), keys));

        assertThat(rows(code)).isEqualTo(12_345);
        assertThat(libraries.found(code, List.of("artist12344title12344", "nothere"))).containsExactly("artist12344title12344");
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
        assertThat(libraries.found(code, (tracks == 2 ? first : second).keys())).hasSize(tracks);
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
