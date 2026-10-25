-- V44 — a song the DJ (or the staff) added to the queue in the panel: a guest asked at the booth. It waits as a guest's request
-- would — a number, the guests' 👍, "Zagrane" —, but the AI never judged it and no guest typed it: the DJ's queue and history mark
-- it, and the evening summary does not count it as a guest's.
ALTER TABLE public.song_requests ADD COLUMN added_by_dj boolean NOT NULL DEFAULT false;
