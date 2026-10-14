-- V35 — a DJ's plan (the owner, 2026-10-14: the app stays free, but some features may one day be "Pro"). Empty at first: no row is
-- Free, and while scan2play.plans.enforce is false nobody's plan is read at all — every party keeps every feature. One row per
-- organiser (the Google subject, as party_settings.owner_id); their staff work the organiser's party with the organiser's plan.
--   plan        — FREE / PRO (PlanService: PRO only while valid_until has not passed)
--   source      — how the plan came: TRIAL (a new DJ's trial), PAID (later: the payment provider), GIFT (given by hand)
--   valid_until — the end of the plan; null = no end
-- Deleted with the account (AccountDeletionService).
CREATE TABLE public.dj_plan (
    owner_id    character varying(255) NOT NULL,
    plan        character varying(20)  NOT NULL,
    source      character varying(20)  NOT NULL,
    valid_until timestamptz,
    updated_at  timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT dj_plan_pkey PRIMARY KEY (owner_id),
    CONSTRAINT dj_plan_plan_check CHECK (plan IN ('FREE', 'PRO')),
    CONSTRAINT dj_plan_source_check CHECK (source IN ('TRIAL', 'PAID', 'GIFT'))
);
