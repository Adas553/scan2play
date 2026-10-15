-- V36 — the Google accounts that had a trial of Pro (the owner, 2026-10-15): "Usuń konto" deletes the DJ's dj_plan row with the rest
-- of their data, and the same Google account logging in again got a second trial. Only a key of the account is kept — the SHA-256 of
-- its Google subject in hex (PlanService.trialKey), no name, no address, nothing of a party — and it is not deleted with the account:
-- what it is for is to outlive it (the privacy policy says so).
--   owner_hash — encode(sha256(convert_to(owner_id, 'UTF8')), 'hex'), as Java computes it (DjPlanIT, TrialUsedMigrationIT)
--   used_at    — when the trial was given
CREATE TABLE public.trial_used (
    owner_hash character varying(64) NOT NULL,
    used_at    timestamptz           NOT NULL DEFAULT now(),
    CONSTRAINT trial_used_pkey PRIMARY KEY (owner_hash)
);

-- the trials given before this version
INSERT INTO public.trial_used (owner_hash, used_at)
SELECT encode(sha256(convert_to(owner_id, 'UTF8')), 'hex'), updated_at FROM public.dj_plan WHERE source = 'TRIAL'
ON CONFLICT DO NOTHING;
