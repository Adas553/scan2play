# Session Handoff — 2026-10-04

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`. The product is the requests-only party** ("Twój program DJ-a"; the owner's decision 2026-10-04: YouTube — 100 API
  searches a day shared by every party, its terms; Spotify — development mode, its policy). The full app is archived: tag
  `full-player-2026-10-04`, branch `archive/full-player` (both pushed), the clone `D:\Coding\scan2play-full` with its own database
  `scan2play_full` — leave them alone.
- **Stage 3 (YouTube goes) — packages 1 and 2 committed and pushed, CI green:** `5a1bf79` (the player and Auto-Pilot, browser side),
  `e44d00f` (the server side, one kind of party, **V19** — deletes every party that is not requests-only with its data, drops the
  player's tables and the kind's columns; the owner tried it locally: "działa"). Tests at `e44d00f`: 248 unit, 19 database,
  23 browser scenarios.
- **Package 3 (the last) — uncommitted, waiting for the owner's review:** the landing page's guide (step 1 "Załóż imprezę", step 4
  without Auto-Pilot and the background playlist), the privacy policy and the terms (PL / EN: no YouTube API, no playlist, no search
  cache; "🔍 Podejrzyj" is a plain link; the iTunes suggestions named), `SmokeTest` +2 (the guide and the legal pages, both languages),
  `AGENTS.md` + the Copilot copy (no YouTube Data API, no player), `CLAUDE.md`, `PROJECT_CONTEXT.md` (the current state only:
  651 → 470 lines; Sections 5.4 and 14 are pointers to the history — old migrations refer to them), `REVIEW.md` (6.3 and the other
  player / Spotify items moot), this file (the old one word for word in `docs/history/session-handoff-2026-10-04.md`). Tests in a
  copy: 250 unit (`SmokeTest` 14).
- **Found while writing the docs:** without a `messages_en` bundle, a browser asking for English gets the bundle of the JVM's own
  locale — Polish on a Polish machine (Railway's JVM is probably English). Not changed; noted in `PROJECT_CONTEXT.md` Section 13.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs?head_sha=…`;
  failed tests are public **annotations**: `.../check-runs/<id>/annotations`): Unit tests, Browser tests, Database tests. Green up
  to `e44d00f`. Unit tests also check that the Copilot copy of `AGENTS.md` matches it.

## Next (the owner picks)

1. **Show the requests-only party to DJs** and tell what they said. Questions (2026-10-01): how many requests per wedding and how
   many they do not have; is searching a pain at all; should a guest hear "the DJ does not have it" at once.
2. **Ideas, only if the DJs ask:** the DJ's library (an export from rekordbox / Serato) → "✓ you have it" beside each request.
   Decided against (2026-10-03): "play later" without rejecting; a sound / a count in the tab title on a new request; a "clear the
   history" button.
3. **Go-live** (the owner: no customers yet, so not now): the checklist of `PROJECT_CONTEXT.md` Section 10 — **V18 and V19 delete
   every party made in production before 2026-10-04** (April had only YouTube / Spotify parties): every DJ gets a new party with a
   new code, a printed QR code stops working. Then start the paused Postgres on Railway, back it up, `pg_dump --schema-only` compared
   with `V1__baseline.sql`; `dev` → `main` (a fast-forward); watch the start log (Flyway V2..V19, Hibernate validation); Dependabot
   switched on in GitHub; `CSP_ENFORCE=true` after a few quiet days.
4. From `REVIEW.md`: what is left is 2.3 (one instance) and 7.x (small tidy-ups).

## Waiting for the owner (not code)

- **YouTube API key:** delete the key **"Klucz API 2"** in Google Cloud Console and the variable **`YOUTUBE_API_KEY`** in IntelliJ's
  run configuration and on Railway (the app no longer reads it; `YOUTUBE_SEARCH_DAILY_BUDGET` too, if set).
- **Spotify:** the Spotify app in the Developer Dashboard and the variables `SPOTIFY_*` and `SPOTIFY_TOKEN_KEY` (IntelliJ, Railway)
  are no longer used — delete them.
- **The OAuth client "Klient internetowy 1"** shows a warning in Google Cloud Console (2026-10-03): probably the disabled secret
  `****IgiS` — delete it (`****pfTe` is the one in use).
- **`SCAN2PLAY_GUEST_URL` in IntelliJ** holds the computer's address in the local network (the QR code's link): it changes with
  the network (2026-10-03: 192.168.68.54, the run configuration said 192.168.100.184). Change it in Run → Edit Configurations.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
- **`D:\Users`**: an empty directory tree left by a mistaken path in an earlier session; safe to delete by hand.
