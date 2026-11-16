# The artists of the guests' song suggestions — what the list is, its review, the learning to come

The guest's song field (`static/js/song-autocomplete.js`) asks Apple's iTunes Search API. iTunes matches whole words only: `shak`
finds "Shik Shak Shok", never Shakira; `podsia` finds nothing. A word after another is completed by asking for the words before it
("shakira da" → "Dai Dai"); a **single** word being typed has nothing before it — for it the script keeps its own list of artists,
`ARTISTS`.

## How the list is used

- A single word of at least 3 letters, still being typed (no space after it), that begins a word of an artist on the list and is
  shorter than it: `shak` → Shakira, `podsia` → Dawid Podsiadło, `beyo` → Beyoncé, `gun` → Guns N' Roses. A word with a slash or
  an apostrophe counts by its letters, and a short word of a name with its neighbour: `ac/d` → AC/DC, `d'ag` → Gigi D'Agostino.
- **Only an addition.** iTunes is asked for the word as before and for the artist at the same time (the list waits for the slower of
  the two). The artist's first 5 songs come first, then the songs of iTunes's own answer with a word (artist, title, album) starting
  so, then the rest of the artist's — what iTunes found for the word is never pushed out of the list. The rest of its answer is the
  hits it pads a half word with (`zene` → "All I Want for Christmas Is You") and is left out. A word the list does not know works
  exactly as it did without the list.
- Not completed: a word that already is a whole word of an artist on the list (`queen`, `kombi`, `dawid` — iTunes finds those
  itself; the typed side of an alias is the exception, below), words of "the", "band", "orkiestra", "dj" and the like
  (`ARTIST_SKIPPED`).
- The first artist that fits wins, so the order of the list matters: the likelier at a party comes earlier (`sha` → Shakira, not
  Shazza; `jus` → Justin Bieber, not Justyna Steczkowska; `que` → Queen, not Quebonafide).
- `"Zenek Martyniuk=Zenon Martyniuk"`: what guests type, then the name iTunes gives the artist — the artist is asked by that name, and
  only the songs with every word of it in their artist are kept. A typed word iTunes does not know as theirs (`ALIAS_WORDS`: zenek →
  zenon, roksana → roxie, gasowski → gassowski; not "kasia", which is Kasia Kowalska's in iTunes) is completed even when whole
  (`zenek` → Zenon Martyniuk), stands for theirs among the finished words (`zenek martyniuk` keeps Zenon Martyniuk's songs) and is
  asked as theirs among the words before the one being typed (`zenek m` asks "zenon").
- "✓ DJ ma" (V58) works as before: the library is asked about the final list, whatever brought a song into it. **The list never comes
  from the DJ's library** — a guest could otherwise learn the library letter by letter (V58: the library is never listed to a guest).
- Scenarios: `song-suggestions-artist-begun`, `-artist-begun-padding`, `-artist-joined`, `-alias`,
  `song-suggestions-library-artist-begun` (`src/test/browser/scenarios/`).

## The review — every few months (next: 2027-02)

New artists come, others stop being asked for. About every three months, or after a guest or a DJ says a name is not suggested:

1. **Check the list against iTunes:** `python tools/check_artists.py` (about 20 minutes, a 3 s pause after each artist: iTunes allows
   ~20 asks a minute, and after its refusal (429) the tool waits a minute; the cloud session's environment must allow
   `itunes.apple.com`). It prints:
   - `NONE <artist>` — iTunes gives no song of that artist for that name: a wrong spelling, an artist iTunes names otherwise (then an
     alias, `"what guests type=what iTunes calls them"`), or a name iTunes matches to others (the band Weekend: "Weekend" gives The
     Weeknd and Scooter) — then take it off the list;
   - `FEW <artist>: n of 25` — fewer than 3 of its songs: a name iTunes matches mostly to others ("Classic"). Completing to it shows
     little of theirs: take it off, or ask by a name iTunes gives fewer others;
   - the beginnings shared by several artists and which one wins (`--offline` prints only this, at once): reorder when the winner is
     the less likely at a party.
2. **Look for new names** — what is played at Polish parties now:
   - the guests' requests on Scan2Play (read only; the production database's copy, `docs/database-backup.md`, or the read-only
     inspection of a local copy — never write to the developer's `scan2play`):
     ```sql
     SELECT split_part(song_name, ' - ', 1) AS artist, count(*) AS requests
     FROM song_requests
     WHERE requested_at > now() - interval '6 months' AND decision = 'accepted' AND NOT added_by_dj AND song_name LIKE '% - %'
     GROUP BY 1 ORDER BY 2 DESC LIMIT 200;
     ```
     (`accepted`: the songs the AI let through — not what it refused); the artists asked for often and missing from the list are
     the candidates (a guest's text is free: check the spelling against
     iTunes);
   - the charts: Apple Music's Top 100 Poland, the Polish radio charts (RMF FM, Eska, Radio ZET), the year's wedding hits.
3. **Edit `ARTISTS`** in `song-autocomplete.js`: add, take off, reorder (each group keeps its kind: Polish dance and disco polo,
   Polish pop/rock/rap, Polish wedding classics, international now / dance / classics; at the end the artists that lose a shared
   beginning to a likelier one). A candidate is checked before it is added: at least 3 of its songs under the name asked. Keep it to a few hundred names — the list is part of the script every guest loads.
4. Run `python tools/check_artists.py` again (0 `NONE`, 0 `FEW`), the browser scenarios `song-suggestions-*`, and write the date of the
   review and the next one here.

| Review | What changed |
|---|---|
| 2026-11-16 | The list made: 280 artists, all found by `tools/check_artists.py` (the band Weekend left out: iTunes gives The Weeknd for it). |
| 2026-11-16 | 101 Polish artists added (disco polo and dance, wedding classics, the new pop and rap): 381 in all, 204 Polish. Left out, iTunes has none or one song under the name: Daj To Głośniej, Imperium, Universe, Kabaret Ani Mru Mru, Daria Marcinkowska; aliases: Gąsowski → "Wojciech Gąssowski", Kasia → "Katarzyna Sobczyk", Roksana → "Roxie Węgiel". Moved to the end, as others are asked for more with the same beginning: Michał Szpak ("mich"), White 2115, Malik Montana, Kordian, Andre and others. |

## The learning to come (not built)

The list is written by hand. Later — once real parties give enough requests — it can learn from them:

- **What:** an endpoint on our server (`GET /api/artists?prefix=shak`, public, cached) answering the artists that begin so, from the
  artists guests asked for across all parties (`song_requests.song_name`, the part before " - "), ranked by how often; the script asks
  it instead of — or before — the list, the list staying as the fallback for a new installation and when the endpoint is slow (the
  same short wait as "✓ DJ ma").
- **How:** a table of artists with a count (filled by a nightly job from the requests that the AI let through, or as a request is
  saved), a prefix index on the plain name (`lower`, without accents; `text_pattern_ops`), a bounded query (Top 10). Only songs that
  reached a DJ's queue count — not the refused, not the DJ's own additions — and an artist only after a few requests from different
  parties, so that one guest's typing never becomes a suggestion for others.
- **Not:** the DJ's library (it is the DJ's, never listed to a guest), the guest's own words beyond the artist.
- **When:** after a few dozen real parties; until then the hand-written list with its review above is enough.
