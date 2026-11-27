package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * "Zagrane samo" watched (V81, V82) on a real PostgreSQL: the newest song's time kept and never moved back, the run of songs, "Ostrzeż
 * po" stored, the look's one query — which parties are silent during the party (open, a set played on the lead computer, its minutes
 * passed, guests asking since its last song) — the card's lines by the same rule, and one push a silence to the organiser and the
 * staff who work the queue.
 */
class DeckWatchIT extends PostgresIntegrationTest {

    @Autowired DjDeviceService devices;
    @Autowired PartySettingsRepository partyRepository;
    @Autowired PartyStaffRepository staffRepository;
    @Autowired PartySettingsQueryService parties;
    @Autowired PartyStaffService staff;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private final PushNotificationService push = mock(PushNotificationService.class);

    /** A clock the test moves: one service, its memory of the pushes kept, minutes later. */
    private static final class MovingClock extends Clock {
        private Instant at;

        MovingClock(Instant at) {
            this.at = at;
        }

        void move(Duration by) {
            at = at.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }

    private DeckWatchService watchAt(Instant now) {
        return new DeckWatchService(jdbc, devices, parties, staff, plans, push, Clock.fixed(now, ZoneOffset.UTC));
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

    /** One song, {@code ago} before now. */
    private void song(long id, Duration ago) {
        devices.notePlay(id, NOW.minus(ago), NOW.minus(ago), "VirtualDJ");
    }

    /** A set: songs from {@code fromAgo} to {@code toAgo} before now, in one call. */
    private void set(long id, Duration fromAgo, Duration toAgo) {
        devices.notePlay(id, NOW.minus(fromAgo), NOW.minus(toAgo), "VirtualDJ");
    }

    private void guestAsked(String party, Duration ago) {
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, votes, checked, requested_at, request_number)"
                + " VALUES (?, 'Sanah - Szampan', 'accepted', 1, true, ?,"
                + " (SELECT COALESCE(MAX(request_number), 0) + 1 FROM song_requests WHERE party_code = ?))", party,
                Timestamp.from(NOW.minus(ago)), party);
    }

    private List<String> silent(Instant now, String... among) {
        return watchAt(now).silentNow(now).stream().map(DeckWatchService.Silent::partyCode)
                .filter(List.of(among)::contains).toList();
    }

    private List<Boolean> warns(String party) {
        return watchAt(NOW).watch(parties.getSettings(party)).computers().stream().map(DeckWatchService.Computer::warn).toList();
    }

    private static Duration min(long minutes) {
        return Duration.ofMinutes(minutes);
    }

    @Test
    void theNewestSong_isKept_andNeverMovedBack_theProgramKeptWhenNotSaid_theRunNoted() {
        String party = party(true);
        long id = computer(party, NOW);

        devices.notePlay(id, NOW.minus(min(9)), NOW.minusSeconds(60), "VirtualDJ");
        devices.notePlay(id, NOW.minusSeconds(600), NOW.minusSeconds(600), "Serato");
        DjDeviceService.Device kept = devices.devices(party).getFirst();
        assertThat(kept.lastPlayAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(kept.playRunFrom()).as("the run from the call's oldest song").isEqualTo(NOW.minus(min(9)));
        assertThat(kept.program()).as("an older song moves nothing").isEqualTo("VirtualDJ");

        devices.notePlay(id, NOW, NOW, null);
        kept = devices.devices(party).getFirst();
        assertThat(kept.lastPlayAt()).isEqualTo(NOW);
        assertThat(kept.playRunFrom()).as("a song 1 min after the last: the same run").isEqualTo(NOW.minus(min(9)));
        assertThat(kept.program()).as("a program too old to say keeps the last one").isEqualTo("VirtualDJ");

        devices.notePlay(id, NOW.plus(min(31)), NOW.plus(min(31)), "VirtualDJ");
        assertThat(devices.devices(party).getFirst().playRunFrom()).as("31 min without a song: a new run")
                .isEqualTo(NOW.plus(min(31)));
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
                set(id, min(60), min(5));
            } else if (party.equals(yesterday)) {
                set(id, Duration.ofHours(14), Duration.ofHours(13));
            } else if (!party.equals(neverPlayed)) {
                set(id, min(60), min(25));
            }
            if (!party.equals(noGuests)) {
                guestAsked(party, min(15));
            }
        }
        watchAt(NOW).setSilenceMinutes(watchOff, 0);
        String[] all = {silentOne, playing, closed, noGuests, neverPlayed, watchOff, yesterday};

