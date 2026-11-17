# Review 2026-11-17 — branch `claude/awesome-sagan-hhc11y` (the guests' song suggestions: versions once, the finished words, a word completed to an artist)

Scope: the 4 commits `f8ae1ff`, `eff81c5`, `8966af0`, `9108158` against their merge base `32762d0`. 8 files, +478 / −23:
`static/js/song-autocomplete.js`, the scenarios `song-suggestions.js` and `song-suggestions-library.js`, `tools/check_artists.py`,
`docs/song-suggestions-artists.md`, `docs/ideas.md` (item 14), `docs/context/06-3-config-templates-static.md`, `SESSION_HANDOFF.md`.
**The branch is already on `main`:** Adas553/scan2play#272 was merged as `e4ff4d1` (`git diff origin/main...FETCH_HEAD` is empty).
Production runs this code, so the fixes below go in a new PR.

## Checks run (cloud session, a worktree of `9108158`, `tools/check.py`)

| Step | Result |
|---|---|
| `unit` | 1311 tests, 0 failed, OK |
| `browser` | 425 scenarios (the 4 new `song-suggestions-*` included), OK. No scenario covers P1–P6 |
| `audit -t guest` | 10 pages × 320 / 390 / 1280: no sideways scrolling, no console errors, no small targets. OK |
| `python tools/check_artists.py --offline` | 381 artists, none repeated, 130 shared beginnings (some in P7) |
| the logic in node, on real iTunes answers (the Polish store, `curl` through the proxy) | the branch's `bareTitle`, `onceEach`, `withTheWords`, `artistBegun`, `withTheArtist`, `finishedWords`, `wordBegun` taken from the file as they are, `fetchSuggestions` copied without the DJ's library. The cases below were run this way; the answers of 2026-11-16 22:50 UTC |

## Findings (most serious first)

### P1 — "zenek martyniuk" gives one song: the finished word "zenek" is not in iTunes's "Zenon Martyniuk" — **high (the likeliest artist at a Polish party)**
- `song-autocomplete.js:188-196` (`withTheWords`): a finished word must begin a word of the song's artist or title. iTunes names him
  "Zenon Martyniuk", so no song of his has "zenek". The alias `'Zenek Martyniuk=Zenon Martyniuk'` (`:60`) is used only by
  `artistBegun`, never by `withTheWords`.
- Measured on real answers. `zenek martyniuk ` and `zenek martyniuk`: **one** suggestion, "Classic, Marcin Miller & zenek martyniuk –
  Kiedy spadnie śnieg" (the one credit in lower case). Without the filter there are 8, among them Mandacik, LOVE and Tańcz with Zenon
  Martyniuk. One song with "zenek" is enough for `having.length > 0` (`:195`), and that removes everything else. Before the branch these
  queries gave 8.
- `zenek m`: the stem "zenek" is asked of iTunes as it is, so only Zenek Kupatasa and Zenek Grabowski come back.
- `zenek` (the whole word, no space): `artistBegun:209` returns null, because "zenek" is a whole word of the entry. The comment says
  "iTunes finds them", but for the typed side of an alias it does not: real answer, 8 × Zenek Kupatasa / Grabowski / Cytadela –
  "Zenek". So `zene` gives Zenon Martyniuk, and one letter later (`zenek`) he is gone.
- The same holds for `gasowski` → Gąssowski (iTunes: Piotr Gasowski, Nikodem Gąsowski). `roksana` works, because iTunes still names
  most of her songs "Roksana Węgiel". `kasia` cannot be mapped word by word (Kasia Kowalska, Kasia Cerekwicka).
- Fix: a word map from the aliases, `typed word → iTunes's word`, built once from `ARTISTS`. Leave out a typed word that is also a
  word of another artist's iTunes name, which drops "kasia". That gives zenek→zenon, gasowski→gassowski, roksana→roxie. Then:
  (a) in `withTheWords` a finished word is also met by its mapped word (checked in node: `zenek martyniuk ` → 8 songs, all with Zenon
  Martyniuk); (b) in `artistBegun` a word equal to an alias's typed word is completed (`zenek` → asks "Zenon Martyniuk"); (c) the
  stem asked for `zenek m` uses the mapped words ("zenon"). Scenarios: `zenek martyniuk ` with a Zenon song and a "zenek" song in the
  answer, both shown; `zenek` asks "Zenon Martyniuk". Both red on the branch.

