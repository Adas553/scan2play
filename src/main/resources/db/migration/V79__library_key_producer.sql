-- V79 — the DJ's library: a producer after a dash is a version (LibraryKey.VERSION_AFTER_DASH), cut from the title's key as
-- "- Original Mix" is: "Toketeo - Prod. by Dj Honda x DJ Jerry" (Serato) is "toketeo", so the guest's "Bebeshito – Toketeo" is "✓" and
-- "Zagrane samo" marks it played. The new keys are made so; the keys saved before are cut here.
--
-- A key has no spaces, so the part to cut is found in the title as the file writes it (title_name, kept since V57: a library
-- uploaded before has none and stays as it is until uploaded again) — "- Prod. by …" after the last dash, the brackets taken out
-- first as the Java does — and its key cut from the end of title_key: made as SongNames.comparable makes one (no accents, lower
-- case, letters and digits) and spelled as LibraryKey.SPELLINGS. Only when the key ends with it: a name this cannot spell as the
-- Java does (letters outside a-z, a key cut to 200) is left as it was, never given another song's key.
--
-- A track whose key becomes one the party's library has already (its artists the same — the primary key) goes: the one there is
-- the song without the producer, the shorter name. Two of them with one new key: the one with the smaller key of before stays.
CREATE TEMPORARY TABLE library_producer ON COMMIT DROP AS
SELECT party_code, title_key, artists, left(title_key, length(title_key) - length(tail)) AS new_key
FROM (SELECT party_code, title_key, artists,
             replace(replace(
                 regexp_replace(lower(regexp_replace(normalize(translate(
                     (regexp_match(regexp_replace(regexp_replace(translate(title_name, '‐‑‒–—―−', '-------'),
                                                                 '\([^)]*\)|\[[^]]*\]', ' ', 'g'),
                                                  '\s+', ' ', 'g'),
                                   '\s-\s[^-]*\y(?:prod(?:uced\s+|\.\s*|\s+)by)\y[^-]*$', 'i'))[1],
                     'łŁ', 'lL'), NFD), '[̀-ͯ]', '', 'g')), '[^a-z0-9]', '', 'g'),
                 'ing', 'in'), 'number', 'no') AS tail
      FROM public.dj_library_track
      WHERE title_name ILIKE '%prod%') AS track
WHERE tail <> ''
  AND length(title_key) > length(tail)
  AND right(title_key, length(tail)) = tail;

DELETE FROM public.dj_library_track AS track
    USING library_producer AS fixed
    WHERE track.party_code = fixed.party_code
      AND track.title_key = fixed.title_key
      AND track.artists = fixed.artists
      AND (EXISTS (SELECT 1 FROM public.dj_library_track AS there
                   WHERE there.party_code = fixed.party_code
                     AND there.artists = fixed.artists
                     AND there.title_key = fixed.new_key)
           OR EXISTS (SELECT 1 FROM library_producer AS other
                      WHERE other.party_code = fixed.party_code
                        AND other.artists = fixed.artists
                        AND other.new_key = fixed.new_key
                        AND other.title_key < fixed.title_key));

UPDATE public.dj_library_track AS track
    SET title_key = fixed.new_key
    FROM library_producer AS fixed
    WHERE track.party_code = fixed.party_code
      AND track.title_key = fixed.title_key
      AND track.artists = fixed.artists;
