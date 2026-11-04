-- V60 — the DJ's library: one word's two spellings make one key (LibraryKey.SPELLINGS) — "ing" is "in" ("Livin' On A Prayer" and
-- "Living on a Prayer", "Stayin' Alive" and "Staying Alive"), "number" is "no" ("Mambo No. 5" and "Mambo Number 5"). The new keys are
-- made so; the keys saved before are changed here the same way, in the same order, so that the libraries uploaded before match as the
-- new ones do (a title's key has no spaces: the change needs none). Two tracks of a party whose keys become one (the same artists,
-- "Sing" and "Sin") are one track then: the one with the smaller key of before is kept, with its details.
DELETE FROM public.dj_library_track AS track
    USING public.dj_library_track AS kept
    WHERE track.party_code = kept.party_code
      AND track.artists = kept.artists
      AND track.swapped = kept.swapped
      AND replace(replace(track.title_key, 'ing', 'in'), 'number', 'no')
          = replace(replace(kept.title_key, 'ing', 'in'), 'number', 'no')
      AND track.title_key > kept.title_key;

UPDATE public.dj_library_track
    SET title_key = replace(replace(title_key, 'ing', 'in'), 'number', 'no')
    WHERE title_key LIKE '%ing%' OR title_key LIKE '%number%';
