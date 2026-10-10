-- V31 — the AI's energy rating (1–10) of a request goes (the owner, 2026-10-10: nobody used it). The AI is no longer asked for it
-- (the prompt and its answer's schema), the queue, the history, the result page and the summary's CSV no longer show it.
ALTER TABLE public.song_requests DROP COLUMN energy_level;
