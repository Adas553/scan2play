package com.scan2play.service;

import com.scan2play.model.PlayerCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.scan2play.model.PlayerLeaseMode.CLAIM;
import static com.scan2play.model.PlayerLeaseMode.TAKE_OVER;
import static com.scan2play.model.PlayerLeaseMode.WATCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "which dashboard window plays" lease: the window that holds it plays, the others only show the queue.
 */
class PlayerLeaseServiceTest {

    private static final String PARTY = "ABC12";
    private static final String COMPUTER = "computer-0000-1111";
    private static final String PHONE = "phone-2222-3333";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T20:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }

    private TestClock clock;
    private PlayerLeaseService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        service = new PlayerLeaseService(clock);
    }

    private static PlayerLeaseService.Status holder() {
        return new PlayerLeaseService.Status(true, false, null, null);
    }

    private static PlayerLeaseService.Status holderWith(PlayerCommand command) {
        return new PlayerLeaseService.Status(true, false, command, null);
    }

    private static PlayerLeaseService.Status notHolder() {
        return new PlayerLeaseService.Status(false, false, null, null);
    }

    private static PlayerLeaseService.Status noneHolds() {
        return new PlayerLeaseService.Status(false, true, null, null);
    }

    // ---- commands: the phone as a remote control of the window that plays ----

    @Test
    @DisplayName("a command given while a window plays is handed to that window with its next report — once")
    void shouldHandTheCommandToTheHolder_once() {
        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.sendCommand(PARTY, PlayerCommand.NEXT)).isTrue();

        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.NEXT));
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("a window that does not play never receives the command, and does not use it up")
    void shouldNotHandTheCommandToAWindowThatDoesNotPlay() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        assertThat(service.report(PARTY, PHONE, WATCH)).isEqualTo(notHolder());
        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(notHolder());
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.NEXT));
    }

    @Test
    @DisplayName("nobody plays: the command is refused (it would wait for nobody), and nothing is kept for later")
    void shouldRefuseACommand_whenNobodyPlays() {
        assertThat(service.sendCommand(PARTY, PlayerCommand.NEXT)).isFalse();

        service.report(PARTY, COMPUTER, CLAIM);
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());

        service.release(PARTY, COMPUTER);
        assertThat(service.sendCommand(PARTY, PlayerCommand.NEXT)).isFalse();
    }

    @Test
    @DisplayName("an expired lease does not accept a command either")
    void shouldRefuseACommand_whenTheLeaseHasExpired() {
        service.report(PARTY, COMPUTER, CLAIM);
        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.sendCommand(PARTY, PlayerCommand.NEXT)).isFalse();
    }

    @Test
    @DisplayName("a command left behind by a window that went away is not carried out by whoever claims the lease next")
    void shouldNotCarryOutAStaleCommand_afterTheLeaseExpired() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("the same window that reports late, after its lease expired, does not get a command from before either")
    void shouldNotCarryOutAStaleCommand_whenTheSameWindowComesBack() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("pressing next twice before the window that plays reports in gives one command, not two")
    void shouldKeepOnlyOneCommandPerParty() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.NEXT));
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    // ---- pause / resume: the window that plays says whether its player makes sound, everybody hears it ----

    @Test
    @DisplayName("pause and resume are handed to the window that plays like the other commands, and the last press wins")
    void shouldHandOverPauseAndResume() {
        service.report(PARTY, COMPUTER, CLAIM);

        service.sendCommand(PARTY, PlayerCommand.PAUSE);
        assertThat(service.report(PARTY, COMPUTER, CLAIM).command()).isEqualTo(PlayerCommand.PAUSE);

        service.sendCommand(PARTY, PlayerCommand.PAUSE);
        service.sendCommand(PARTY, PlayerCommand.RESUME);
        assertThat(service.report(PARTY, COMPUTER, CLAIM).command()).isEqualTo(PlayerCommand.RESUME);
        assertThat(service.report(PARTY, COMPUTER, CLAIM).command()).isNull();
    }

    @Test
    @DisplayName("what the holder says about its player is told back to every window: making sound, then paused")
    void shouldTellEveryWindowWhetherThePlayerMakesSound() {
        service.report(PARTY, COMPUTER, CLAIM, true);
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isTrue();

        service.report(PARTY, COMPUTER, CLAIM, false);
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isFalse();
        assertThat(service.report(PARTY, COMPUTER, CLAIM, false).playing()).isFalse();
    }

    @Test
    @DisplayName("a report that says nothing about the player leaves the last known state alone")
    void shouldKeepTheLastStateWhenAReportSaysNothing() {
        service.report(PARTY, COMPUTER, CLAIM, false);

        service.report(PARTY, COMPUTER, CLAIM);   // the holder renews without saying

        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isFalse();
    }

    @Test
    @DisplayName("only the holder is believed: a window that does not hold the lease cannot change the state")
    void shouldIgnoreWhatAWindowThatDoesNotPlaySaysAboutThePlayer() {
        service.report(PARTY, COMPUTER, CLAIM, true);

        assertThat(service.report(PARTY, PHONE, CLAIM, false)).isEqualTo(
                new PlayerLeaseService.Status(false, false, null, true));   // refused, and it hears the truth

        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isTrue();
    }

    @Test
    @DisplayName("nothing is known while nobody plays, before the holder has said anything, and once the lease has gone")
    void shouldKnowNothingWithoutAHolder() {
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isNull();

        service.report(PARTY, COMPUTER, CLAIM);                            // holds the lease, has not said yet
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isNull();

        service.report(PARTY, COMPUTER, CLAIM, true);
        service.release(PARTY, COMPUTER);
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isNull();

        service.report(PARTY, COMPUTER, CLAIM, true);
        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));
        assertThat(service.report(PARTY, PHONE, WATCH).playing()).isNull();
    }

    @Test
    @DisplayName("a window that takes the lease over brings its own state, not the old holder's")
    void shouldTakeTheStateOfTheNewHolder() {
        service.report(PARTY, COMPUTER, CLAIM, true);

        service.report(PARTY, PHONE, TAKE_OVER, false);

        assertThat(service.report(PARTY, COMPUTER, WATCH).playing()).isFalse();
    }

    @Test
    @DisplayName("back is handed over like next; when both are pressed in one interval the last one pressed wins")
    void shouldHandOverThePreviousCommand_andLetTheLastPressWin() {
        service.report(PARTY, COMPUTER, CLAIM);

        service.sendCommand(PARTY, PlayerCommand.PREVIOUS);
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.PREVIOUS));

        service.sendCommand(PARTY, PlayerCommand.NEXT);
        service.sendCommand(PARTY, PlayerCommand.PREVIOUS);
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.PREVIOUS));
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("a command meant for the window that played does not fire in the window that takes over")
    void shouldDropTheCommand_whenTheLeaseChangesHands() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        assertThat(service.report(PARTY, PHONE, TAKE_OVER)).isEqualTo(holder());
        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("a command waiting for a window that leaves is dropped with its lease")
    void shouldDropTheCommand_whenTheHolderReleasesTheLease() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        service.release(PARTY, COMPUTER);

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("commands of one party are not seen by another party")
    void shouldKeepCommandsOfDifferentPartiesApart() {
        service.report(PARTY, COMPUTER, CLAIM);
        service.report("XYZ99", PHONE, CLAIM);
        service.sendCommand(PARTY, PlayerCommand.NEXT);

        assertThat(service.report("XYZ99", PHONE, CLAIM)).isEqualTo(holder());
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holderWith(PlayerCommand.NEXT));
    }

    @Test
    @DisplayName("the first window to report in with CLAIM gets the lease")
    void shouldGiveTheLeaseToTheFirstClaim() {
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("a second window that claims while the lease is live does not get it")
    void shouldNotGiveALiveLeaseToASecondClaim() {
        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(notHolder());
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("WATCH never takes the lease, not even a free one — it only reports that nobody holds it")
    void shouldNeverTakeTheLease_inWatchMode() {
        assertThat(service.report(PARTY, PHONE, WATCH)).isEqualTo(noneHolds());
        assertThat(service.mayPlay(PARTY, COMPUTER)).isTrue();
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("WATCH by a window that is not the holder leaves the lease where it is")
    void shouldLeaveTheLeaseAlone_whenAnotherWindowWatches() {
        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.report(PARTY, PHONE, WATCH)).isEqualTo(notHolder());
        assertThat(service.mayPlay(PARTY, COMPUTER)).isTrue();
        assertThat(service.mayPlay(PARTY, PHONE)).isFalse();
    }

    @Test
    @DisplayName("TAKE_OVER moves a live lease to the window that asks; the old holder learns it on its next report")
    void shouldMoveTheLease_onTakeOver() {
        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.report(PARTY, PHONE, TAKE_OVER)).isEqualTo(holder());
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(notHolder());
        assertThat(service.mayPlay(PARTY, COMPUTER)).isFalse();
        assertThat(service.mayPlay(PARTY, PHONE)).isTrue();
    }

    @Test
    @DisplayName("a lease that is not renewed within the timeout is free; a window that only watches is told so")
    void shouldFreeTheLease_whenItIsNotRenewed() {
        service.report(PARTY, COMPUTER, CLAIM);

        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.report(PARTY, PHONE, WATCH)).isEqualTo(noneHolds());
    }

    @Test
    @DisplayName("a free lease can be taken by CLAIM, and the window that lost it does not get it back by claiming")
    void shouldGiveAFreeLeaseToTheNextClaim() {
        service.report(PARTY, COMPUTER, CLAIM);
        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(notHolder());
    }

    @Test
    @DisplayName("the holder that reports in late, before anyone else does, keeps its lease")
    void shouldLetTheHolderRenew_afterTheTimeout_ifNobodyElseAsked() {
        service.report(PARTY, COMPUTER, CLAIM);
        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(30));

        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(notHolder());
    }

    @Test
    @DisplayName("each report renews the lease, so a window that keeps reporting in never loses it")
    void shouldRenewTheLease_onEveryReport() {
        service.report(PARTY, COMPUTER, CLAIM);

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(4));
            assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
        }

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(notHolder());
    }

    @Test
    @DisplayName("the lease is per party — another party's window is not affected")
    void shouldKeepLeasesOfDifferentPartiesApart() {
        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.report("XYZ99", PHONE, CLAIM)).isEqualTo(holder());
        assertThat(service.mayPlay(PARTY, PHONE)).isFalse();
    }

    @Test
    @DisplayName("mayPlay: anyone may ask when nobody holds a live lease; otherwise only the holder — no id counts as another window")
    void shouldAllowAskingForTheNextTrack_onlyToTheHolder() {
        assertThat(service.mayPlay(PARTY, PHONE)).isTrue();
        assertThat(service.mayPlay(PARTY, null)).isTrue();

        service.report(PARTY, COMPUTER, CLAIM);

        assertThat(service.mayPlay(PARTY, COMPUTER)).isTrue();
        assertThat(service.mayPlay(PARTY, PHONE)).isFalse();
        assertThat(service.mayPlay(PARTY, null)).isFalse();

        clock.advance(PlayerLeaseService.LEASE_TTL.plusSeconds(1));

        assertThat(service.mayPlay(PARTY, PHONE)).isTrue();
    }

    @Test
    @DisplayName("release: the holder gives the lease up at once, so the next window that claims gets it")
    void shouldFreeTheLease_whenTheHolderReleasesIt() {
        service.report(PARTY, COMPUTER, CLAIM);

        service.release(PARTY, COMPUTER);

        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(holder());
    }

    @Test
    @DisplayName("release by a window that does not hold the lease changes nothing")
    void shouldIgnoreARelease_fromAWindowThatDoesNotHoldTheLease() {
        service.report(PARTY, COMPUTER, CLAIM);

        service.release(PARTY, PHONE);
        service.release("XYZ99", COMPUTER);

        assertThat(service.report(PARTY, COMPUTER, CLAIM)).isEqualTo(holder());
        assertThat(service.report(PARTY, PHONE, CLAIM)).isEqualTo(notHolder());
    }

    @Test
    @DisplayName("release of a party that never had a lease is harmless")
    void shouldIgnoreARelease_ofAPartyWithoutALease() {
        service.release(PARTY, COMPUTER);

        assertThat(service.mayPlay(PARTY, PHONE)).isTrue();
    }

    @Test
    @DisplayName("a window id is a random string of letters, digits, '-' and '_', 8 to 64 characters — anything else is refused")
    void shouldValidateTheDeviceId() {
        assertThat(PlayerLeaseService.isValidDeviceId("0f8fad5b-d9cb-469f-a165-70867728950e")).isTrue();
        assertThat(PlayerLeaseService.isValidDeviceId("dk3j2l9x0a1b")).isTrue();
        assertThat(PlayerLeaseService.isValidDeviceId("a".repeat(64))).isTrue();

        assertThat(PlayerLeaseService.isValidDeviceId(null)).isFalse();
        assertThat(PlayerLeaseService.isValidDeviceId("")).isFalse();
        assertThat(PlayerLeaseService.isValidDeviceId("short")).isFalse();
        assertThat(PlayerLeaseService.isValidDeviceId("a".repeat(65))).isFalse();
        assertThat(PlayerLeaseService.isValidDeviceId("has space in it")).isFalse();
        assertThat(PlayerLeaseService.isValidDeviceId("<script>alert(1)</script>")).isFalse();
    }
}
