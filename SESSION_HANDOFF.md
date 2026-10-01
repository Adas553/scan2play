# Session Handoff — 2026-10-01

The current state only: the branch, what waits for the owner, what comes next. **The history of every session up to 2026-10-01**
(decisions, the owner's words, what was tried) is in `docs/history/session-handoff-2026-09.md` — read it only when a question
needs the background. Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`.** Pushed up to `d0ca3c7` (2026-10-01: the guest's song named by its video, the two request modes, the guest's view
  of the queue — tried by the owner). Check with `git status -sb` and `git log --oneline -5`.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs`): Unit tests
  and Browser tests green for every push up to `d0ca3c7` (checked 2026-10-01); look at the runs of the next push.
- **COMMITTED and PUSHED on the owner's "ok, commituj i pushuj" (2026-10-01; code, then the docs)** — the session of 2026-10-01 (below). 513 unit tests and 24 database tests green in a
  scratch copy; JS and templates untouched, so the 47 browser scenarios were not run again.

**The push of `cfef47e` was red twice, fixed in the commit after it** (the owner pasted the two logs — GitHub shows a log only
when signed in): Unit tests — `-Dtest=!…` replaces surefire's name patterns, so the 24 `*IT` ran there without a database (now
`!*IT` in the command, and `PostgresIntegrationTest` skips itself outside failsafe); Database tests — the plan test of `MigrationIT`
ran after other tests had filled the table and the planner chose another index plus a one-row sort (now it asks with
`enable_sort = off`, green in both class orders, still red without V9). Both workflows also annotate failed tests now
(`.github/scripts/annotate-test-failures.py`): annotations are public, logs are not.

## The session of 2026-10-01 (committed and pushed)

The owner's order: 6.1, then 1.5 / 1.4 / 2.1 on top of it, 7.1, then 1.2, 4.4, 4.5 and a durable YouTube search count. All done:

- **6.1 — tests on a real PostgreSQL.** `mvnw verify -Pit` (Maven profile `it`: failsafe runs `*IT`, surefire off). Base class
  `PostgresIntegrationTest`: creates and drops its own `s2p_it_*` database on the local server (no Docker here, none needed), runs the
  whole app on it (Flyway + Hibernate validation). `MigrationIT`, `FallbackQueueIT`, `FallbackQueueConcurrencyIT` (fails with
  "deadlock detected" if the advisory lock is removed — checked), `SongRequestRepositoryIT`, `YouTubeSearchBudgetIT`,
  `ApplicationSetupIT`. New workflow `.github/workflows/db-tests.yml` (`postgres:18`; Railway runs `postgres-ssl:18`, checked through
  the Railway connector) — **its first run is the next push: look at it.** `CLAUDE.md` now points to these tests instead of a
  throw-away test written anew each time.
- **1.5 — `V9__queue_indexes`:** `(party_code, playlist_id, status, play_order, playlist_position)` (the next track read in order, no
  sort) and `(party_code, fetched_at)`; three indexes dropped (two duplicates and the old `(party_code, status)`). The purge of
  `song_requests` keeps no index of its own (an earlier session measured the skip scan: 1.9 ms on 300 000 rows).
- **1.4 — an import is one `INSERT … SELECT FROM unnest(…) WITH ORDINALITY`** (`FallbackTrackRepository.insertTracks`), not up to
  500 INSERTs under the queue's lock. The IT counts the statements (< 10 for 300 tracks; red before).
- **2.1 — the list's version is one aggregate query** (`queueFingerprint`: md5 of the queued ids in play order + manual flags +
  skipped count, computed by PostgreSQL); no entity is read (IT, red before). `GET …/fallback-queue` reads the version before the list.
- **1.2 —** `PartySettingsQueryService.getSettings` hands out a copy of the cached entity; `updateSettings` evicts the entry after the
  commit (instead of `@CachePut`); the Spotify tokens are out of `toString`.
- **4.4 —** the async executor is bounded: 16 / 32 threads, queue 50 (`spring.task.execution.pool.*`, env `ASYNC_POOL_*`).
- **4.5 —** a YouTube search that found nothing is not asked again for 10 minutes (`YouTubeMusicProvider.notFound`); failures are
  still retried.
- **The YouTube search count is in the database** (`V10__youtube_search_budget`, one row per Google day, taken atomically) — a
  restart no longer gives the day's budget back.
- **7.1 —** this file was cut down; the old one is `docs/history/session-handoff-2026-09.md` word for word.

**What the owner should know before trying it:** the next start of the app in IntelliJ applies `V9` and `V10` to the local
`scan2play` database (Flyway, as with every migration; index changes and one new table, no data touched). Worth a look: a playlist
import, ⏭ / the "up next" list in two windows (the version), a guest request at a YouTube party.

**Files:** `pom.xml`, `CLAUDE.md`, `.github/workflows/db-tests.yml`, `V9__queue_indexes.sql`, `V10__youtube_search_budget.sql`,
`FallbackTrackRepository`, `FallbackTrackCommandService`, `FallbackQueueService`, `DjFallbackQueueController`, `PartySettingsEntity`,
`PartySettingsQueryService`, `PartySettingsCommandService`, `AppConfig`, `FallbackTrackEntity`, `SongRequestEntity`,
`YouTubeMusicProvider`, `YouTubeSearchBudget`, `application.properties`; tests: the six `*IT` + `PostgresIntegrationTest`,
`PartySettingsQueryServiceTest` (new), `PartySettingsCommandServiceTest`, `FallbackTrackCommandServiceTest`,
`FallbackQueueServiceTest`, `DjFallbackQueueControllerTest`, `YouTubeMusicProviderTest`, `DashboardPageRenderTest`; docs
`PROJECT_CONTEXT.md` (4.1, 5.4, 6.2, 7.3, 9, 10, 13), `REVIEW.md` (status), this file, `docs/history/`.

## Next

1. **Look at the first run of `db-tests.yml` on GitHub** (it ran for the first time with this push); the owner tries the changes in
   IntelliJ (the app applies V9 and V10 to the local database at its next start).
2. From `REVIEW.md`, each needing the owner's decision first: 3.2 / 3.3 (the player's state and how the two scripts talk), 5.1 CSP,
   6.2 tests of the Spotify classes, 2.3 single instance (Spring Session JDBC would keep DJs logged in across a deploy); the small N
   items of its table.
3. `PROJECT_CONTEXT.md` (~1750 lines) is the other half of 7.1: the description "as it is" could stay, the history of each decision
   move to short notes in `docs/` — a separate, larger edit.

## Waiting for the owner (not code)

- **The Auto-Pilot hint / IFrame-API message** — not built until the friend's case is reproduced (which state it was: Auto-Pilot
  off, the IFrame API blocked, another window holding the lease). The `silent-*` browser scenarios show today's behaviour.
- **The old `YOUTUBE_API_KEY`** (rotated 2026-09-29): delete it in Google Cloud Console and check the new one is restricted to the
  YouTube Data API v3. The disabled OAuth client secret `****IgiS` can be deleted; `****pfTe` is the one in use.
- **Production** (Railway paused): at the next go-live, the Flyway checklist of `PROJECT_CONTEXT.md` Section 10 first — the first
  deploy applies V2..V10 at once. `dev` → `main` only when the owner decides. `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` is set on
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
