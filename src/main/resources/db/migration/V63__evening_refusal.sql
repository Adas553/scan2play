-- V63 — the guests' requests refused before the AI judged a song, for "Podsumowanie wieczoru": they are never saved as requests
-- (nothing for the DJ to act on), so the summary could not count them. A count per party, evening and reason — no song, no word of
-- the guest's:
--   HOSTS_BLOCKED  — a song on the hosts' "nie grać" list (V29), refused before the AI or after it named the song
--   SKIPPED_BY_DJ  — a song the DJ skipped within the last two hours, kept out of the queue
--   NOT_A_SONG     — the AI read a mood or a message, not a song, and asked the guest for a title
-- One upsert a refusal (EveningSummaryRepository.countRefusal). Kept 13 months as the evening's songs, gone with the party, following
-- a new party code.
CREATE TABLE public.evening_refusal (
    party_code character varying(5)  NOT NULL,
    evening    date                  NOT NULL,
    reason     character varying(16) NOT NULL,
    count      integer               NOT NULL,
    CONSTRAINT evening_refusal_pkey PRIMARY KEY (party_code, evening, reason),
    CONSTRAINT fk_evening_refusal_party FOREIGN KEY (party_code)
        REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT evening_refusal_reason_check CHECK (reason IN ('HOSTS_BLOCKED', 'SKIPPED_BY_DJ', 'NOT_A_SONG'))
);
-- the purge after 13 months
CREATE INDEX idx_evening_refusal_evening ON public.evening_refusal (evening);
