-- V15 — how many guests asked for a waiting song: a request for a song that already waits in the queue adds a vote to it instead
-- of a row of its own (SongRequestCommandService). Every existing request is one guest's.
ALTER TABLE public.song_requests ADD COLUMN votes integer NOT NULL DEFAULT 1;