### P2 — `withTheWords` ignores the album: a film's or musical's songs go ("grease you", "dirty dancing ti") — **medium-high**
- `song-autocomplete.js:191`: `its` holds only the artist and the title. iTunes matches the album (`collectionName`) too, and guests
  type a film's name followed by the song.
- Measured: `grease you` gives "Grease" by Frankie Valli, Bee Gees, Franck Pourcel and others. **"You're the One That I Want" (John
  Travolta & Olivia Newton-John) and "Hopelessly Devoted to You" are gone**, though without the filter they are first and second
  (album "Grease (The Original Soundtrack…)"). `dirty dancing ti` gives **one** song ("Glockenbach – Dirty Dancing"); without the
  filter there are 8, with "(I've Had) The Time of My Life" first. `mamma mia here`: the film's songs (When I Kissed the Teacher,
  Angel Eyes…) go too.
- Fix: `words(track.collectionName)` in `its`. Checked in node: `grease you` and `dirty dancing ti` give what they gave before the
  branch, and `sweet car` still has no "Calm Down" (its album has no "sweet"). Scenario: `grease you` with the soundtrack's song
  (`collectionName: 'Grease (Soundtrack)'`) and a "Grease" title: both shown. Red on the branch.

### P3 — completing to an artist: iTunes's own answer for a half word is mostly padding, and it pushes the artist's songs out — **medium**
- `song-autocomplete.js:223-229` (`withTheArtist`): the artist's first 5 songs, then *all* of `itsOwn`, then the rest, cut to 8. For a
  word iTunes does not know, its answer is unrelated hits (the problem that `withTheWords` solves for finished words).
- Measured: `zene` → 5 × Zenon Martyniuk, then **Mariah Carey – All I Want for Christmas Is You, AC/DC – Highway to Hell, AC/DC –
  Back In Black** (iTunes gave 25 songs of his). `danc` → Papa Dance × 5, then Regard – Ride It, Dua Lipa – New Rules, Calvin Harris
  – One Kiss. `shak` is fine because iTunes's own songs there do begin with "shak" ("Shik Shak Shok").
- Fix: from `itsOwn`, keep only the songs with a word (artist, title, album) that starts with the typed word. This is the same rule as
  `byTitle`/`byArtist` (`:346-350`). If none has such a word, `itsOwn` adds nothing. "Never pushed out" still holds for every song
  that has the typed word. Scenario: `zene` with an answer of `['Mariah Carey - All I Want…']` and 7 Zenon songs: 7 Zenon songs, no
  Mariah. Red on the branch.

### P4 — `bareTitle` joins songs that are not versions of each other — **low**
- `song-autocomplete.js:153-163`.
  - Every bracket goes, a part's number too: "Another Brick in the Wall (Part 1)" and "(Part 2)" become one song, whichever came first
    (same length). iTunes PL names Pink Floyd's "Pt. 2" without brackets, so this is not seen there, but "(Part 2)", "(cz. 2)" or
    "(Pt. 2)" exist elsewhere.
  - The " - " regex (`:161`) cuts at the first " - " if a version word comes *anywhere* after it: "Video - Killed the Radio Star" →
    "Video", "Ona - Live Forever" → "Ona". A different song of the same artist that is called "Video" or "Ona" would then be merged
    with it.
  - " – " (en dash) is not handled: "Teraz – Remix" stays apart from "Teraz". This is only a missed merge.
- Fix: keep a bracket that is a part (`/^(part|pt\.?|cz\.?|część)\s*\d+$/i` inside) in the key. Cut the " - " part only when its first
  or last word is a version word (`Remastered 2015`, `Davis Remix`, `Extended Mix`, `Live at Wembley`). Accept `[-–]`. Unit cases in a
  scenario (`song-suggestions-versions`): "(Part 1)" and "(Part 2)" both shown; "X - Killed the Radio Star" not merged with "X".

### P5 — the song is shown by its shortest title, which can be a remix — **low**
- `song-autocomplete.js:176`: the shortest `trackName` wins. When the original has a long subtitle, a remix is shorter. Real answer,
  `shakria w`: "Shakira – Waka Waka (This Time for Africa) **[K-Mix]**". The original is "Waka Waka (This Time for Africa) [The
  Official 2010 FIFA World Cup (TM) Song]", which is longer. The guest picks the label as shown, and the DJ gets a request for the
  K-Mix.
