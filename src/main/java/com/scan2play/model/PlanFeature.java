package com.scan2play.model;

/**
 * A feature of the Pro plan (V35, the owner 2026-10-14): what a party of an organiser without Pro does not have once the plans are
 * enforced ({@code scan2play.plans.enforce}). The core of a party — the QR code, the AI, the queue, the votes, the history, the vibe —
 * is everyone's and is not here. {@code PlanService} decides; the staff work an organiser's party with the organiser's plan.
 */
public enum PlanFeature {
    /** The hosts' lists and their link (V29): without Pro they are kept but not applied, and the link is a 404. */
    HOST_LISTS,
    /** The DJ's profiles on the guest page and the print (V24). */
    PROFILES,
    /** The tip link (V27) and counting tips (💸, V28). */
    TIPS,
    /** The AI's comment styles but "Klasyczne" (V22). */
    COMMENT_STYLES,
    /** The guests' limits set by the DJ; without Pro every party has the defaults (2 requests, 3 min, no repeats of the last 15). */
    GUEST_LIMITS,
    /** "Podsumowanie wieczoru" and its CSV. */
    SUMMARY,
    /**
     * The DJ's library (V41): the queue marks each request "✓", "?" or "✗" by the DJ's own collection. Without Pro the uploaded library
     * is kept but marks nothing; deleting it needs no plan.
     */
    LIBRARY
}
