package com.scan2play.service;

import com.scan2play.model.PlanFeature;
import lombok.Getter;
import org.springframework.security.access.AccessDeniedException;

/**
 * A feature of the Pro plan asked for at a party whose organiser has no Pro, while the plans are enforced (V35): a 403, as a
 * permission the person of the staff was not given ({@code DjSessionHelper.require}). The page shows no form for it anyway — this
 * is the server's check, not the look.
 */
@Getter
public class PlanRequiredException extends AccessDeniedException {

    private final PlanFeature feature;

    public PlanRequiredException(PlanFeature feature) {
        super("The Pro plan is needed for " + feature);
        this.feature = feature;
    }
}