- Fix: prefer a title whose brackets and " - " part name no version (the regex's words), then the shortest. Scenario
  `song-suggestions-versions`: the K-Mix and the long original → the original. The existing scenario expects "[K-Mix]", so this changes
  its expectation.

### P6 — no back-off after iTunes refuses; and the asks grow for single words — **low (iTunes's ~20 asks a minute per IP)**
- `song-autocomplete.js:319`: a refused ask (`!response.ok`: 403/429 at the limit) returns `[]` and is not remembered, so every
  pause asks again. With the artist there are now two asks a pause for a single word (`:341-342`), one before: "sha" asks "sha" and
  "Shakira". The artist's answer is cached while the word grows, so "shak", "shaki" cost one ask each.
- The IP is often the venue's Wi-Fi, shared by every guest and the kiosk. A full limit then stops the suggestions for everyone, and
  the retries keep it full.
- Fix: after a refused ask, ask nothing for 30–60 s (one module-level timestamp; the suggestions close quietly as with `[]`).
  Scenario: an iTunes stub answering 429. After it the next pause asks nothing; after the pause (a shorter wait in the test, e.g. a
  constant overridden on `window` by the harness, or `t.sleep`) it asks again.

### P7 — the list: who wins a shared beginning, generic names, title words that complete to an artist — **low (the owner decides)**
- `song-autocomplete.js:58-116`, `python tools/check_artists.py --offline`:
  - Debatable winners at a Polish party: `que` → Quebonafide (Queen comes at `quee`); `hou` → Swedish House Mafia (Whitney Houston);
    `dee` → Alice Deejay (Deep Purple); `micha` → Michael Jackson, also while a guest types "michał" (Szpak, Bajor); `ros` → Rosalía
    (Guns N' Roses, Rosiewicz).
  - Words of titles complete to an artist and put five of their songs first while the word is typed: `danc` → Papa Dance ("Dance
    Monkey"), `lov` → T.Love, `hal` → Halina Frąckowiak ("Hallelujah"), `wonde` → Stevie Wonder ("Wonderwall"), `pie` → Piękni i
    Młodzi, `band` → Wanda i Banda ("band" is in `ARTIST_SKIPPED`, but "banda" is not). P3's fix lessens this: iTunes's own songs with
    the word stay. The order still puts the artist first.
  - A generic name brings few of its own songs: "Classic" → **1** of 25 (CLASS:y, MKTO – Classic and others fill the rest).
    `attribute=artistTerm` does not help (tried: the same answer). `check_artists.py:98` reports only artists with none.
  - "AC/DC" and "Gigi D'Agostino" cannot be completed: "ac/d" and "d'ag" are two words for `artistBegun:204`. A nit.
- Fix: the owner reorders where they disagree. In `check_artists.py`, report `FEW <artist> (n)` when fewer than 3 songs are found
  (the doc's own rule for a candidate, `docs/song-suggestions-artists.md:50`), and drop or rename such entries.

### P8 — the docs and the tool — **low**
- `docs/song-suggestions-artists.md:15`: "a word that already is a whole word of an artist … iTunes finds those itself". This is not
  true for an alias's typed word (P1); say so, or let it follow P1's fix.
- `docs/song-suggestions-artists.md:29`: "about 15 minutes". 381 artists × 3 s of pause (`check_artists.py:102`) plus iTunes's time is
  about 20 minutes, and longer after a 429 (60 s each).
- `docs/song-suggestions-artists.md:42`: the review's SQL counts refused requests too, though the section "The learning to come"
  counts only the songs that reached a queue. Add the decision (the approved ones), so that what the AI refused does not become a
  candidate.
- `check_artists.py:26`: `SKIPPED` is a copy of `ARTIST_SKIPPED`, kept the same by hand. Read it from the JS as `artists()` reads the
  list.

### P9 — the cost — **info**
- The script: 20.2 → 32.6 KB, gzipped 6.1 → 11.1 KB. It is versioned (`spring.web.resources.chain.strategy.fixed`) and cached, so
  this is acceptable.
- `artistBegun:209-213` runs `words()` (`normalize('NFD')` and two regexes) on ~760 strings at every pause. That is under a
  millisecond, but it could be precomputed once into `[{ words, name }]` and would read simpler.

## What is right

- Cancelling with three asks: one `AbortController`, and any `null` → `null` (`:343`). A late answer is dropped by
  `input.value.trim() !== query` (`:508`), and the `song-suggestions-late-answer` scenario still covers it (with `despacito`, because
  "abb" is now completed).
- "✓ DJ ma": the library is asked about the final ≤ 8 songs as shown (`withTheDjsFirst`), whatever brought them. The completion list is
  static, never from the library, so nothing about the library reaches a guest that did not before. `song-suggestions-library-artist-begun`
  checks the asked list.
- Polish letters, ł and accents (`plain`), apostrophes ("gun" → Guns N' Roses), `Beyoncé`, `Måneskin`, `Tiësto` all complete as
  expected. The artist filter keeps "feat." and "&" collaborations (`Łobuzy, Akcent & Zenon Martyniuk`).
- No change to the rendering, the combobox's ARIA or the CSP (the same host, `itunes.apple.com`). The kiosk, the wishes page and the
  DJ's "Dodaj" use the same code path.
- Comments say why, not history (AGENTS.md). One history-like phrase is in `docs/ideas.md` item 14 ("the owner, 2026-11-16"), which
  is allowed there.

## The fixes (2026-11-17, `claude/kind-knuth-7ryiki`)

The owner chose all of them. Each new or changed scenario was run red on the branch's code first (7 of 7 red, each for its own
reason), then green.

| Point | Fix | Scenario |
|---|---|---|
| P1 | `ALIAS_WORDS` built from `ARTISTS` (zenek → zenon, roksana → roxie, gasowski → gassowski; not "kasia"): met by theirs in `withTheWords`, a whole typed word completed by `artistBegun`, the stem asked as theirs (`asITunesNames`) | `song-suggestions-alias` |
| P2 | `wordsOfSong`: the artist, title and album, in `withTheWords` | `song-suggestions-album` |
| P3 | `withTheArtist` keeps only those of iTunes's own with a word starting so | `song-suggestions-artist-begun-padding` |
| P4 | `titleParts`: a part's number stays in the key, a " - " part goes only when its first or last word names a version, the en dash counts | `song-suggestions-versions-not-joined` |
| P5 | `onceEach` shows a title that names no version (feat. is none) before the shortest | `song-suggestions-versions` (its expectation: the original, not "[K-Mix]") |
| P6 | after 403/429 nothing asked for `ITUNES_PAUSE_MS` (60 s); the remembered answers still served | `song-suggestions-itunes-refused` |
| P7 | Quebonafide, Swedish House Mafia, Alice Deejay moved to the end (`que` → Queen, `hou` → Whitney Houston, `dee` → Deep Purple); a short word joined with its neighbour (`ac/d` → AC/DC, `d'ag` → Gigi D'Agostino); `check_artists.py` reports `FEW` (< 3 songs); title words completing to an artist are lessened by P3 ("danc" now shows "Dance Monkey" after Papa Dance's five) | `song-suggestions-artist-joined` |
| P8 | `docs/song-suggestions-artists.md`: the alias exception, ~20 min, `decision = 'accepted'` in the SQL, `FEW`; `check_artists.py` reads `ARTIST_SKIPPED` from the JS and mirrors the alias words and the joins | — |
| P9 | `ARTIST_ENTRIES` read once; `artistBegun` no longer normalizes the list at every pause | — |

Checked in node on the real iTunes answers of the review: `zenek martyniuk ` → 8 songs with Zenon Martyniuk; `zenek` → his 5,
then iTunes's own "zenek" songs; `zenek m` → his songs; `grease you` and `dirty dancing ti` as before the branch; `sweet car` without
"Calm Down"; `zene` without Mariah Carey; `que` → Queen; `ac/d` → AC/DC.

`python tools/check_artists.py` after the fixes (the whole list, iTunes PL, ~20 min): 379 of 381 found. `NONE` Duran Duran and Lionel
Richie: iTunes answered both with nothing once; asked again by hand, 24 and 25 songs, so the tool now asks an empty answer once
more. `FEW` (1 of 25): Classic, Perfect, Feel, Survivor — names iTunes matches mostly to others. Kept: after P3 completing to them
shows their song and then iTunes's own songs with the word, which is better than iTunes's padding for the half word. The doc says
so. Then `unit` 1311 OK, `browser` 431 OK, `audit -t guest` OK.
