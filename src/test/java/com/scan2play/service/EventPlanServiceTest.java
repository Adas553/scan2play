package com.scan2play.service;

import com.scan2play.service.EventPlanService.Moment;
import com.scan2play.service.EventPlanService.Moments;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Plan imprezy": the moments as a form sends them — empty rows out, a row without a name named, times read, the limits held; tonight's
 * moments as moments in Polish time; which of a day's plans is tonight's.
 */
class EventPlanServiceTest {

    @Test
    void theFormsRows_becomeMoments_inTheirOrder_theEmptyOnesLeftOut() {
        Moments moments = EventPlanService.moments(
                new String[] {"Wejście pary", "", "  ", "Tort"},
                new String[] {"17:30", "", "", "7.05"},
                new String[] {"Ktoś – Piosenka", "", "", ""},
                new String[] {"wolno,\npo toaście", "", "", ""}, "Moment");

        assertThat(moments.dropped()).isZero();
        assertThat(moments.kept()).containsExactly(
                new Moment("Wejście pary", LocalTime.of(17, 30), "Ktoś - Piosenka", "wolno, po toaście"),
                new Moment("Tort", LocalTime.of(7, 5), null, null));
    }

    @Test
    void aRowWithASongButNoName_isNamed_andAWrongTimeIsNone() {
        Moments moments = EventPlanService.moments(new String[] {""}, new String[] {"o północy"}, new String[] {"Wilki - Baśka"},
                new String[] {""}, "Moment");

        assertThat(moments.kept()).containsExactly(new Moment("Moment", null, "Wilki - Baśka", null));
        assertThat(EventPlanService.time("25:00")).isNull();
        assertThat(EventPlanService.time("0:30")).isEqualTo(LocalTime.of(0, 30));
    }

    @Test
    void thePlanHoldsThirtyMoments_andTheFieldsTheirLengths() {
        String[] labels = Collections.nCopies(EventPlanService.MAX_MOMENTS + 2, "x".repeat(100)).toArray(String[]::new);

        Moments moments = EventPlanService.moments(labels, null, null, new String[] {"n".repeat(400)}, "Moment");

        assertThat(moments.kept()).hasSize(EventPlanService.MAX_MOMENTS);
        assertThat(moments.dropped()).isEqualTo(2);
        assertThat(moments.kept().getFirst().label()).hasSize(EventPlanService.LABEL_MAX);
        assertThat(moments.kept().getFirst().note()).hasSize(EventPlanService.NOTE_MAX);
        assertThat(moments.kept().get(1).note()).isNull();
        assertThat(EventPlanService.moments(null, null, null, null, "Moment").kept()).isEmpty();
    }

    @Test
    void anEmptyPlan_isEmpty_andATimeShowsAsTheFormWantsIt() {
        assertThat(new EventPlanService.Plan(java.util.List.of(), " ", null, null, null).isEmpty()).isTrue();
        assertThat(new EventPlanService.Plan(java.util.List.of(), null, "Akcent", null, null).isEmpty()).isFalse();
        assertThat(new Moment("Tort", LocalTime.of(7, 5), null, null).timeText()).isEqualTo("07:05");
        assertThat(new Moment("Tort", null, null, null).timeText()).isEmpty();
    }

    @Test
    void aRowWithOnlyATime_isNamed_notLost() {
        Moments moments = EventPlanService.moments(new String[] {""}, new String[] {"21:00"}, new String[] {""}, new String[] {""}, "Moment");

        assertThat(moments.kept()).containsExactly(new Moment("Moment", LocalTime.of(21, 0), null, null));
    }

    @Test
    void aLongName_isCutBetweenCharacters_neverInHalfAnEmoji() {
        String label = "x".repeat(EventPlanService.LABEL_MAX - 1) + "🎂🎂";

        String cut = EventPlanService.tidy(label, EventPlanService.LABEL_MAX);

        assertThat(cut).isEqualTo("x".repeat(EventPlanService.LABEL_MAX - 1) + "🎂");
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(EventPlanService.LABEL_MAX);
    }

    private static final LocalDate EVENING = LocalDate.of(2027, 6, 12);   // summer: Warsaw is UTC+2

    private static EventPlanService.Tonight tonight(long id, Moment... moments) {
        return new EventPlanService.Tonight(id, "Wydarzenie " + id, EVENING, List.of(moments), null, null);
    }

    @Test
    void aMomentsTime_isAMomentInPolishTime_theNightAfterBeforeSix() {
        EventPlanService.Tonight plan = tonight(1, new Moment("Tort", LocalTime.of(23, 0), null, null),
                new Moment("Oczepiny", LocalTime.of(0, 30), null, null), new Moment("Ostatni", null, null, null));

        assertThat(Instant.ofEpochMilli(plan.at(plan.moments().get(0)))).isEqualTo(Instant.parse("2027-06-12T21:00:00Z"));
        assertThat(Instant.ofEpochMilli(plan.at(plan.moments().get(1)))).as("0:30: the night after the evening's day")
                .isEqualTo(Instant.parse("2027-06-12T22:30:00Z"));
        assertThat(plan.at(plan.moments().get(2))).isNull();
        assertThat(plan.key(plan.moments().get(1))).isEqualTo("Oczepiny|00:30");
        assertThat(plan.key(plan.moments().get(2))).isEqualTo("Ostatni|");
    }

    @Test
    void twoPlansOnOneDay_tonightsIsTheOneStartedLast_elseTheFirstToStart() {
        EventPlanService.Tonight christening = tonight(1, new Moment("Obiad", LocalTime.of(14, 0), null, null));
        EventPlanService.Tonight wedding = tonight(2, new Moment("Bez godziny", null, null, null),
                new Moment("Wejście pary", LocalTime.of(19, 0), null, null));
        EventPlanService.Tonight untimed = tonight(3, new Moment("Kiedyś", null, null, null));
        List<EventPlanService.Tonight> day = List.of(untimed, christening, wedding);

        assertThat(EventPlanService.choose(day, Instant.parse("2027-06-12T08:00:00Z")).orElseThrow().eventId())
                .as("10:00 — nothing started: the first to start").isEqualTo(1);
        assertThat(EventPlanService.choose(day, Instant.parse("2027-06-12T13:00:00Z")).orElseThrow().eventId())
                .as("15:00 — the christening").isEqualTo(1);
        assertThat(EventPlanService.choose(day, Instant.parse("2027-06-12T16:30:00Z")).orElseThrow().eventId())
                .as("18:30 — the wedding starts within the hour").isEqualTo(2);
        assertThat(EventPlanService.choose(day, Instant.parse("2027-06-12T23:00:00Z")).orElseThrow().eventId())
                .as("1:00 — still the wedding").isEqualTo(2);
        assertThat(EventPlanService.choose(List.of(untimed, tonight(4, new Moment("Też", null, null, null))),
                Instant.parse("2027-06-12T18:00:00Z")).orElseThrow().eventId()).as("no times at all: the first made").isEqualTo(3);
        assertThat(EventPlanService.choose(List.of(), Instant.now())).isEmpty();
    }

    @Test
    void aPlansVersion_isItsLastSave_toTheMicrosecond() {
        assertThat(new EventPlanService.Plan(List.of(), null, null, null, null).version()).isEmpty();
        assertThat(new EventPlanService.Plan(List.of(), null, null, Instant.parse("2027-06-12T21:00:00.123456789Z"), "dj").version())
                .isEqualTo(String.valueOf(Instant.parse("2027-06-12T21:00:00Z").getEpochSecond() * 1_000_000L + 123_456));
    }
}