        assertThat(silent(NOW, all)).containsExactly(silentOne);
        watchAt(NOW).setSilenceMinutes(silentOne, 30);
        assertThat(silent(NOW, all)).as("30 minutes chosen, 25 passed").isEmpty();
    }

    /** The DJ's test before the party: one song at 15:00, the guests asking from 16:30 before the set — no alarm (review P1). */
    @Test
    void aSongPlayedAsATest_isNoSetThatStopped() {
        String party = party(true);
        long id = computer(party, NOW);
        song(id, min(90));
        guestAsked(party, min(1));

        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        String played = party(true);   // the same silence after a set of half an hour: a set that stopped
        set(computer(played, NOW), min(120), min(90));
        guestAsked(played, min(1));
        assertThat(silent(NOW, played)).containsExactly(played);
        assertThat(warns(played)).containsExactly(true);
    }

    /** The end of the set at a party left open: the guests asked while it played, not since — no alarm (review P2). */
    @Test
    void theEndOfTheSet_isNoFault_untilAGuestAsksSince() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(120), min(21));
        guestAsked(party, min(26));
        guestAsked(party, min(20));   // a minute after the last song started: still the set's end

        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        guestAsked(party, min(10));   // asking while nothing is seen: the music goes on, the program does not see it
        assertThat(silent(NOW, party)).containsExactly(party);
        assertThat(warns(party)).containsExactly(true);
    }

    /** A spare laptop tested at 15:00 is said in grey: only the computer the set is played on warns (review P3). */
    @Test
    void theLeadComputer_alone_warns_andItsCallsSayWhetherItIsGone() {
        String party = party(true);
        long spare = computer(party, NOW);
        long main = computer(party, NOW.minus(min(10)));
        song(spare, min(300));
        set(main, min(60), min(2));
        guestAsked(party, min(1));

        assertThat(silent(NOW, party)).as("the main one plays").isEmpty();
        assertThat(warns(party)).containsExactly(false, false);

        String gone = party(true);
        long spareOn = computer(gone, NOW);
        long mainAsleep = computer(gone, NOW.minus(min(10)));
        song(spareOn, min(300));
        set(mainAsleep, min(60), min(25));
        guestAsked(gone, min(5));

        DeckWatchService.Watch watch = watchAt(NOW).watch(parties.getSettings(gone));
        assertThat(watch.computers()).filteredOn(DeckWatchService.Computer::warn).singleElement()
                .satisfies(computer -> assertThat(computer.id()).isEqualTo(mainAsleep));
        assertThat(watchAt(NOW).silentNow(NOW)).filteredOn(s -> s.partyCode().equals(gone)).singleElement()
                .satisfies(s -> assertThat(s.lastSeenAt()).as("the lead's own last call, not the spare's")
                        .isEqualTo(NOW.minus(min(10))));
    }

    @Test
    void theLook_warnsOnceASilence_theOrganiserAndTheQueuesStaff_withTheSilenceAsItIs() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(60), min(21));
        guestAsked(party, min(5));
        String owner = "watch-" + party;
        staffRepository.saveAndFlush(PartyStaffEntity.builder().partyCode(party).memberId("dj-" + party).joinedAt(NOW)
                .permissions(EnumSet.of(StaffPermission.QUEUE)).build());
        staffRepository.saveAndFlush(PartyStaffEntity.builder().partyCode(party).memberId("door-" + party).joinedAt(NOW)
                .permissions(EnumSet.noneOf(StaffPermission.class)).build());
        MovingClock clock = new MovingClock(NOW);
        DeckWatchService watch = new DeckWatchService(jdbc, devices, parties, staff, plans, push, clock);

        watch.look();
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), eq(21L), eq(false));
        verify(push, times(1)).notifyDeckSilent(eq("dj-" + party), eq(party), eq(21L), eq(false));
        verify(push, never()).notifyDeckSilent(eq("door-" + party), anyString(), anyLong(), anyBoolean());

        song(id, min(20).minusSeconds(1));
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());

        guestAsked(party, min(15));
        clock.move(min(5));
        watch.look();
        // 24 min since the new song; the program has not called for these 5 minutes either (the test calls for it only at NOW)
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), eq(24L), eq(true));
    }

    /** The program stopped calling too: the push says it is not connected. */
    @Test
    void theLook_saysTheProgramIsGone_whenTheLeadStoppedCalling() {
        String party = party(true);
        long id = computer(party, NOW.minus(min(22)));
        set(id, min(60), min(22));
        guestAsked(party, min(5));

        watchAt(NOW).look();
        verify(push).notifyDeckSilent(eq("watch-" + party), eq(party), eq(22L), eq(true));
    }

    /** More minutes chosen: the silence warned about may be warned at them; fewer: no second push about it (review P6). */
    @Test
    void ostrzezPo_changed_warnsAgainOnlyAtMoreMinutes() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(60), min(25));
        guestAsked(party, min(5));
        String owner = "watch-" + party;
        MovingClock clock = new MovingClock(NOW);
        DeckWatchService watch = new DeckWatchService(jdbc, devices, parties, staff, plans, push, clock);

        watch.look();
        watch.setSilenceMinutes(party, 10);
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());

        watch.setSilenceMinutes(party, 30);
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());
        clock.move(min(6));
        watch.look();
        verify(push, times(2)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());
    }

    @Test
    void thePanelsLines_warnOnlyDuringTheParty() {
        String party = party(true);
        long silentId = computer(party, NOW.minus(min(1)));
        set(silentId, min(60), min(25));
        String quiet = party(true);
        set(computer(quiet, NOW), min(60), min(25));
        String closed = party(false);
        set(computer(closed, NOW), min(60), min(25));
        String never = party(true);
        set(computer(never, NOW.minus(min(10))), min(60), min(25));
        for (String asking : List.of(party, closed, never)) {
            guestAsked(asking, min(3));
        }
        watchAt(NOW).setSilenceMinutes(never, 0);

        DeckWatchService.Watch live = watchAt(NOW).watch(parties.getSettings(party));
        assertThat(live.silenceMinutes()).isEqualTo(20);
        assertThat(live.computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.SILENT);
            assertThat(computer.warn()).isTrue();
            assertThat(computer.program()).isEqualTo("VirtualDJ");
        });
        assertThat(watchAt(NOW).watch(parties.getSettings(quiet)).computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.SILENT);
            assertThat(computer.warn()).as("nobody asking: the set ended, no fault").isFalse();
        });
        assertThat(warns(closed)).as("the party closed").containsExactly(false);
        assertThat(watchAt(NOW).watch(parties.getSettings(never)).computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.OFFLINE);
            assertThat(computer.warn()).as("\"nigdy\": neither push nor amber").isFalse();
        });
        verify(push, never()).notifyDeckSilent(anyString(), anyString(), anyLong(), anyBoolean());
    }
}
