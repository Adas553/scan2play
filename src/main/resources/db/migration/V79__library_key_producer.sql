-- V79 — the DJ's library: a producer after a dash is a version (LibraryKey.VERSION_AFTER_DASH), cut from the title's key as
-- "- Original Mix" is: "Toketeo - Prod. by Dj Honda x DJ Jerry" (Serato) is "toketeo", so the guest's "Bebeshito – Toketeo" is "✓" and
-- "Zagrane samo" marks it played. The new keys are made so; the keys saved before are made again here.
--
-- A key has no spaces, so it is made again from the title as the file writes it (title_name, kept since V57: a library uploaded
-- before has none and stays as it is until uploaded again), by the steps of LibraryKey.of and SongNames in their order: the dashes
-- one "-", the brackets and "feat. X" out, then the comparable key (no accents, lower case, letters and digits), LibraryKey.SPELLINGS,
-- cut to 200. A track changes only when its whole title made so is exactly the key saved beside it: that shows this SQL spells the
-- name as the Java did, so the title without the producer is spelled as the Java spells it now. Any other name (a letter or a space
-- the database reads otherwise, a key of another title) is left as it was — at worst it matches again only once uploaded again,
-- never under another song's key. "\s" of the Java is ASCII only: the class below, not the database's "\s".
--
-- A track whose new key the party's library has already (its artists the same — the primary key) goes: the one there is the song
-- without the producer. Of two with one new key the one with the shortest name stays, as the upload keeps a song's shortest title.
-- A track whose new key is the old key of another one that moves (a second "- Prod. by" in the title) stays as it is: deleting it
-- for a key that is about to be free would lose it.
CREATE FUNCTION pg_temp.library_title(title text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
SELECT btrim(regexp_replace(
           regexp_replace(' ' || regexp_replace(
               regexp_replace(regexp_replace(translate(
                   regexp_replace(title, '[ \t\n\r\f\v]*[[:cntrl:]]+[ \t\n\r\f\v]*', ' - ', 'g'),
                   '‐‑‒–—―−', '-------'), '-(?:[ \t\n\r\f\v]*-)+', '-', 'g'), '[ \t\n\r\f\v]+', ' ', 'g'),
               '\([^)]*\)|\[[^]]*\]', ' ', 'g'),
               '[ \t\n\r\f\v](?:feat\.?|ft\.?|featuring)[ \t\n\r\f\v][^-]*', ' ', 'gi'),
           '^[ \t\n\r\f\v]+|[ \t\n\r\f\v]+$', '', 'g'))
$$;

CREATE FUNCTION pg_temp.library_key(title text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
SELECT left(replace(replace(
           regexp_replace(lower(regexp_replace(normalize(translate(title, 'łŁ', 'lL'), NFD), '[̀-ͯ]', '', 'g')), '[^[:alnum:]]', '', 'g'),
           'ing', 'in'), 'number', 'no'), 200)
$$;

CREATE TEMPORARY TABLE library_producer ON COMMIT DROP AS
SELECT party_code, title_key, artists, title_name, pg_temp.library_key(without) AS new_key
FROM (SELECT party_code, title_key, artists, title_name, core,
             regexp_replace(core, '[ \t\n\r\f\v]-[ \t\n\r\f\v][^-]*\y(?:prod(?:uced[ \t\n\r\f\v]+|\.[ \t\n\r\f\v]*|[ \t\n\r\f\v]+)by)\y[^-]*$',
                            '', 'i') AS without
      FROM (SELECT party_code, title_key, artists, title_name, pg_temp.library_title(title_name) AS core
            FROM public.dj_library_track
            WHERE title_name ILIKE '%prod%') AS named) AS cut
WHERE without <> core
  AND pg_temp.library_key(core) = title_key;

DELETE FROM library_producer WHERE new_key = '' OR new_key = title_key;

CREATE INDEX ON library_producer (party_code, artists, title_key);
ANALYZE library_producer;

DELETE FROM library_producer AS chained
    WHERE EXISTS (SELECT 1 FROM library_producer AS moving
                  WHERE moving.party_code = chained.party_code
                    AND moving.artists = chained.artists
                    AND moving.title_key = chained.new_key);

DELETE FROM public.dj_library_track AS track
    USING (SELECT party_code, title_key, artists, new_key,
                  row_number() OVER (PARTITION BY party_code, artists, new_key
                                     ORDER BY length(title_name), title_key) AS place
           FROM library_producer) AS fixed
    WHERE track.party_code = fixed.party_code
      AND track.title_key = fixed.title_key
      AND track.artists = fixed.artists
      AND (fixed.place > 1
           OR EXISTS (SELECT 1 FROM public.dj_library_track AS there
                      WHERE there.party_code = fixed.party_code
                        AND there.artists = fixed.artists
                        AND there.title_key = fixed.new_key));

UPDATE public.dj_library_track AS track
    SET title_key = fixed.new_key
    FROM library_producer AS fixed
    WHERE track.party_code = fixed.party_code
      AND track.title_key = fixed.title_key
      AND track.artists = fixed.artists;

DROP FUNCTION pg_temp.library_key(text);
DROP FUNCTION pg_temp.library_title(text);
