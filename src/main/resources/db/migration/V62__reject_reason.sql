-- V62 — why the AI rejected a request, for "Podsumowanie wieczoru": DUPLICATE — the song played lately (it was on the list of the
-- recently played the AI is given, the DJ's duplicate window), OFF_VIBE — any other refusal (the AI judged it against the vibe and
-- the DJ's note). Set when the request is saved (SongEvaluationService), from what the code knows — the prompt is not changed. Null for
-- every request the AI let through and for the ones of before (the summary counts them apart, "bez powodu"). Kept with the evening's
-- songs (evening_song) as the rest of a deleted request.
ALTER TABLE public.song_requests ADD COLUMN reject_reason character varying(16);
ALTER TABLE public.song_requests ADD CONSTRAINT song_requests_reject_reason_check CHECK (reject_reason IN ('DUPLICATE', 'OFF_VIBE'));
ALTER TABLE public.evening_song ADD COLUMN reject_reason character varying(16);
ALTER TABLE public.evening_song ADD CONSTRAINT evening_song_reject_reason_check CHECK (reject_reason IN ('DUPLICATE', 'OFF_VIBE'));
