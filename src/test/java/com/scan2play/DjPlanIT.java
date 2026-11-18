package com.scan2play;

import com.scan2play.entity.DjAccountEntity;
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
    @Autowired com.scan2play.repository.DjAccountRepository accounts;
    @Autowired PlanService planService;
    @Autowired AccountDeletionService accountDeletion;
    @Autowired org.springframework.cache.CacheManager caches;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * "Usuń konto" and back with the same Google account: no second trial of Pro (the owner, 2026-10-15). What is left of the account
     * is its key in {@code trial_used} — a SHA-256 of the Google subject, the same in Java and in PostgreSQL (V36 fills it in SQL).
     */
    @Test
    void aDeletedAccount_getsNoSecondTrial() {
        String owner = "trial-it-" + System.nanoTime();
        PlanService enforced = new PlanService(plans, caches, true, "", 14);
        enforced.startTrial(owner);
        assertThat(plans.existsById(owner)).isTrue();

        accountDeletion.deleteAllUserData(owner);
        assertThat(plans.existsById(owner)).isFalse();
        enforced.startTrial(owner);

        assertThat(plans.existsById(owner)).as("the same Google account again: no second trial").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_used WHERE owner_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                Integer.class, owner)).as("the key, as PostgreSQL computes it").isOne();
    }

    /**
     * A password account (V72) deleted and made again with the same address — a new id — gets no second trial either: its trial is
     * remembered by its address (Gmail's dots not counting), not by its id.
     */
    @Test
    void aPasswordAccountMadeAgain_withTheSameAddress_getsNoSecondTrial() {
        PlanService enforced = new PlanService(plans, caches, true, "", 14);
        String stamp = String.valueOf(System.nanoTime());
        DjAccountEntity first = accounts.saveAndFlush(account("ola.k" + stamp + "@gmail.com"));
        enforced.startTrial(first.userId());
        assertThat(plans.existsById(first.userId())).isTrue();

        accountDeletion.deleteAllUserData(first.userId());
        DjAccountEntity again = accounts.saveAndFlush(account("olak" + stamp + "@gmail.com"));
        enforced.startTrial(again.userId());

        assertThat(again.getId()).isNotEqualTo(first.getId());
        assertThat(plans.existsById(again.userId())).as("the same address again: no second trial").isFalse();
        DjAccountEntity other = accounts.saveAndFlush(account("inna" + stamp + "@example.com"));
        enforced.startTrial(other.userId());
        assertThat(plans.existsById(other.userId())).as("another address: its trial").isTrue();
    }

    private static DjAccountEntity account(String email) {
        return DjAccountEntity.builder().email(email).passwordHash("{bcrypt}x").createdAt(java.time.Instant.now()).build();
    }

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
