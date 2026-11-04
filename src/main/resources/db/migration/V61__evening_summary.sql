-- V61 — "Podsumowanie wieczoru" that outlives the guests' requests: the summary of an evening was read from song_requests alone, so
-- it went with them — 30 days after the party (SongRequestRetentionService) and at once with "Wyczyść historię".
--   evening_song    — what the summary needs of a request, copied in the same statement that deletes the request (the 30 days'
--                     purge, "Wyczyść historię"): the song's name ("Artist - Title" as the AI made it; none for a request the AI
--                     rejected — that name is what the guest typed), when it was asked for and played, how it ended, its votes, the
--                     DJ's tips, how many dedications it had, whether the DJ added it or it came from the kiosk. Nothing a guest wrote
--                     (no guest_text, no dedication's words, no AI comment) and nothing of who asked. The summary reads an evening
--                     from here and from song_requests together (a request is in one of them, never in both). Kept 13 months
--                     (EveningSummaryLog.MAX_AGE_MONTHS), gone with the party (the account) and following a new party code.
--   evening_summary — one row per party and evening the DJ named or shared: the title the DJ typed ("Wesele Ani i Tomka") and the
--                     secret of the hosts' read-only link (/s/{token}, 128 bits) with the moment it stops working. Kept as long as
--                     the evening's songs.
CREATE TABLE public.evening_song (
    song_request_id bigint                 NOT NULL,
    party_code      character varying(5)   NOT NULL,
    song_name       character varying(255),
    requested_at    timestamptz            NOT NULL,
    played_at       timestamptz,
    status          character varying(16)  NOT NULL,
    votes           integer                NOT NULL,
    tips            integer                NOT NULL,
    dedications     integer                NOT NULL,
    added_by_dj     boolean                NOT NULL,
    via_kiosk       boolean                NOT NULL,
    CONSTRAINT evening_song_pkey PRIMARY KEY (song_request_id),
    CONSTRAINT fk_evening_song_party FOREIGN KEY (party_code)
        REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT evening_song_status_check CHECK (status IN ('PLAYED', 'WAITING', 'SKIPPED', 'CLEARED', 'REJECTED_BY_AI'))
);
-- one evening of a party (a range of requested_at), the evenings' list (a party's rows) and the purge after 13 months (requested_at)
CREATE INDEX idx_evening_song_party_time ON public.evening_song (party_code, requested_at);
CREATE INDEX idx_evening_song_time ON public.evening_song (requested_at);

CREATE TABLE public.evening_summary (
    id               bigserial              PRIMARY KEY,
    party_code       character varying(5)   NOT NULL,
    evening          date                   NOT NULL,
    title            character varying(60),
    share_token      character varying(32),
    share_expires_at timestamptz,
    updated_at       timestamptz            NOT NULL,
    CONSTRAINT uk_evening_summary_party_evening UNIQUE (party_code, evening),
    CONSTRAINT fk_evening_summary_party FOREIGN KEY (party_code)
        REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX uk_evening_summary_share_token ON public.evening_summary (share_token);
