package com.scan2play.service;

import com.scan2play.entity.DjPlanEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.Plan;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.PlanSource;
import com.scan2play.repository.DjPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link PlanService} (V35): the switch, the organiser's plan, the party as its plan lets it work, the trial. */
class PlanServiceTest {

    private final DjPlanRepository rows = mock(DjPlanRepository.class);

    private PlanService plans(boolean enforced, String proOwners, int trialDays) {
        return new PlanService(rows, new ConcurrentMapCacheManager(PlanService.CACHE), enforced, proOwners, trialDays);
    }

    private static PartySettingsEntity savedOnPro() {
        return PartySettingsEntity.builder().partyCode("ABC12").ownerId("owner").active(true).vibeNote("wesele 40+").djName("DJ Koko")
                .hostBlocked("Akcent").hostWanted("Hej sokoły").hostToken("tok").instagramUrl("https://www.instagram.com/dj/")
                .facebookUrl("https://www.facebook.com/dj").tiktokUrl("https://www.tiktok.com/@dj").tipUrl("https://buycoffee.to/dj")
                .commentStyle(CommentStyle.SARCASTIC).requestLimit(5).cooldownMinutes(10).duplicateCheckWindow(40).build();
    }

    private void row(String owner, Plan plan, Instant validUntil) {
        when(rows.findById(owner)).thenReturn(Optional.of(DjPlanEntity.builder().ownerId(owner).plan(plan).source(PlanSource.PAID)
                .validUntil(validUntil).updatedAt(Instant.now()).build()));
    }

    @Test
    void notEnforced_everyPartyHasEverything_andNoPlanIsRead() {
        PlanService plans = plans(false, "", 30);
        PartySettingsEntity party = savedOnPro();

        assertThat(plans.planOf(party)).isEqualTo(Plan.PRO);
        assertThat(plans.asPlanned(party)).isSameAs(party);
        assertThat(plans.require(party, PlanFeature.HOST_LISTS)).isSameAs(party);
        assertThat(plans.staffPlaces(party)).isEqualTo(10);
        assertThat(plans.view(party, true).pro("HOST_LISTS")).as("no page says Pro").isFalse();
        plans.startTrial("owner");
        verifyNoInteractions(rows);
    }

    @Test
    void enforced_anOrganiserWithoutARow_isFree() {
        when(rows.findById(anyString())).thenReturn(Optional.empty());
        PlanService plans = plans(true, "", 0);

        assertThat(plans.planOf("owner")).isEqualTo(Plan.FREE);
        assertThat(plans.staffPlaces(savedOnPro())).isEqualTo(1);
        assertThatThrownBy(() -> plans.require(savedOnPro(), PlanFeature.SUMMARY)).isInstanceOf(PlanRequiredException.class)
                .extracting("feature").isEqualTo(PlanFeature.SUMMARY);
    }

    @Test
    void enforced_aRowCounts_untilItsEnd() {
        row("paid", Plan.PRO, Instant.now().plus(Duration.ofDays(3)));
        row("ended", Plan.PRO, Instant.now().minusSeconds(1));
        row("gift", Plan.PRO, null);
        PlanService plans = plans(true, "", 0);

        assertThat(plans.planOf("paid")).isEqualTo(Plan.PRO);
        assertThat(plans.planOf("ended")).isEqualTo(Plan.FREE);
        assertThat(plans.planOf("gift")).as("no end: for ever").isEqualTo(Plan.PRO);
    }

    @Test
    void enforced_theOwnersOfTheConfiguration_arePro_withoutARow() {
        PlanService plans = plans(true, " me , tester ", 0);

        assertThat(plans.planOf("me")).isEqualTo(Plan.PRO);
        assertThat(plans.planOf("tester")).isEqualTo(Plan.PRO);
        verify(rows, never()).findById(anyString());
    }

    @Test
    void enforced_thePlanIsReadOnce_thenFromTheCache() {
        when(rows.findById("owner")).thenReturn(Optional.empty());
        PlanService plans = plans(true, "", 0);

        plans.planOf("owner");
        plans.planOf("owner");
        verify(rows, times(1)).findById("owner");
    }

