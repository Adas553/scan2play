package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.scan2play.service.DeckWatchService.State.OFFLINE;
import static com.scan2play.service.DeckWatchService.State.OK;
import static com.scan2play.service.DeckWatchService.State.SILENT;
import static com.scan2play.service.DeckWatchService.State.WAITING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A computer's line in the panel (V81): gone, waiting for its first song, playing, or silent past the minutes chosen; the party's lead
 * computer and whether its songs were a set or a test (V82).
 */
class DeckWatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T21:00:00Z");

    private static DjDeviceService.Device device(Duration seenAgo, Duration playedAgo) {
        return device(1, seenAgo, playedAgo, playedAgo);
    }

    private static DjDeviceService.Device device(long id, Duration seenAgo, Duration playedAgo, Duration runFromAgo) {
        return new DjDeviceService.Device(id, "PC", NOW.minus(Duration.ofDays(1)), seenAgo == null ? null : NOW.minus(seenAgo),
                playedAgo == null ? null : NOW.minus(playedAgo), runFromAgo == null ? null : NOW.minus(runFromAgo), "VirtualDJ");
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
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(90)), 0, NOW))
                .as("\"nigdy\": no warning, but no \"widzi piosenki\" for a song of 90 min ago either").isEqualTo(SILENT);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(19)), 0, NOW))
                .as("\"nigdy\": said by the default's minutes").isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofMinutes(4), Duration.ofMinutes(5)), 20, NOW))
                .as("three calls missed: gone, whatever it played").isEqualTo(OFFLINE);
        assertThat(DeckWatchService.state(device(null, null), 20, NOW)).as("never called since its pairing a day ago").isEqualTo(OFFLINE);
    }

    /** The edges: silent only past the minutes, gone only past three minutes, tonight's set only within the twelve hours. */
    @Test
    void theEdges() {
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(20)), 20, NOW)).as("exactly 20 min")
                .isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofMinutes(20).plusSeconds(1)), 20, NOW))
                .isEqualTo(SILENT);
        assertThat(DeckWatchService.state(device(Duration.ofMinutes(3), Duration.ofMinutes(5)), 20, NOW)).as("exactly 3 min")
                .isEqualTo(OK);
        assertThat(DeckWatchService.state(device(Duration.ofMinutes(3).plusSeconds(1), Duration.ofMinutes(5)), 20, NOW))
                .isEqualTo(OFFLINE);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofHours(12)), 0, NOW)).as("exactly 12 h")
                .isEqualTo(WAITING);
        assertThat(DeckWatchService.state(device(Duration.ofSeconds(30), Duration.ofHours(12).minusSeconds(1)), 0, NOW))
                .as("tonight's song, said as the last one").isEqualTo(SILENT);
    }

    /** The lead computer is the one with tonight's newest song: a spare laptop tested at 15:00 is not it. */
    @Test
    void theLead_isTheComputerWithTheNewestSong() {
        DjDeviceService.Device spare = device(1, Duration.ofSeconds(30), Duration.ofMinutes(300), Duration.ofMinutes(300));
        DjDeviceService.Device main = device(2, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(60));
        DjDeviceService.Device yesterday = device(3, Duration.ofSeconds(30), Duration.ofHours(13), Duration.ofHours(15));
        assertThat(DeckWatchService.lead(List.of(spare, main, yesterday), NOW)).contains(main);
        assertThat(DeckWatchService.lead(List.of(yesterday), NOW)).as("nothing tonight").isEmpty();
    }

    /**
     * A set: ten minutes of songs with a guest's request in them, or thirty without; one song (or two) played to test the program, or
     * a soundcheck before the guests came, is not.
     */
    @Test
    void aSet_isTenMinutesWithGuests_orThirty_notATestOrASoundcheck() {
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), Duration.ofMinutes(90)), true))
                .as("one song").isFalse();
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), Duration.ofMinutes(99)), true))
                .as("two songs, 9 min, a guest asking").isFalse();
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), Duration.ofMinutes(100)), true))
                .as("exactly 10 min, a guest asking").isTrue();
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), Duration.ofMinutes(115)), false))
                .as("a soundcheck of 25 min, nobody asking").isFalse();
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), Duration.ofMinutes(120)), false))
                .as("exactly 30 min, nobody asking yet").isTrue();
        assertThat(DeckWatchService.setPlayed(device(1, Duration.ofSeconds(30), Duration.ofMinutes(90), null), true))
                .as("no run noted yet (before V82)").isFalse();
    }

    @Test
    void theChoicesOffered_includeTheDefault() {
        assertThat(DeckWatchService.SILENCE_CHOICES).containsExactlyInAnyOrder(0, 10, 20, 30)
                .contains(DeckWatchService.DEFAULT_SILENCE_MINUTES);
    }
}
