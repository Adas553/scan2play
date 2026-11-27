-- V82 — "Zagrane samo" watched: when the current run of songs began, so that one song played to test the program (the card asks
-- for it before the party) is not taken for a set that has started and then stopped (DeckWatchService).
--   dj_device.play_run_from — the start of the first song of the run the newest song belongs to; a run ends after a gap of
--                             30 minutes without a song (DjDeviceService.RUN_GAP). Null until the program sends a song again.
-- Only adds a nullable column; no row is moved.
ALTER TABLE public.dj_device ADD COLUMN play_run_from timestamptz;
