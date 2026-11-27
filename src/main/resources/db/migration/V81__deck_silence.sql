-- V81 — "Zagrane samo" watched (docs/ideas.md item 21): the panel says per computer whether its program sees songs, and the DJ is
-- warned when it stopped seeing them during the party (DeckSilenceMonitor).
--   dj_device.last_play_at          — the start of the newest song the program sent (on the server's clock); never the song itself:
--                                     only "the program saw a song at 21:43"
--   dj_device.last_play_program     — what it read it from ("VirtualDJ", "Serato", "Spotify"); null from a program too old to say
--   party_settings.deck_silence_minutes — "Ostrzeż, gdy program nie widzi piosenek": after this many minutes without a song while the
--                                     party is open; 0 = never. 10, 20 (the default) or 30 offered.
-- Only adds columns (one with a default); no row is moved.
ALTER TABLE public.dj_device ADD COLUMN last_play_at timestamptz;
ALTER TABLE public.dj_device ADD COLUMN last_play_program character varying(30);
ALTER TABLE public.party_settings ADD COLUMN deck_silence_minutes smallint NOT NULL DEFAULT 20;
ALTER TABLE public.party_settings ADD CONSTRAINT ck_party_settings_deck_silence
    CHECK (deck_silence_minutes BETWEEN 0 AND 120);

-- the watch's look every minute: only the computers that saw a song in the last hours
CREATE INDEX idx_dj_device_last_play ON public.dj_device (last_play_at) WHERE last_play_at IS NOT NULL;
