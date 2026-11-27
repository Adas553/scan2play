package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.scan2play.service.DeckWatchService.State.OFFLINE;
import static com.scan2play.service.DeckWatchService.State.OK;
import static com.scan2play.service.DeckWatchService.State.SILENT;
import static com.scan2play.service.DeckWatchService.State.WAITING;
import static org.assertj.core.api.Assertions.assertThat;

/** A computer's line in the panel (V81): gone, waiting for its first song, playing, or silent past the minutes chosen. */
class DeckWatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T21:00:00Z");

    private static DjDeviceService.Device device(Duration seenAgo, Duration playedAgo) {
        return new DjDeviceService.Device(1, "PC", NOW.minus(Duration.ofDays(1)), seenAgo == null ? null : NOW.minus(seenAgo),
                playedAgo == null ? null : NOW.minus(playedAgo), "VirtualDJ");
    }

    @Test
    void theStates() {
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), null), 20, NOW)).as("paired, nothing played: the test")
                .isEqualTo(WAITING);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofHours(13)), 20, NOW))
                .as("yesterday's set is not tonight's").isEqualTo(WAITING);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(19)), 20, NOW)).isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(21)), 20, NOW)).isEqualTo(SILENT);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(21)), 30, NOW)).isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(90)), 0, NOW)).as("never warn").isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofMinutes(4), Duration.ofMinutes(5)), 20, NOW))
                .as("three calls missed: gone, whatever it played").isEqualTo(OFFLINE);
        assertThat(DeckWatchService.state(device(null, null), 20, NOW)).as("never called since its pairing a day ago").isEqualTo(OFFLINE);
    }

    @Test
    void theChoicesOffered_includeTheDefault() {
        assertThat(DeckWatchService.SILENCE_CHOICES).containsExactlyInAnyOrder(0, 10, 20, 30)
                .contains(DeckWatchService.DEFAULT_SILENCE_MINUTES);
    }
}
