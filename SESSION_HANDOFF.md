# Session Handoff — 2026-10-01

The current state only: the branch, what waits for the owner, what comes next. **The history of every session up to 2026-10-01**
(decisions, the owner's words, what was tried) is in `docs/history/session-handoff-2026-09.md` — read it only when a question
needs the background. Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`**, pushed up to `65609bf` (2026-10-01). Check with `git status -sb` and `git log --oneline -8`.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs`; logs need a
  GitHub login, but failed tests are written as public **annotations**: `.../check-runs/<id>/annotations`): three workflows —
  Unit tests, Browser tests, Database tests. All three green for `65609bf` (2026-10-01). After the QR print (dashboard template
  changed) the 56 browser scenarios were run again locally: green.
- **Printing the QR code — tried by the owner ("działa"), COMMITTED and PUSHED (2026-10-01)** (the owner's wish, 2026-10-01: "zrób oba warianty, dwujęzyczna"): a "🖨" link
  under the dashboard's QR code opens `GET /dj/qr-print` in a new tab — `layout=poster` (one A4 poster, default) or `cards` (eight
  93 × 68 mm cards to cut out); the printed text is Polish **and** English (written in `qr-print.html`, each part with its own
  `lang`), the bar above it (not printed) follows the DJ's language; QR 1000 px. `DjDashboardController.qrPrint`, `ViewAttributes`,
  `qr-print.html`, `css/qr-print.css`, `js/qr-print.js` (no inline handler), `dashboard.html` (the link), 7 message keys PL/EN
  (escapes by a script), `QrPrintPageTest` (3; it writes `target/qr-print/*.html`). 516 unit tests green; both layouts looked at in
  a browser. Note: locally the QR code holds the LAN address
  (`http://192.168.100.184:…`, from `SCAN2PLAY_GUEST_URL` in IntelliJ) — a code printed from the local app works only on that Wi-Fi.

## The second half of 2026-10-01 (committed and pushed)

- **CI fixes** (`b7cc990`): `-Dtest=!…` pulls `*IT` into surefire — the workflow and `CLAUDE.md` now exclude `!*IT`, and
  `PostgresIntegrationTest` skips itself outside failsafe (`scan2play.it`); `MigrationIT`'s plan test asks with `enable_sort = off`
  (it failed on GitHub when it ran after other tests). Failed tests become annotations (`.github/scripts/annotate-test-failures.py`).
- **The resume of an interrupted track** (`e4b002f`; the owner's report and choice "A", tried: "działa bardzo dobrze"): the page
  that plays notes on `pagehide` the track it interrupts (`sessionStorage` `scan2play.interruptedTrack` = `{key, paused}`); after a
  reload only that track comes back (newest of `recent-tracks`, within 10 min) — with Auto-Pilot off once it is switched on or
  "resume" is pressed; a track the DJ had paused is held until "resume" even with Auto-Pilot on; a track that ended by itself is
  not played again. Not covered by a scenario: "resume" on the phone while the computer is held.
- **Review 3.2** (`063464f`): the track in the player is one object `current` in `youtube-autopilot.js`, created only by
  `startTrack`, phase LOADING → RUNNING → OVER; the old flags are functions of it.
- **Review 3.3** (`ef53c3b`, tried by the owner — no console errors, the standalone history page works): `dashboard.js` is ES
  modules in `js/dashboard/` (`main.js` → `list-tools.js`, `fallback-queue.js`, `forms.js`, `tabs.js`, `polling.js`; `common.js`;
  `events.js` = the 7 `s2p:*` events); `youtube-autopilot.js` is a module and talks only through those events; `wake-lock.js`
  listens to `s2p:playback-mode`; no inline `onclick`/`onchange` on the dashboard; `history.html` loads `list-tools.js` alone.
  The Spotify dashboard was not tried (the owner: Spotify's API allows only a few users, left as it is for now).
- 56 browser scenarios, 513 unit tests (516 with the QR print), 24 database tests.

## Next (the owner picks)

1. From `REVIEW.md`: **5.1 CSP** — easier now (no inline handlers on the dashboard; still inline: the scripts in
   `fragments/components.html` and `index.html`, inline `style` attributes, Bootstrap from a CDN); **6.2** tests of
   `SongEvaluationService` / `GuestController` / `YouTubeMusicProvider` / Spotify; **2.3** single instance (Spring Session JDBC would
   keep DJs logged in across a deploy); the small N items of its table (1.6, 1.7, 1.8, 1.9, 2.4, 2.5, 2.6, 3.4, 3.5, 4.6, 4.7, 5.3,
   5.4, 5.5, 6.3, 6.4, 7.2, 7.3).
2. Seen in the owner's screenshot of the history page: a rejected request of 01.04.2026 has no song name (the row shows only "ANY
   ODRZUCONE" and the comment) — find out why the name is empty (an old row, or the AI answer without `songName` on a rejection).
3. `PROJECT_CONTEXT.md` (~1760 lines) — the other half of 7.1, a separate edit.

## The first half of 2026-10-01 (committed and pushed)

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
