-- V42 — the DJ's library matched by the title and the artists apart (LibraryKey): "✓" is the same title with an artist in common,
-- "? Podobny" the same title alone — the order of the artists, a "The", a guest artist or a version after a dash no longer make a
-- song "✗". One key per track was kept under V41 and the new ones cannot be made from it (no title as written is kept), so the
-- libraries uploaded under V41 go: the DJ uploads the file again ("Twoja biblioteka" says none is uploaded).
--   dj_library.playlist          — the rekordbox playlist the library is, by its folders ("Kluby / Piątek"); null: the whole
--                                  collection or an M3U
--   dj_library_track.title_key   — the title without a version (LibraryKey.title)
--   dj_library_track.artists     — the artists, sorted, joined by "+" (LibraryKey.artists; "" when the file names none)
--   the primary key (party, title, artists) is the queue's lookup: a party's waiting titles at once
DELETE FROM public.dj_library;

DROP TABLE public.dj_library_track;

CREATE TABLE public.dj_library_track (
    party_code character varying(5)   NOT NULL,
    title_key  character varying(200) NOT NULL,
    artists    character varying(200) NOT NULL,
    CONSTRAINT dj_library_track_pkey PRIMARY KEY (party_code, title_key, artists),
    CONSTRAINT fk_dj_library_track_library FOREIGN KEY (party_code) REFERENCES public.dj_library (party_code) ON DELETE CASCADE
);

ALTER TABLE public.dj_library ADD COLUMN playlist character varying(200);
