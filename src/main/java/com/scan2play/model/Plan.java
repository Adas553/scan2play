package com.scan2play.model;

import java.util.EnumSet;
import java.util.Set;

/** A DJ's plan (V35): what their parties may do ({@link PlanFeature}) and how many people their staff may have. */
public enum Plan {
    FREE(1, EnumSet.noneOf(PlanFeature.class)),
    /** Everything — and what every party has while the plans are not enforced. */
    PRO(10, EnumSet.allOf(PlanFeature.class));

    /** The most people on a party's staff, with the invitations by e-mail waiting (V34). */
    private final int staffPlaces;
    private final Set<PlanFeature> features;

    Plan(int staffPlaces, Set<PlanFeature> features) {
        this.staffPlaces = staffPlaces;
        this.features = features;
    }

    public int staffPlaces() {
        return staffPlaces;
    }

    public boolean includes(PlanFeature feature) {
        return features.contains(feature);
    }
}
