-- V18 — Spotify is gone (2026-10-04, the owner: the product is the requests-only party; Spotify allowed only a few test accounts
-- and forbids this use). A Spotify party belonged to a DJ who logged in with Spotify — a login that no longer exists, so such a
-- DJ could never open or delete it: the party goes, with its requests, its background playlist, its play log and its DJ's
-- feedback. Then the Spotify tokens' columns, and SPOTIFY leaves the check of the party's kind.
DELETE FROM public.song_requests WHERE party_code IN (SELECT party_code FROM public.party_settings WHERE active_provider = 'SPOTIFY');
DELETE FROM public.fallback_track WHERE party_code IN (SELECT party_code FROM public.party_settings WHERE active_provider = 'SPOTIFY');
DELETE FROM public.fallback_play WHERE party_code IN (SELECT party_code FROM public.party_settings WHERE active_provider = 'SPOTIFY');
DELETE FROM public.feedback WHERE owner_id IN (SELECT owner_id FROM public.party_settings WHERE active_provider = 'SPOTIFY');
DELETE FROM public.party_settings WHERE active_provider = 'SPOTIFY';

ALTER TABLE public.party_settings DROP COLUMN spotify_access_token;
ALTER TABLE public.party_settings DROP COLUMN spotify_refresh_token;
ALTER TABLE public.party_settings DROP COLUMN spotify_token_expires_at;

ALTER TABLE public.party_settings DROP CONSTRAINT IF EXISTS party_settings_active_provider_check;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_active_provider_check
    CHECK (((active_provider)::text = ANY ((ARRAY['YOUTUBE'::character varying, 'REQUESTS_ONLY'::character varying])::text[])));
