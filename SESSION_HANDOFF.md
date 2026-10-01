# Session Handoff — 2026-10-01

The current state only: the branch, what waits for the owner, what comes next. **The history of every session up to 2026-10-01**
(decisions, the owner's words, what was tried) is in `docs/history/session-handoff-2026-09.md` — read it only when a question
needs the background. Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`**, pushed up to the documentation commit after `fbc3f01` (2026-10-01). Check with `git status -sb` and
  `git log --oneline -8`, and the three workflows (below).
- **Committed and pushed (`fbc3f01`, 2026-10-01; tried by the owner: "działa") — the requests-only party, for wedding DJs** (the owner: "zrób to, później
  pokażę DJom"; they play from VirtualDJ / Serato / rekordbox, not YouTube):
  - **The kind `REQUESTS_ONLY`** (`MusicProviderType`, migration **`V13`** — the check constraint; the next start in IntelliJ applies
    it to the local `scan2play` database, no data touched). A third tile on the landing page, "Zbieraj prośby gości" →
    `/start/requests` → Google's login; the YouTube tile goes through `/start/youtube`. The choice is kept in the session and used
    once (`DjSessionHelper`): a new DJ gets a party of that kind; **a DJ who has a party gets the same party (code, QR) switched**
    between YouTube and requests-only — so the owner's own Google party switches when he picks the tile (log out first: `/` sends
    a logged-in DJ to the dashboard), and back with the YouTube tile.
  - **The dashboard of such a party:** a "Twój program DJ-a" badge with a line of help; each waiting request has "Oznacz jako
    zagrane", **"Pomiń"** (new `POST /dj/dashboard/dismiss`: the request leaves as rejected with the note "Skipped by the DJ ⏭",
    shown in the history's rejected ones) and **"🔍 Podejrzyj"** — a link to YouTube's search results (`RequestsOnlyMusicProvider`:
    no YouTube API call, nothing of the daily search budget; a line of lyrics is looked up by the guest's own words). No player,
    Auto-Pilot, background playlist, DJ pick; the forms reload the page (as at a Spotify party). The guest page has no YouTube badge;
    "Teraz gra" does not show (nothing is known about what the DJ plays). The AI, the limits, the QR print — as for any party.
  - Server-side Auto-Pilot (`handleAutoQueue`) only for Spotify: a party switched from YouTube with Auto-Pilot on would otherwise
    have tried to queue to a player that does not exist.
  - Tests: `DjSessionHelperChosenKindTest` (6), `RequestsOnlyMusicProviderTest` (3), `SmokeTest` +2 (the tiles; `/start` is
    public, keeps the choice, goes to Google), `DashboardPageRenderTest` +1 (writes `dashboard-requests.html`), `DjServiceTest` +2
    (skip), `SongEvaluationServiceTest` +2 — **red on the old service**; `MigrationIT` +1 — **red without V13**; browser scenario
    `requests-only-dashboard` (the dashboard's modules without the player: the poll, the lists, no lease / next-track) — red when
    the player's script is put back on the page. 563 unit tests, 28 database tests, 68 browser scenarios — green in copies.
  - **After the owner's first try (2026-10-01):** the footer's "YouTube API Services" line is gone from a requests-only party's
    pages (guest page, result, dashboard, history; the landing and legal pages keep it). **The History tab loads in place for
    every party** (`tabs.js`; it was a page of its own without the player — the owner: "bez odświeżania"); scenario
    `requests-only-history-in-place`, red before. **The AI down** (the owner saw a Gemini timeout, 2× 10 s): at a requests-only
    party the request goes to the DJ unchecked (accepted, the guest's words, "AI jest chwilowo niedostępne — …"), the guest sees
    "PRZEKAZANE" without the energy (`SongEvaluationService.withoutTheAi`, `DjResponse.KIND_UNCHECKED`, `result.html`); other
    parties refuse as before. The server limits stay (they protect the AI's cost and against spam). The history the owner saw is
    his YouTube party's (the same party, switched) — a new DJ starts empty; for showing it to DJs a separate Google account is
    cleaner. 565 unit tests, 69 browser scenarios.
  - **Then (the owner: "bez przeładowania"; "usunąć nastrój"):** the requests-only dashboard sends its forms in the background
    (`common.submitsInPlace`, `<body data-party-kind>`); "Oznacz jako zagrane" / "Pomiń" / a DJ pick fire `s2p:guest-queue-changed`
    and `polling.js` fetches the queue at once (one chain of polls, no second loop) — a YouTube party's rows leave at once too.
    Scenario `requests-only-skip-in-place` — red before, and red again with the event taken out (a mutation in the work copy).
    **Songs only** at a requests-only party: no song / mood tiles on the guest page (a hidden `requestMode=SONG`), the server
    evaluates every request as a song, a mood goes back with "Tutaj DJ przyjmuje konkretne piosenki — …"
    (`guest.error.song_only`); `guest-party.js` no longer assumes the tiles (scenario `guest-page-requests-only` — red before: a
    TypeError). `GuestControllerTest` +2, `GuestPageRenderTest` +1. 568 unit tests, 71 browser scenarios.
  - **The guest's limit (the owner's report: "117 s" after two songs a minute apart; his choice of both options):** the wait now
    runs from the guest's LAST request — up to `requestLimit` requests, then `cooldownMinutes` from the last, then the whole limit
    again (it was a sliding window: the wait ran from the oldest of the window). A request that came to nothing gives its place
    back (`GuestSessionService.giveBack`): a mood sent back to the form, or the AI not answering at a party that refuses then
    (`DjResponse.KIND_AI_UNAVAILABLE`); the server's own limits keep counting everything. `GuestSessionServiceTest` +4 (the two
    about the wait **red on the old counting**), `GuestControllerTest` +2. 574 unit tests.
  - **To try:** log out, pick "Zbieraj prośby gości", send a request from the phone, "Pomiń" / "Oznacz jako zagrane", "🔍
    Podejrzyj"; back to YouTube with its tile.
- **Earlier on 2026-10-01 — committed, pushed, CI green:** the review packages one to eight (`REVIEW.md`, "Status poprawek"; the
  CSP enforced in the browser tests and without `'unsafe-inline'`, 6.2, 7.2, the favicon, the YouTube player fixes, the QR print page
  on a phone) — the day's details in `docs/history/session-handoff-2026-10-01.md`, earlier days in `docs/history/session-handoff-2026-09.md`.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs`; logs need a
  GitHub login, but failed tests are written as public **annotations**: `.../check-runs/<id>/annotations`): three workflows —
  Unit tests, Browser tests, Database tests. All three green up to `30ccec2` (2026-10-01; `fbc3f01` — see the next session). Unit tests also check that the
  Copilot copy of `AGENTS.md` matches it.

