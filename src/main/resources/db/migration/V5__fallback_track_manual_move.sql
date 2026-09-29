-- V5 — remembers which queued fallback tracks the DJ has moved by hand.
--
-- The DJ can reorder the "up next" list (Phase 3, step 2 in PROJECT_CONTEXT.md Section 14). A moved track is
-- flagged, so the dashboard can say "order changed by hand" and can ask before switching shuffle on or off would
-- throw those moves away. Every statement that gives the queued tracks a new order (import, shuffle, a new round of
-- the playlist, the shuffle switch) clears the flag again.
ALTER TABLE public.fallback_track ADD COLUMN manual_move boolean NOT NULL DEFAULT false;
