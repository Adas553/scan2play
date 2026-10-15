package com.scan2play.entity;

import com.scan2play.model.Plan;
import com.scan2play.model.PlanSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link DjPlanEntity#planAt}: a plan lasts until its end, not at it — the moment {@code validUntil} is Free already. */
class DjPlanEntityTest {

    @Test
    void aPlan_endsAtItsValidUntil() {
        Instant end = Instant.parse("2026-11-01T12:00:00Z");
        DjPlanEntity row = DjPlanEntity.builder().ownerId("dj").plan(Plan.PRO).source(PlanSource.TRIAL).validUntil(end).build();

        assertThat(row.planAt(end.minusNanos(1000))).isEqualTo(Plan.PRO);
        assertThat(row.planAt(end)).as("the end itself").isEqualTo(Plan.FREE);
        assertThat(DjPlanEntity.builder().ownerId("dj").plan(Plan.PRO).source(PlanSource.GIFT).build().planAt(end)).as("no end").isEqualTo(Plan.PRO);
    }
}
