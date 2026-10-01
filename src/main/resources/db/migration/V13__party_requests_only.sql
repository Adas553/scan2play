-- V13 — a party of the kind REQUESTS_ONLY: the DJ plays from their own software, and Scan2Play only collects the guests'
-- requests (the AI filters them, the queue shows them). Nothing else changes: only the check on the provider column has to let
-- the new value in.
ALTER TABLE public.party_settings DROP CONSTRAINT IF EXISTS party_settings_active_provider_check;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_active_provider_check
    CHECK (((active_provider)::text = ANY ((ARRAY['SPOTIFY'::character varying, 'YOUTUBE'::character varying, 'REQUESTS_ONLY'::character varying])::text[])));
