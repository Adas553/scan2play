-- V38 — a guest's dedication with a request ("dla Ani na 18-stkę"; an idea from Rekwest, 2026-10-16): a short message the DJ sees
-- by the song, never the other guests. One row per dedication, so a guest who asks for a song that already waits (a vote) adds
-- theirs too. Deleted with its request (ON DELETE CASCADE): the 30 days of SongRequestRetentionService, "Wyczyść historię" and the
-- account's deletion take the dedications with the requests.
CREATE TABLE public.song_request_dedications (
    id              bigserial    PRIMARY KEY,
    song_request_id bigint       NOT NULL REFERENCES public.song_requests (id) ON DELETE CASCADE,
    text            varchar(100) NOT NULL,
    -- false: the AI could not be asked, the dedication went to the DJ unchecked (the queue marks it)
    checked         boolean      NOT NULL,
    created_at      timestamptz  NOT NULL
);
-- the queue reads the newest few of each waiting song (SongRequestDedicationRepository.findNewest)
CREATE INDEX idx_dedication_request ON public.song_request_dedications (song_request_id, id);

-- How many dedications the song has: the queue's fingerprint (its ETag) counts it, so a dedication added to a waiting song with
-- the guest's own request (no new vote) still reaches the DJ's open panel; the queue reads the table only for songs that have one.
ALTER TABLE public.song_requests ADD COLUMN dedications integer NOT NULL DEFAULT 0;

-- Whether the party's guests may add one: on by default — every party there is too —; the DJ switches it in the card "Impreza".
ALTER TABLE public.party_settings ADD COLUMN dedications_on boolean NOT NULL DEFAULT true;