## Next (the owner picks)

1. **Check the workflows of `fbc3f01`** (and its documentation commit): the Database tests run V13, the Browser tests 71 scenarios.
   Then the owner shows the requests-only party to DJs — with a separate Google account (the owner's own party has the YouTube history).
2. **Go-live** (the owner: no customers yet, so not now): start the paused Postgres on Railway, back it up, `pg_dump --schema-only`
   compared with `V1__baseline.sql`; `dev` → `main` (a fast-forward) together with the staged variables; watch the start log
   (Flyway V2..V13, Hibernate validation); Dependabot switched on in GitHub; `CSP_ENFORCE=true` after a few quiet days.
3. **Ideas for the requests-only party, to ask DJs about:** the DJ's library (an export from rekordbox / Serato) → "✓ you have
   it" beside each request; the queue on the phone as the main view; the guest's wait shown in minutes ("spróbuj za 3 min") rather
   than seconds; Spotify's dashboard forms in the background too (today only YouTube and requests-only — Spotify has no tests).
4. From `REVIEW.md`: **3.4** (a hidden window polls less — small gain with one DJ), 2.5 (an import inside `next-track`, only for a
   party older than 29 days), 6.3 (only together with a change of the queue), 4.7 / 5.3 (Spotify), JaCoCo, the rest of 7.3,
   Bootstrap from the app instead of the CDN.

## Waiting for the owner (not code)

- **The Auto-Pilot hint / IFrame-API message** — not built until the friend's case is reproduced (which state it was: Auto-Pilot
  off, the IFrame API blocked, another window holding the lease). The `silent-*` browser scenarios show today's behaviour.
- **The old `YOUTUBE_API_KEY`** (rotated 2026-09-29): delete it in Google Cloud Console and check the new one is restricted to the
  YouTube Data API v3. The disabled OAuth client secret `****IgiS` can be deleted; `****pfTe` is the one in use.
- **Production** (Railway paused): at the next go-live, the Flyway checklist of `PROJECT_CONTEXT.md` Section 10 first — the first
  deploy applies V2..V13 at once. `dev` → `main` only when the owner decides. `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` is set on
  Railway already.
- **Try on the phone:** the wake lock (Auto-Pilot on, the screen should not dim), ✕ on an "up next" row, Save with a private / wrong
  playlist link.
- **Spotify locally:** the redirect `https://dev.scan2play.com.pl/dj/spotify/callback` would have to be added in the Spotify
  Developer Dashboard.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
- **`D:\Users`**: an empty directory tree left by a mistaken path in an earlier session; safe to delete by hand.
- **Parked:** skipping a music video's intro with SponsorBlock (licence CC BY-NC-SA — ask its maintainer first if ever built;
  details in the history file).
