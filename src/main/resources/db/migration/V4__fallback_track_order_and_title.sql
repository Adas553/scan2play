-- V4 — a fixed play order and a title for the server-side fallback ("background music") tracks.
--
-- Until now the next background track was picked at the moment the player asked for it (a random row
-- when shuffle was on), so nobody — including the DJ — could know what comes next. play_order makes the
-- order explicit: the next track is the QUEUED one with the lowest play_order (ties: playlist_position).
-- It is assigned when the playlist is imported, when the playlist starts a new round, and when the DJ
-- switches shuffle on or off (Section 14 of PROJECT_CONTEXT.md).
--
-- title is shown to the DJ ("up next"). It comes from the same YouTube API call as the rest of the row and
-- is covered by the same 30-day retention (rows are purged by fetched_at). It is NULL for rows imported
-- before this migration and for single videos whose title could not be looked up.
ALTER TABLE public.fallback_track ADD COLUMN play_order integer;
ALTER TABLE public.fallback_track ADD COLUMN title character varying(255);

-- Existing rows: keep what the old behaviour promised. Parties with shuffle on get a random order,
-- everyone else plays in playlist order. (Random keys stay well below the int limit, so a track can later
-- be moved "to the end" with max + 1.)
UPDATE public.fallback_track t
SET play_order = CASE
    WHEN COALESCE((SELECT p.fallback_shuffle FROM public.party_settings p WHERE p.party_code = t.party_code), false)
        THEN CAST(floor(random() * 1000000000) AS integer)
    ELSE t.playlist_position
END;

ALTER TABLE public.fallback_track ALTER COLUMN play_order SET NOT NULL;
