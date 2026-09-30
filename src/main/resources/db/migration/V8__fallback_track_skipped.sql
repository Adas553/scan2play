-- V8 — the DJ can skip a track of the playlist for the current round (status SKIPPED).
--
-- A skipped track is not queued any more, so it does not play in this round; when the playlist starts its next round
-- (FallbackTrackCommandService.startNewRound) the played and the skipped tracks go back in the queue. Nothing else
-- changes: only the check on the status column has to let the new value in.
ALTER TABLE public.fallback_track DROP CONSTRAINT IF EXISTS fallback_track_status_check;
ALTER TABLE public.fallback_track ADD CONSTRAINT fallback_track_status_check
    CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PLAYED'::character varying, 'CANCELLED'::character varying, 'SKIPPED'::character varying])::text[])));
