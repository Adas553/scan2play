-- V28 — every song in a party's queue gets a number, "#27": the guests see it beside the song and write it in the title of a tip,
-- the DJ finds the song by it and counts the tip ("💸"). Scan2Play never sees the money.

-- The party's own count of numbers given: a number is never given twice, also after the history is cleared.
ALTER TABLE public.party_settings ADD COLUMN request_counter integer NOT NULL DEFAULT 0;

ALTER TABLE public.song_requests ADD COLUMN request_number integer;
ALTER TABLE public.song_requests ADD COLUMN tips integer NOT NULL DEFAULT 0
    CONSTRAINT song_requests_tips_check CHECK (tips >= 0);

-- The requests so far that reached the queue (waiting, played, skipped or cleared by the DJ — not the ones the AI rejected) are
-- numbered in the order they were asked for, party by party, and each party's count goes on from its last number.
WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY party_code ORDER BY requested_at, id) AS n
    FROM public.song_requests
    WHERE decision IN ('accepted', 'played') OR skipped_at IS NOT NULL OR cleared_at IS NOT NULL
)
UPDATE public.song_requests s SET request_number = numbered.n FROM numbered WHERE s.id = numbered.id;

UPDATE public.party_settings p
SET request_counter = COALESCE((SELECT MAX(s.request_number) FROM public.song_requests s WHERE s.party_code = p.party_code), 0);

-- One number, one song of a party.
CREATE UNIQUE INDEX uk_song_requests_party_number ON public.song_requests (party_code, request_number)
    WHERE request_number IS NOT NULL;
