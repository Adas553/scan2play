package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * "Zagrane samo" watched (V81) on a real PostgreSQL: the newest song's time kept and never moved back, "Ostrzeż po" stored, the
 * look's one query — which parties are silent during the party (open, the set started, guests asking, the minutes passed) — and one
 * push a silence.
 */
class DeckWatchIT extends PostgresIntegrationTest {

    @Autowired DjDeviceService devices;
    @Autowired PartySettingsRepository partyRepository;
    @Autowired PartySettingsQueryService parties;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private final PushNotificationService push = mock(PushNotificationService.class);

    private DeckWatchService watchAt(Instant now) {
        return new DeckWatchService(jdbc, devices, parties, plans, push, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String party(boolean active) {
        String code = newPartyCode('W');
        partyRepository.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("watch-" + code).active(active).build());
        return code;
    }

    private long computer(String party, Instant seen) {
        String token = devices.pair(devices.newCode(party), "PC", "it-" + party, seen).token();
        return devices.device(token, seen).orElseThrow().deviceId();
    }

    private void guestAsked(String party, Instant at) {
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, votes, checked, requested_at, request_number)"
                + " VALUES (?, 'Sanah - Szampan', 'accepted', 1, true, ?,"
                + " (SELECT COALESCE(MAX(request_number), 0) + 1 FROM song_requests WHERE party_code = ?))", party, Timestamp.from(at), party);
    }

    private List<String> silent(Instant now, String... among) {
        return watchAt(now).silentNow(now).stream().map(DeckWatchService.Silent::partyCode)
                .filter(List.of(among)::contains).toList();
    }

    @Test
    void theNewestSong_isKept_andNeverMovedBack_theProgramKeptWhenNotSaid() {
        String party = party(true);
        long id = computer(party, NOW);

        devices.notePlay(id, NOW.minusSeconds(60), "VirtualDJ");
        devices.notePlay(id, NOW.minusSeconds(600), "Serato");
        assertThat(devices.devices(party).getFirst().lastPlayAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(devices.devices(party).getFirst().program()).as("an older song moves nothing").isEqualTo("VirtualDJ");

        devices.notePlay(id, NOW, null);
        assertThat(devices.devices(party).getFirst().lastPlayAt()).isEqualTo(NOW);
        assertThat(devices.devices(party).getFirst().program()).as("a program too old to say keeps the last one").isEqualTo("VirtualDJ");
    }

    @Test
    void ostrzezPo_isTwentyByDefault_andTakesOnlyWhatIsOffered() {
        String party = party(true);
        DeckWatchService watch = watchAt(NOW);

        assertThat(watch.silenceMinutes(party)).isEqualTo(20);
        assertThat(watch.setSilenceMinutes(party, 10)).isTrue();
        assertThat(watch.setSilenceMinutes(party, 15)).isFalse();
        assertThat(watch.setSilenceMinutes(party, 0)).isTrue();
        assertThat(watch.silenceMinutes(party)).isZero();
    }

    @Test
    void silent_onlyDuringTheParty_afterTheMinutesChosen() {
        String silentOne = party(true);
        String playing = party(true);
        String closed = party(false);
        String noGuests = party(true);
        String neverPlayed = party(true);
        String watchOff = party(true);
        String yesterday = party(true);
        for (String party : List.of(silentOne, playing, closed, noGuests, neverPlayed, watchOff, yesterday)) {
            long id = computer(party, NOW);
            if (party.equals(playing)) {
                devices.notePlay(id, NOW.minus(Duration.ofMinutes(5)), "VirtualDJ");
            } else if (party.equals(yesterday)) {
                devices.notePlay(id, NOW.minus(Duration.ofHours(13)), "VirtualDJ");
            } else if (!party.equals(neverPlayed)) {
                devices.notePlay(id, NOW.minus(Duration.ofMinutes(25)), "VirtualDJ");
            }
            if (!party.equals(noGuests)) {
                guestAsked(party, NOW.minus(Duration.ofMinutes(15)));
            }
        }
        watchAt(NOW).setSilenceMinutes(watchOff, 0);
        String[] all = {silentOne, playing, closed, noGuests, neverPlayed, watchOff, yesterday};

        assertThat(silent(NOW, all)).containsExactly(silentOne);
        watchAt(NOW).setSilenceMinutes(silentOne, 30);
        assertThat(silent(NOW, all)).as("30 minutes chosen, 25 passed").isEmpty();
    }

    @Test
    void theNewestSongOfTheParty_counts_notEachComputers() {
        String party = party(true);
        devices.notePlay(computer(party, NOW), NOW.minus(Duration.ofMinutes(40)), "Serato");
        devices.notePlay(computer(party, NOW), NOW.minus(Duration.ofMinutes(2)), "VirtualDJ");
        guestAsked(party, NOW.minus(Duration.ofMinutes(1)));

        assertThat(silent(NOW, party)).as("the second computer plays").isEmpty();
    }

    @Test
    void theLook_warnsOnceASilence_againAfterANewSongAndAnotherSilence() {
        String party = party(true);
        long id = computer(party, NOW);
        devices.notePlay(id, NOW.minus(Duration.ofMinutes(21)), "VirtualDJ");
        guestAsked(party, NOW.minus(Duration.ofMinutes(5)));
        String owner = "watch-" + party;
        DeckWatchService watch = watchAt(NOW);

        watch.look();
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), eq(20), anyBoolean());

        devices.notePlay(id, NOW.minus(Duration.ofMinutes(20)).plusSeconds(1), "VirtualDJ");
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyInt(), anyBoolean());

        DeckWatchService later = new DeckWatchService(jdbc, devices, parties, plans, push,
                Clock.fixed(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        later.look();
        verify(push, times(2)).notifyDeckSilent(eq(owner), eq(party), eq(20), anyBoolean());
    }

    @Test
    void thePanelsLines_warnOnlyDuringTheParty() {
        String party = party(true);
        long silentId = computer(party, NOW.minus(Duration.ofMinutes(1)));
        devices.notePlay(silentId, NOW.minus(Duration.ofMinutes(25)), "VirtualDJ");
        String quiet = party(true);
        devices.notePlay(computer(quiet, NOW), NOW.minus(Duration.ofMinutes(25)), "VirtualDJ");
        guestAsked(party, NOW.minus(Duration.ofMinutes(3)));

        DeckWatchService.Watch live = watchAt(NOW).watch(parties.getSettings(party));
        DeckWatchService.Watch noGuests = watchAt(NOW).watch(parties.getSettings(quiet));

        assertThat(live.silenceMinutes()).isEqualTo(20);
        assertThat(live.computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.SILENT);
            assertThat(computer.warn()).isTrue();
            assertThat(computer.program()).isEqualTo("VirtualDJ");
        });
        assertThat(noGuests.computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.SILENT);
            assertThat(computer.warn()).as("nobody asking: the set ended, no fault").isFalse();
        });
        verify(push, never()).notifyDeckSilent(anyString(), anyString(), anyInt(), anyBoolean());
    }
}
