package com.scan2play.model;

/** How a DJ's plan came (V35, {@code dj_plan.source}). */
public enum PlanSource {
    /** A new DJ's trial ({@code scan2play.plans.trial-days}). */
    TRIAL,
    /** Paid — written by the payment provider's notice, once there is one. */
    PAID,
    /** Given by hand (a tester, a partner). */
    GIFT
}
