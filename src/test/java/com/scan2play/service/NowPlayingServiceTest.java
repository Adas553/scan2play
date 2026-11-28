package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** "🎶 Teraz: …" on the TV and the kiosk: when a song the DJ's program played is "now", and when it stops being. */
class NowPlayingServiceTest {

    private static final Instant START = Instant.parse("2026-11-28T21:30:00Z");
    private static final NowPlayingService.Song SONG = new NowPlayingService.Song("Wilki - Baśka", START);

    private final NowPlayingService service = new NowPlayingService();

    @Test
    void aSongIsNow_fromItsStart_forAFewMinutes() {
        service.heard("AB123", SONG, START);

        assertThat(service.now("AB123", START.plusSeconds(45))).contains(SONG);
        assertThat(service.now("AB123", START.plus(NowPlayingService.SHOWN).minusSeconds(1))).contains(SONG);
        assertThat(service.now("AB123", START.plus(NowPlayingService.SHOWN))).isEmpty();
        assertThat(service.now("CD456", START)).as("another party").isEmpty();
    }

    @Test
    void theNextSong_endsIt_theSameSongSentAgainDoesNot() {
        service.heard("AB123", SONG, START);

        service.heard("AB123", null, START.plusSeconds(2));
        assertThat(service.now("AB123", START.plusSeconds(60))).contains(SONG);
        service.heard("AB123", null, START.plusSeconds(180));
        assertThat(service.now("AB123", START.plusSeconds(190))).isEmpty();
    }

    @Test
    void aRequestWhoseSongIsNotTheNewestOfTheCall_isNotNow() {
        service.heard("AB123", SONG, START.plusSeconds(200));

        assertThat(service.now("AB123", START.plusSeconds(210))).isEmpty();
    }

    @Test
    void anOlderSongMarkedLater_doesNotEndTheNewerOne() {
        NowPlayingService.Song later = new NowPlayingService.Song("Sanah - Szampan", START.plusSeconds(240));
        service.heard("AB123", later, later.startedAt());

        service.heard("AB123", null, START);
        assertThat(service.now("AB123", START.plusSeconds(250))).contains(later);
    }
}
