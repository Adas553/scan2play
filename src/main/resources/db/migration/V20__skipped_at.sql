-- V20 — when the DJ skipped a request ("Pomiń"): the same song is kept out of the queue for two hours from then, and the DJ can
-- put the request back ("Cofnij", "↩ Przywróć"). Null for every other request — also one cleared with the whole queue. The requests
-- skipped before this column existed get their request time, the closest moment that is known.
ALTER TABLE public.song_requests ADD COLUMN skipped_at timestamptz;

UPDATE public.song_requests SET skipped_at = requested_at
WHERE decision = 'rejected' AND dj_comment = 'Skipped by the DJ ⏭';
