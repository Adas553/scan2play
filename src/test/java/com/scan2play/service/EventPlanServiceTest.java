package com.scan2play.service;

import com.scan2play.service.EventPlanService.Moment;
import com.scan2play.service.EventPlanService.Moments;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** "Plan imprezy": the moments as a form sends them — empty rows out, a row without a name named, times read, the limits held. */
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
}
