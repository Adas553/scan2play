package com.scan2play;

import com.scan2play.entity.DjPlanEntity;
import com.scan2play.model.Plan;
import com.scan2play.model.PlanSource;
import com.scan2play.repository.DjPlanRepository;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DJ's plan (V35) on PostgreSQL: the entity reads and writes its row as the migration made it (an end of the plan in
 * {@code timestamptz}), the plans are not enforced in the application's own configuration (production today: nothing changes), and
 * "Usuń konto" deletes the row with the rest of the DJ's data.
 */
class DjPlanIT extends PostgresIntegrationTest {

    @Autowired DjPlanRepository plans;
    @Autowired PlanService planService;
    @Autowired AccountDeletionService accountDeletion;

    @Test
    void aPlan_isKeptAsWritten_andGoesWithTheAccount() {
        Instant end = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        plans.save(DjPlanEntity.builder().ownerId("plan-it-dj").plan(Plan.PRO).source(PlanSource.TRIAL).validUntil(end)
                .updatedAt(Instant.now()).build());

        DjPlanEntity read = plans.findById("plan-it-dj").orElseThrow();
        assertThat(read.getPlan()).isEqualTo(Plan.PRO);
        assertThat(read.getSource()).isEqualTo(PlanSource.TRIAL);
        assertThat(read.getValidUntil()).isEqualTo(end);
        assertThat(read.planAt(end.plusSeconds(1))).isEqualTo(Plan.FREE);

        accountDeletion.deleteAllUserData("plan-it-dj");
        assertThat(plans.existsById("plan-it-dj")).isFalse();
    }

    @Test
    void thePlans_areNotEnforced_byDefault() {
        assertThat(planService.isEnforced()).isFalse();
        assertThat(planService.planOf("anyone")).isEqualTo(Plan.PRO);
    }
}
