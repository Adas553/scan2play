package com.scan2play.util;

import com.scan2play.util.PartySchedule.Plan;
import com.scan2play.util.PartySchedule.Problem;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules of a party's planned open and close (V55): what the form's day and time mean in Polish time — across the change of the
 * clocks too —, what a plan may not be, and which moments a manual open or close leaves.
 */
class PartyScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-30T11:00:00Z");   // Friday, 12:00 in Poland
    private static final Instant TODAY_20 = Instant.parse("2026-10-30T19:00:00Z");
    private static final Instant SATURDAY_3 = Instant.parse("2026-10-31T02:00:00Z");
    private static final Instant SATURDAY_20 = Instant.parse("2026-10-31T19:00:00Z");

    @Test
    void theFormsDayAndTime_areAMomentInPolishTime() {
        assertThat(PartySchedule.moment("2026-10-30", "20:00")).isEqualTo(TODAY_20);
        assertThat(PartySchedule.moment(" 2026-10-31 ", "03:00")).isEqualTo(SATURDAY_3);
        assertThat(PartySchedule.moment("2026-07-04", "20:00")).as("summer: UTC+2").isEqualTo(Instant.parse("2026-07-04T18:00:00Z"));
        assertThat(PartySchedule.moment("2026-10-30", "20:00:45")).as("to the minute").isEqualTo(TODAY_20);
        assertThat(PartySchedule.moment("", "")).isNull();
        assertThat(PartySchedule.moment(null, " ")).isNull();
    }

    /**
     * The clocks: in autumn 2:30 comes twice (2026-10-25) — the first, still summer time —; in spring 2:30 never comes (2027-03-28) —
     * the same time after the change, 3:30 summer time. The hours around them as they are.
     */
    @Test
    void theChangeOfTheClocks() {
        assertThat(PartySchedule.moment("2026-10-25", "02:30")).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(PartySchedule.moment("2026-10-25", "03:00")).isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
        assertThat(PartySchedule.moment("2026-10-24", "23:00")).isEqualTo(Instant.parse("2026-10-24T21:00:00Z"));
        assertThat(PartySchedule.moment("2027-03-28", "02:30")).isEqualTo(Instant.parse("2027-03-28T01:30:00Z"));
        assertThat(PartySchedule.moment("2027-03-28", "03:30")).isEqualTo(Instant.parse("2027-03-28T01:30:00Z"));
        assertThat(PartySchedule.moment("2027-03-28", "01:59")).isEqualTo(Instant.parse("2027-03-28T00:59:00Z"));
    }

    @Test
    void aHalfOfAMoment_orNoMoment_isRefused() {
        assertProblem(() -> PartySchedule.moment("2026-10-30", ""), Problem.INCOMPLETE);
        assertProblem(() -> PartySchedule.moment(null, "20:00"), Problem.INCOMPLETE);
        assertProblem(() -> PartySchedule.moment("30.10.2026", "20:00"), Problem.NOT_A_MOMENT);
        assertProblem(() -> PartySchedule.moment("2026-10-30", "25:00"), Problem.NOT_A_MOMENT);
        assertProblem(() -> PartySchedule.moment("2026-02-30", "20:00"), Problem.NOT_A_MOMENT);
    }

    @Test
    void aPlan_comesLater_andNotTooFar_andNotBothAtOnce() {
        PartySchedule.check(new Plan(TODAY_20, SATURDAY_3), false, NOW);
        PartySchedule.check(new Plan(null, SATURDAY_3), true, NOW);
        PartySchedule.check(Plan.NONE, true, NOW);
        PartySchedule.check(new Plan(SATURDAY_20, SATURDAY_3), true, NOW);   // open now: closes at 3, opens again at 20

        assertProblem(() -> PartySchedule.check(new Plan(NOW, null), false, NOW), Problem.PAST);
        assertProblem(() -> PartySchedule.check(new Plan(null, NOW.minusSeconds(60)), true, NOW), Problem.PAST);
        assertProblem(() -> PartySchedule.check(new Plan(NOW.plus(java.time.Duration.ofDays(366)), null), false, NOW), Problem.TOO_FAR);
        assertProblem(() -> PartySchedule.check(new Plan(TODAY_20, TODAY_20), false, NOW), Problem.SAME_MOMENT);
    }

    /** A moment that would change nothing is refused, with what to do instead. */
    @Test
    void aMomentThatWouldChangeNothing_isRefused() {
        assertProblem(() -> PartySchedule.check(new Plan(TODAY_20, null), true, NOW), Problem.OPEN_WHILE_OPEN);
        assertProblem(() -> PartySchedule.check(new Plan(TODAY_20, SATURDAY_3), true, NOW), Problem.OPEN_WHILE_OPEN);
        assertProblem(() -> PartySchedule.check(new Plan(null, SATURDAY_3), false, NOW), Problem.CLOSE_WHILE_CLOSED);
        assertProblem(() -> PartySchedule.check(new Plan(SATURDAY_20, SATURDAY_3), false, NOW), Problem.CLOSE_WHILE_CLOSED);
    }

    /**
     * The owner's rule (2026-10-30): a manual change against the plan wins until the next planned moment. The DJ closes early: closed,
     * the planned close is gone (it has happened), the planned open stays. The DJ opens early: the planned open is gone, the close stays.
     */
    @Test
    void aManualCloseOrOpen_dropsTheMomentItMadePointless() {
        // open, closes at 3, opens again at 20 — the DJ closes at 1
        assertThat(PartySchedule.settle(new Plan(SATURDAY_20, SATURDAY_3), false)).isEqualTo(new Plan(SATURDAY_20, null));
        // closed, opens at 20, closes at 3 — the DJ opens at 19
        assertThat(PartySchedule.settle(new Plan(TODAY_20, SATURDAY_3), true)).isEqualTo(new Plan(null, SATURDAY_3));
        // only a close, the DJ closes: nothing left; only an open, the DJ opens: nothing left
        assertThat(PartySchedule.settle(new Plan(null, SATURDAY_3), false)).isEqualTo(Plan.NONE);
        assertThat(PartySchedule.settle(new Plan(TODAY_20, null), true)).isEqualTo(Plan.NONE);
        // a change that agrees with the plan keeps it
        assertThat(PartySchedule.settle(new Plan(TODAY_20, SATURDAY_3), false)).isEqualTo(new Plan(TODAY_20, SATURDAY_3));
        assertThat(PartySchedule.settle(new Plan(SATURDAY_20, SATURDAY_3), true)).isEqualTo(new Plan(SATURDAY_20, SATURDAY_3));
        assertThat(PartySchedule.settle(Plan.NONE, true)).isEqualTo(Plan.NONE);
    }

    private static void assertProblem(Runnable call, Problem problem) {
        assertThatThrownBy(call::run).isInstanceOf(PartySchedule.Refused.class)
                .satisfies(e -> assertThat(((PartySchedule.Refused) e).problem()).isEqualTo(problem));
        assertThat(problem.messageKey()).startsWith("schedule.error.");
    }
}
