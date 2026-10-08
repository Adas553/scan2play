-- V25 — when the DJ cleared the request with the whole queue ("Wyczyść kolejkę"). A clear keeps the AI's comment from now on, as a
-- skip does (V23); cleared_at alone says it was cleared (the history shows that in the page's language). The fixed English note the
-- clears wrote before in its place goes: its request time is the closest moment that is known, the AI's comment under it is lost.
ALTER TABLE public.song_requests ADD COLUMN cleared_at timestamptz;

UPDATE public.song_requests SET cleared_at = COALESCE(requested_at, now()), dj_comment = NULL
WHERE decision = 'rejected' AND dj_comment = 'Cleared by the DJ 🧹';
