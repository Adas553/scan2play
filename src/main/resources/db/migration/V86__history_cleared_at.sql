-- V86 — "Wyczyść historię" and "Już leciało" (DeckSetService.playedLately): clearing the history deletes the played requests, so the
-- AI no longer knows what played; the night's set (deck_play) stays for the summary, but from the moment of the clear only the songs
-- played after it are a repeat. Kept here, not in memory: a restart in the evening must not bring the earlier songs back.
--   party_settings.history_cleared_at — the last "Wyczyść historię" (DjService.clearHistory); null: never. Read by DeckSetService only
--                                       (not mapped in the entity, as deck_set_kept).
-- Only adds a nullable column; no row is moved.
ALTER TABLE public.party_settings ADD COLUMN history_cleared_at timestamp with time zone;
