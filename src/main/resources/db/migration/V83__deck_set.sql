-- V83 — "Zagrane samo": the night's set kept (docs/ideas.md item 21, 4). The DJ's program sends every song it reads, not only the
-- guests' requests; the server keeps them for the party, so that:
--   "Już leciało o 21:30" — a guest asking for a song among the DJ's last "Bez powtórek z ostatnich N" is told so before the AI,
--   "Podsumowanie wieczoru" lists the whole set, and the guests' "Co to było?" shows the last few songs.
--   deck_play                     — one song the DJ's program played: its start (the server's clock), the artist and title as the
--                                   program wrote them, their keys (LibraryKey, as dj_library_track's) and the program's name. No
--                                   guest's data. Kept 30 days (SongRequestRetentionService, as the requests), gone with the party.
--   party_settings.deck_set_kept  — "Zapisuj set wieczoru" in the program's card; on by default (the owner, 2026-11-27). Off: nothing
--                                   kept, and the party's kept set deleted (DeckSetService.setKept).
--   evening_refusal's reasons     — ALREADY_PLAYED: a request refused as "Już leciało", counted for the summary as the others.
-- Only adds (a table, a column with a default, a wider CHECK); no row is moved.
CREATE TABLE public.deck_play (
    id          bigserial                NOT NULL,
    party_code  character varying(5)     NOT NULL,
    started_at  timestamp with time zone NOT NULL,
    artist      character varying(200),
    title       character varying(200)   NOT NULL,
    title_key   character varying(200)   NOT NULL,
    artists_key character varying(200)   NOT NULL,
    program     character varying(30),
    CONSTRAINT deck_play_pkey PRIMARY KEY (id),
    CONSTRAINT fk_deck_play_party FOREIGN KEY (party_code)
        REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE
);
-- every read is one party's newest songs (DeckSetService: "Już leciało", "Co to było?", the evening, a song sent again)
CREATE INDEX idx_deck_play_party_started ON public.deck_play (party_code, started_at DESC);
-- the nightly purge of what is 30 days old, across all parties
CREATE INDEX idx_deck_play_started ON public.deck_play (started_at);

ALTER TABLE public.party_settings ADD COLUMN deck_set_kept boolean NOT NULL DEFAULT true;

ALTER TABLE public.evening_refusal DROP CONSTRAINT evening_refusal_reason_check;
ALTER TABLE public.evening_refusal ADD CONSTRAINT evening_refusal_reason_check
    CHECK (reason IN ('HOSTS_BLOCKED', 'SKIPPED_BY_DJ', 'NOT_A_SONG', 'ALREADY_PLAYED'));
