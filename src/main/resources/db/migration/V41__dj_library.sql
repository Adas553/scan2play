-- V41 — the DJ's library (plan Pro): the songs of the DJ's own collection, so the queue can mark each guest's request "✓ in your
-- library" or "✗ not in it". Uploaded from the settings as a rekordbox collection (XML) or a playlist (M3U / M3U8); an upload
-- replaces the whole library. Only each song's key is kept (SongNames.libraryKey: the name without the case, the accents, the
-- punctuation, a version in brackets and a guest artist) — no title as written, no path to a file. Goes with the party.
--   dj_library        — one row per party that has a library: how many tracks the file had, its format, when it was uploaded
--                       (the panel's ETag changes with it, so the marks follow a new upload)
--   dj_library_track  — one row per song key; the primary key is the queue's lookup (a party's few waiting songs at once)
CREATE TABLE public.dj_library (
    party_code  character varying(5)  NOT NULL,
    track_count integer               NOT NULL,
    format      character varying(10) NOT NULL,
    uploaded_at timestamptz           NOT NULL,
    CONSTRAINT dj_library_pkey PRIMARY KEY (party_code),
    CONSTRAINT fk_dj_library_party FOREIGN KEY (party_code) REFERENCES public.party_settings (party_code) ON DELETE CASCADE
);

CREATE TABLE public.dj_library_track (
    party_code character varying(5)   NOT NULL,
    match_key  character varying(200) NOT NULL,
    CONSTRAINT dj_library_track_pkey PRIMARY KEY (party_code, match_key),
    CONSTRAINT fk_dj_library_track_library FOREIGN KEY (party_code) REFERENCES public.dj_library (party_code) ON DELETE CASCADE
);
