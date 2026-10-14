package com.scan2play.service;

import com.scan2play.entity.DjPlanEntity;
import com.scan2play.model.Plan;
import com.scan2play.model.PlanSource;
import com.scan2play.repository.DjPlanRepository;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link PlanService}s for the tests: the plans not enforced (as in production today), or enforced with a Free / Pro organiser. */
public final class Plans {

    private Plans() {
    }

    /** {@code scan2play.plans.enforce=false}: every party has every feature, no page says "Pro". */
    public static PlanService off() {
        return new PlanService(mock(DjPlanRepository.class), new ConcurrentMapCacheManager(PlanService.CACHE), false, "", 0);
    }

    /** Enforced; every organiser Free (no row of dj_plan) but those named Pro. */
    public static PlanService enforced(String... proOwners) {
        DjPlanRepository rows = mock(DjPlanRepository.class);
        when(rows.findById(anyString())).thenReturn(Optional.empty());
        for (String owner : proOwners) {
            when(rows.findById(owner)).thenReturn(Optional.of(DjPlanEntity.builder().ownerId(owner).plan(Plan.PRO)
                    .source(PlanSource.GIFT).updatedAt(Instant.now()).build()));
        }
        return new PlanService(rows, new ConcurrentMapCacheManager(PlanService.CACHE), true, "", 0);
    }
}
