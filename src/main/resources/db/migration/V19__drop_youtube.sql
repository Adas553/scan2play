-- V19 — YouTube is gone (2026-10-04, the owner: the product is the requests-only party — "Twój program DJ-a"; YouTube's API
-- allows 100 searches a day shared by every party, and its terms limit playing to personal use). One kind of party is left.
-- The YouTube parties go with their requests and their DJ's feedback (all of them were the owner's own test parties: "można
-- usuwać"); a party with no kind at all (a row from before the kind was always set) counted as a YouTube party and goes too. Their
-- DJs log in with Google as before and get a new party. Then the player's tables — the background playlist, its play log, the
-- YouTube search cache and its daily budget — and the columns of the kind, Auto-Pilot and the background playlist.
DELETE FROM public.song_requests WHERE party_code IN (SELECT party_code FROM public.party_settings
                                                      WHERE active_provider IS DISTINCT FROM 'REQUESTS_ONLY');
DELETE FROM public.feedback WHERE owner_id IN (SELECT owner_id FROM public.party_settings
                                               WHERE active_provider IS DISTINCT FROM 'REQUESTS_ONLY');
DELETE FROM public.party_settings WHERE active_provider IS DISTINCT FROM 'REQUESTS_ONLY';

DROP TABLE public.fallback_play;
DROP TABLE public.fallback_track;
DROP TABLE public.youtube_cache;
DROP TABLE public.youtube_search_budget;

-- their check constraints go with them
ALTER TABLE public.party_settings DROP COLUMN active_provider;
ALTER TABLE public.party_settings DROP COLUMN playback_mode;
ALTER TABLE public.party_settings DROP COLUMN fallback_playlist_url;
ALTER TABLE public.party_settings DROP COLUMN fallback_shuffle;
