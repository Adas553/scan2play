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
        request(party, ago, false);
    }

    /** Guests asking: one request at each of these times before now. */
    private void guestsAsked(String party, Duration... agos) {
        for (Duration ago : agos) {
            request(party, ago, false);
        }
    }

    /** The DJ's own "+ Dodaj piosenkę" (V44): a row of the queue, no guest. */
    private void djAdded(String party, Duration ago) {
        request(party, ago, true);
    }

    private void request(String party, Duration ago, boolean byDj) {
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, votes, checked, requested_at, request_number, added_by_dj)"
                + " VALUES (?, 'Sanah - Szampan', 'accepted', 1, true, ?,"
                + " (SELECT COALESCE(MAX(request_number), 0) + 1 FROM song_requests WHERE party_code = ?), ?)", party,
                Timestamp.from(NOW.minus(ago)), party, byDj);
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
                guestsAsked(party, min(15), min(14));
            }
        }
        watchAt(NOW).setSilenceMinutes(watchOff, 0);
        String[] all = {silentOne, playing, closed, noGuests, neverPlayed, watchOff, yesterday};

        assertThat(silent(NOW, all)).containsExactly(silentOne);
        watchAt(NOW).setSilenceMinutes(silentOne, 30);
        assertThat(silent(NOW, all)).as("30 minutes chosen, 25 passed").isEmpty();
    }

    /** The DJ's test before the party: one song at 15:00, the guests asking from 16:30 before the set — no alarm. */
    @Test
    void aSongPlayedAsATest_isNoSetThatStopped() {
        String party = party(true);
        long id = computer(party, NOW);
        song(id, min(90));
        guestsAsked(party, min(2), min(1));

        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        String played = party(true);   // the same silence after a set of half an hour: a set that stopped
        set(computer(played, NOW), min(120), min(90));
        guestsAsked(played, min(2), min(1));
        assertThat(silent(NOW, played)).containsExactly(played);
        assertThat(warns(played)).containsExactly(true);
    }

    /**
     * The end of the set at a party left open: the guests asked while its last song played and "one more!" just after it — no alarm.
     * Guests asking 8 minutes and more after the last song's start, twice: the music goes on, the program does not see it.
     */
    @Test
    void theEndOfTheSet_isNoFault_untilGuestsKeepAsking() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(120), min(21));
        guestsAsked(party, min(26), min(20), min(16));   // during the last song
        guestsAsked(party, min(15), min(14), min(13));   // "jeszcze jedna!" 6, 7 and exactly 8 min after its start

        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        guestAsked(party, min(10));
        assertThat(silent(NOW, party)).as("one late request is no party going on").isEmpty();
        assertThat(warns(party)).containsExactly(false);

        guestAsked(party, min(5));
        assertThat(silent(NOW, party)).containsExactly(party);
        assertThat(warns(party)).containsExactly(true);
    }

    /** A spare laptop tested at 15:00 is said in grey: only the computer the set is played on warns. */
    @Test
    void theLeadComputer_alone_warns_andItsCallsSayWhetherItIsGone() {
        String party = party(true);
        long spare = computer(party, NOW);
        long main = computer(party, NOW.minus(min(10)));
        song(spare, min(300));
        set(main, min(60), min(2));
        guestsAsked(party, min(1), min(1));

        assertThat(silent(NOW, party)).as("the main one plays").isEmpty();
        assertThat(warns(party)).containsExactly(false, false);

        String gone = party(true);
        long spareOn = computer(gone, NOW);
        long mainAsleep = computer(gone, NOW.minus(min(10)));
        song(spareOn, min(300));
        set(mainAsleep, min(60), min(25));
        guestsAsked(gone, min(5), min(4));

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
        guestsAsked(party, min(5), min(4));
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

        guestAsked(party, min(10));
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
        guestsAsked(party, min(5), min(4));

        watchAt(NOW).look();
        verify(push).notifyDeckSilent(eq("watch-" + party), eq(party), eq(22L), eq(true));
    }

    /** More minutes chosen: the silence warned about may be warned at them; fewer: no second push about it. */
    @Test
    void ostrzezPo_changed_warnsAgainOnlyAtMoreMinutes() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(60), min(25));
        guestsAsked(party, min(5), min(4));
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
            guestsAsked(asking, min(3), min(2));
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

    /** A soundcheck before the party — a quarter of an hour of songs, no guest yet — is no set; the guests at the tables later: no alarm. */
    @Test
    void aSoundcheck_isNoSet_aRunWithAGuestAskingIs() {
        String party = party(true);
        set(computer(party, NOW), min(95), min(80));
        guestsAsked(party, min(2), min(1));

        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        String played = party(true);   // the same quarter of an hour with a guest asking in it: the party had started
        set(computer(played, NOW), min(95), min(80));
        guestsAsked(played, min(90), min(2), min(1));
        assertThat(silent(NOW, played)).containsExactly(played);
        assertThat(warns(played)).containsExactly(true);

        String nine = party(true);   // nine minutes, a guest asking: a test, not a set
        set(computer(nine, NOW), min(89), min(80));
        guestsAsked(nine, min(85), min(2), min(1));
        assertThat(silent(NOW, nine)).isEmpty();

        String half = party(true);   // half an hour of songs before the first guest asked: a set
        set(computer(half, NOW), min(110), min(80));
        guestsAsked(half, min(2), min(1));
        assertThat(silent(NOW, half)).containsExactly(half);
    }

    /** The DJ's own "+ Dodaj piosenkę" is no guest: not in the run (no set), not after the last song (no party going on). */
    @Test
    void theDjsOwnSongs_areNoGuests() {
        String party = party(true);
        set(computer(party, NOW), min(95), min(80));
        djAdded(party, min(90));
        guestsAsked(party, min(2), min(1));
        assertThat(silent(NOW, party)).as("a soundcheck, the DJ typing the couple's list in it").isEmpty();

        String after = party(true);
        set(computer(after, NOW), min(60), min(25));
        djAdded(after, min(5));
        djAdded(after, min(4));
        assertThat(silent(NOW, after)).as("only the DJ adding songs since").isEmpty();
        assertThat(warns(after)).containsExactly(false);
    }

    /** The guests' minutes: a request of 61 minutes ago is no guest asking now — in the card as in the look. */
    @Test
    void guestsAsking_onlyInTheLastHour() {
        String party = party(true);
        set(computer(party, NOW), min(130), min(90));
        guestsAsked(party, min(62), min(61));
        assertThat(silent(NOW, party)).isEmpty();
        assertThat(warns(party)).containsExactly(false);

        guestsAsked(party, min(59), min(58));
        assertThat(silent(NOW, party)).containsExactly(party);
        assertThat(warns(party)).containsExactly(true);
    }

    /** The edges: silent past the minutes chosen, not at them. */
    @Test
    void silent_pastTheMinutes_notAtThem() {
        String party = party(true);
        set(computer(party, NOW), min(60), min(20));
        guestsAsked(party, min(5), min(4));
        assertThat(silent(NOW, party)).as("exactly 20 min").isEmpty();
        assertThat(silent(NOW.plusSeconds(1), party)).containsExactly(party);
    }

    /**
     * The program not started tonight after the test: amber in the card while guests ask — it does not connect — but no push (a test
     * song is no set that stopped).
     */
    @Test
    void theProgramGoneAfterTheTest_isAmberInTheCard_withoutAPush() {
        String party = party(true);
        long id = computer(party, NOW.minus(min(290)));
        song(id, min(300));
        guestsAsked(party, min(5), min(4));

        assertThat(watchAt(NOW).watch(parties.getSettings(party)).computers()).singleElement().satisfies(computer -> {
            assertThat(computer.state()).isEqualTo(DeckWatchService.State.OFFLINE);
            assertThat(computer.warn()).isTrue();
        });
        assertThat(silent(NOW, party)).isEmpty();
    }

    /** A set goes on after a break without music (a dinner, a band): a program that fails just after it is still warned about. */
    @Test
    void aSetGoesOnAfterABreak_aTestOrASoundcheckDoesNot() {
        String party = party(true);
        long id = computer(party, NOW);
        set(id, min(200), min(150));   // the set before dinner
        song(id, min(100));            // after 50 min without music, one song — then the program sees nothing
        assertThat(devices.devices(party).getFirst().playRunFrom()).as("the set's run goes on").isEqualTo(NOW.minus(min(200)));
        guestsAsked(party, min(5), min(4));
        assertThat(silent(NOW, party)).containsExactly(party);

        String tested = party(true);
        long test = computer(tested, NOW);
        song(test, min(300));          // the test at 15:00
        song(test, min(100));          // the set's first song
        assertThat(devices.devices(tested).getFirst().playRunFrom()).as("a test is no set: a new run").isEqualTo(NOW.minus(min(100)));

        String sound = party(true);
        long check = computer(sound, NOW);
        set(check, min(300), min(285));   // a soundcheck of 15 minutes, nobody asking
        song(check, min(100));
        assertThat(devices.devices(sound).getFirst().playRunFrom()).as("a soundcheck is no set: a new run").isEqualTo(NOW.minus(min(100)));

        String nextDay = party(true);
        long club = computer(nextDay, NOW);
        set(club, Duration.ofHours(11), Duration.ofHours(10));   // last night's set
        song(club, min(30));                                      // today's soundcheck: more than 4 h later
        assertThat(devices.devices(nextDay).getFirst().playRunFrom()).as("the next day: a new run").isEqualTo(NOW.minus(min(30)));
    }

    /** The run: a song kept before V82 starts one; one call's songs are one run, a call after a gap starts a new one. */
    @Test
    void theRun_fromARowBeforeV82_andAcrossOneCall() {
        String party = party(true);
        long id = computer(party, NOW);
        jdbc.update("UPDATE dj_device SET last_play_at = ?, play_run_from = NULL WHERE id = ?", Timestamp.from(NOW.minus(min(5))), id);
        song(id, min(1));
        assertThat(devices.devices(party).getFirst().playRunFrom()).as("a row before V82: a run from now on").isEqualTo(NOW.minus(min(1)));

        String queued = party(true);
        long late = computer(queued, NOW);
        song(late, min(100));
        set(late, min(80), min(1));   // the program's queue after the internet came back: songs from 20 min after the last one on
        assertThat(devices.devices(queued).getFirst().playRunFrom()).as("the gap measured to the call's oldest song")
                .isEqualTo(NOW.minus(min(100)));

        String gap = party(true);
        long after = computer(gap, NOW);
        song(after, min(100));
        set(after, min(60), min(1));   // the call's oldest song 40 min after the last one: a new run
        assertThat(devices.devices(gap).getFirst().playRunFrom()).isEqualTo(NOW.minus(min(60)));
    }

    /** The organiser in their own party's staff (with the queue): one push, not two. */
    @Test
    void theOrganiserInTheStaffToo_isWarnedOnce() {
        String party = party(true);
        set(computer(party, NOW), min(60), min(21));
        guestsAsked(party, min(5), min(4));
        String owner = "watch-" + party;
        staffRepository.saveAndFlush(PartyStaffEntity.builder().partyCode(party).memberId(owner).joinedAt(NOW)
                .permissions(EnumSet.of(StaffPermission.QUEUE)).build());

        watchAt(NOW).look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());
    }

    /** "Ostrzeż po" raised while the silence warned about has lasted past the new minutes already: no second push about it. */
    @Test
    void ostrzezPo_raisedPastTheSilence_noSecondPush() {
        String party = party(true);
        set(computer(party, NOW), min(60), min(25));
        guestsAsked(party, min(5), min(4));
        String owner = "watch-" + party;
        DeckWatchService watch = new DeckWatchService(jdbc, devices, parties, staff, plans, push, new MovingClock(NOW));
        watch.setSilenceMinutes(party, 10);

        watch.look();
        watch.setSilenceMinutes(party, 20);
        watch.look();
        verify(push, times(1)).notifyDeckSilent(eq(owner), eq(party), anyLong(), anyBoolean());
    }

    /** A spare laptop tested during the silence of the main one: the lead now, a test — no warning while it is. */
    @Test
    void theLeadChangingDuringASilence_toATestedSpare_warnsNot() {
        String party = party(true);
        set(computer(party, NOW), min(60), min(25));
        guestsAsked(party, min(5), min(4));
        assertThat(silent(NOW, party)).containsExactly(party);

        song(computer(party, NOW), min(1));
        assertThat(silent(NOW, party)).isEmpty();
    }
}
