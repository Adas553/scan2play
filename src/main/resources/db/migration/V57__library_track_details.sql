-- V57 — the DJ's library keeps each song as the file writes it, beside its key: the queue shows the tempo and the key of a song the
-- DJ has, and a song can later be shown to the guests by its name. Matched by the keys as before (title_key, artists); these columns
-- are only shown. All null for a library uploaded before (its marks work as they did; uploaded again, it has them) and for what the
-- file does not say (an M3U: no tempo, no key, no genre, no year).
--   dj_library_track.artist_name — the artist as written ("Calvin Harris feat. Rihanna"); for an M3U the part of the name before " - "
--   dj_library_track.title_name  — the title as written, the shortest of the song's versions ("One More Time")
--   dj_library_track.bpm         — the tempo (rekordbox's AverageBpm, 128.00)
--   dj_library_track.tonality    — the key (rekordbox's Tonality, "5A", "Am")
--   dj_library_track.genre       — rekordbox's Genre
--   dj_library_track.year        — rekordbox's Year (its 0, none, is null)
--   dj_library.with_details      — uploaded with these columns: false for every library before, so the settings can ask a rekordbox
--                                  library to be uploaded again
ALTER TABLE public.dj_library_track
    ADD COLUMN artist_name character varying(200),
    ADD COLUMN title_name  character varying(200),
    ADD COLUMN bpm         numeric(5, 2),
    ADD COLUMN tonality    character varying(10),
    ADD COLUMN genre       character varying(100),
    ADD COLUMN year        smallint;

ALTER TABLE public.dj_library ADD COLUMN with_details boolean NOT NULL DEFAULT false;
