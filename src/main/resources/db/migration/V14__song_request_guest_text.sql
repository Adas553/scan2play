-- V14 — what the guest typed, as the guest typed it (one line, at most 150 characters — what the AI is given), beside the song the
-- AI made of it: the DJ checks the AI against the guest's own words. NULL for a DJ's pick and for requests from before V14.
ALTER TABLE public.song_requests ADD COLUMN guest_text varchar(150);