    /** Leaving Pro deletes nothing: the copy for the guests and the AI has none of it, the party itself keeps all of it. */
    @Test
    void enforced_aFreeParty_worksWithoutWhatTheProPlanHas_andKeepsItSaved() {
        when(rows.findById("owner")).thenReturn(Optional.empty());
        PlanService plans = plans(true, "", 0);
        PartySettingsEntity party = savedOnPro();

        PartySettingsEntity planned = plans.asPlanned(party);

        assertThat(planned).isNotSameAs(party);
        assertThat(planned.getHostBlocked()).isNull();
        assertThat(planned.getHostWanted()).isNull();
        assertThat(planned.getHostToken()).isNull();
        assertThat(planned.getInstagramUrl()).isNull();
        assertThat(planned.getFacebookUrl()).isNull();
        assertThat(planned.getTiktokUrl()).isNull();
        assertThat(planned.getTipUrl()).isNull();
        assertThat(planned.getCommentStyle()).isEqualTo(CommentStyle.CLASSIC);
        assertThat(planned.getRequestLimit()).isEqualTo(2);
        assertThat(planned.getCooldownMinutes()).isEqualTo(3);
        assertThat(planned.getDuplicateCheckWindow()).isEqualTo(15);
        assertThat(planned.getVibeNote()).as("everyone's").isEqualTo("wesele 40+");
        assertThat(planned.getDjName()).isEqualTo("DJ Koko");
        assertThat(planned.isActive()).isTrue();

        assertThat(party.getHostWanted()).as("the party itself keeps it").isEqualTo("Hej sokoły");
        assertThat(party.getTipUrl()).isEqualTo("https://buycoffee.to/dj");
        assertThat(party.getRequestLimit()).isEqualTo(5);
    }

    @Test
    void enforced_aProParty_isAsItIs() {
        row("owner", Plan.PRO, null);
        PartySettingsEntity party = savedOnPro();
        assertThat(plans(true, "", 0).asPlanned(party)).isSameAs(party);
    }

    @Test
    void theView_offersProOnlyToTheOrganiser_ofAPartyWithoutIt() {
        when(rows.findById("owner")).thenReturn(Optional.empty());
        PlanService plans = plans(true, "", 0);

        assertThat(plans.view(savedOnPro(), true).pro("TIPS")).isTrue();
        assertThat(plans.view(savedOnPro(), true).allows("TIPS")).isFalse();
        assertThat(plans.view(savedOnPro(), false).pro("TIPS")).as("the staff: hidden").isFalse();
    }

    @Test
    void theTrial_isWritten_onlyWhileEnforced_withDays_andOnce() {
        when(rows.existsById("new")).thenReturn(false);
        when(rows.existsById("known")).thenReturn(true);

        plans(true, "", 0).startTrial("new");
        plans(false, "", 30).startTrial("new");
        plans(true, "", 30).startTrial("known");
        verify(rows, never()).save(any());

        plans(true, "", 30).startTrial("new");
        verify(rows).save(org.mockito.ArgumentMatchers.argThat(row -> row.getOwnerId().equals("new") && row.getPlan() == Plan.PRO
                && row.getSource() == PlanSource.TRIAL && row.getValidUntil().isAfter(Instant.now().plus(Duration.ofDays(29)))));
        verify(rows).markTrialUsed(PlanService.trialKey("new"));
    }

    /**
     * A Google account that had a trial and deleted its account (its {@code dj_plan} row went with it) gets no second one: only the
     * key of the account (a SHA-256, {@code trial_used}) is left (the owner, 2026-10-15; {@code DjPlanIT}).
     */
    @Test
    void theTrial_isNotGivenAgain_toAnAccountThatHadOne() {
        when(rows.existsById("back")).thenReturn(false);
        when(rows.trialUsed(PlanService.trialKey("back"))).thenReturn(true);

        plans(true, "", 30).startTrial("back");

        verify(rows, never()).save(any());
        assertThat(PlanService.trialKey("back")).as("SHA-256 in hex").hasSize(64).doesNotContain("back");
    }

    /** The plan read into the cache ends when its row says, not up to 10 minutes later (the cache's own time). */
    @Test
    void enforced_aCachedPlan_endsWhenItsRowSays() throws InterruptedException {
        row("owner", Plan.PRO, Instant.now().plusMillis(300));
        PlanService plans = plans(true, "", 0);

        assertThat(plans.planOf("owner")).isEqualTo(Plan.PRO);
        Thread.sleep(500);
        assertThat(plans.planOf("owner")).isEqualTo(Plan.FREE);
        verify(rows, times(1)).findById("owner");   // from the cache: it knows the end
    }

    /**
     * The trial's row is in the cache's view only after the commit: evicted before it, a request reading the plan meanwhile put FREE
     * back for 10 minutes (the review of 2026-10-14).
     */
    @Test
    void theTrial_evictsTheCachedPlan_afterTheCommit() {
        when(rows.findById("new")).thenReturn(Optional.empty());
        PlanService plans = plans(true, "", 30);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            plans.startTrial("new");
            assertThat(plans.planOf("new")).as("read meanwhile (no row yet): FREE, cached").isEqualTo(Plan.FREE);
            row("new", Plan.PRO, Instant.now().plus(Duration.ofDays(30)));
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(plans.planOf("new")).as("after the commit: the trial").isEqualTo(Plan.PRO);
    }
}
