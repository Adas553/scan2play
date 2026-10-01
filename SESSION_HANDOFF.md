# Session Handoff — 2026-10-01

The current state only: the branch, what waits for the owner, what comes next. **The history of every session up to 2026-10-01**
(decisions, the owner's words, what was tried) is in `docs/history/session-handoff-2026-09.md` — read it only when a question
needs the background. Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`**, pushed up to `46d1ba4` (2026-10-01). Check with `git status -sb` and `git log --oneline -8`.
- **Committed and pushed (2026-10-01):** the fifth package of the review (`REVIEW.md`, "Piąta paczka") — 2.4
  `next-track` takes a free player lease (`PlayerLeaseService.claimToPlay`; a waiting command is kept), 1.7 a collision-free queue
  lock key (the party code in base 36), 2.6 `FallbackQueueService.currentPlaylist`, 3.5 `encodeURIComponent` in the poll, 6.4
  milestone/snapshot repositories and the unused `dependency-check` plugin removed from `pom.xml` (checked with an empty local Maven
  repository), 7.3 `.github/copilot-instructions.md` synced with `AGENTS.md` and checked by the Unit tests workflow, an indentation.
  **And the owner's report:** "End party" changed only the window it was pressed in — now every answer of the queue poll carries
  `X-Party-Active` and every window shows the "party closed" banner / the "end party" button by it (within 3 s; a poll sent before
  the DJ's own click is ignored). Scenario `party-closed-elsewhere` (red before), `DjDashboardControllerGuestLimitsTest` +1.
  536 unit tests, 24 database tests, 59 browser scenarios — green in copies of the repo. To try: end the party on the phone, the
  computer's dashboard shows it within 3 s, and back; two dashboard windows right after a restart of the app: only one plays.
- **Committed and pushed (2026-10-01, tried by the owner: "działa"):** the fourth package of the review (below, "The third part of
  2026-10-01") — the CSP (report-only), the rejected song without a name, 4.6, 5.4, 1.6, 1.9 and tests of `SongEvaluationService`.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs`; logs need a
  GitHub login, but failed tests are written as public **annotations**: `.../check-runs/<id>/annotations`): three workflows —
  Unit tests, Browser tests, Database tests. All three green for `46d1ba4` (2026-10-01). Unit tests now also check that the
  Copilot copy of `AGENTS.md` matches it.
- **Printing the QR code — tried by the owner ("działa"), COMMITTED and PUSHED (2026-10-01)** (the owner's wish, 2026-10-01: "zrób oba warianty, dwujęzyczna"): a "🖨" link
  under the dashboard's QR code opens `GET /dj/qr-print` in a new tab — `layout=poster` (one A4 poster, default) or `cards` (eight
  93 × 68 mm cards to cut out); the printed text is Polish **and** English (written in `qr-print.html`, each part with its own
  `lang`), the bar above it (not printed) follows the DJ's language; QR 1000 px. `DjDashboardController.qrPrint`, `ViewAttributes`,
  `qr-print.html`, `css/qr-print.css`, `js/qr-print.js` (no inline handler), `dashboard.html` (the link), 7 message keys PL/EN
  (escapes by a script), `QrPrintPageTest` (3; it writes `target/qr-print/*.html`). 516 unit tests green; both layouts looked at in
  a browser. Note: locally the QR code holds the LAN address
  (`http://192.168.100.184:…`, from `SCAN2PLAY_GUEST_URL` in IntelliJ) — a code printed from the local app works only on that Wi-Fi.

## The third part of 2026-10-01 (committed and pushed)

The owner's choice: the missing song name, then the CSP ("zrób jak uważasz, że jest lepiej"), then 6.2 and the small N items. Details
and files: `REVIEW.md`, "Czwarta paczka".

- **The rejected request without a song name** (the owner's screenshot, a row of 01.04.2026): the Polish prompt told the AI it may leave
  `songName` empty on a rejection. The local `scan2play` database (read only) has 3 such rows, all 01–04.04.2026, all with Polish
  comments; later rejections have names, but the prompt had not changed — it could happen today. Fixed in both prompts, and the code
  saves the guest's text when the name is empty. The 3 old rows cannot be filled in (the guest's text is not stored).
- **CSP, steps 1–2:** no page has an inline script or an `on…=` handler any more (`js/scroll-restore.js`, `js/dj-nav.js`,
  `js/guest-party.js`, `data-auto-submit`, `data-confirm`); `Content-Security-Policy-Report-Only` on every response, reports to
  `POST /csp-report`, logged as `CSP violation: …` (once an hour per violation). **Decisions taken:** scripts without `'unsafe-inline'`,
  styles with it (the `style="…"` attributes stay for now), Bootstrap stays on the CDN, reports to the log. **Step 3 = `CSP_ENFORCE=true`**
  (no code change) after the log stays quiet through real use.
- **4.6:** the guest's text reaches the prompt as one line ≤ 150 characters; the style is decided by the server — the DJ's forced vibe
  could be changed by a guest through the hidden form field before.
- **5.4** bounds of the DJ's limits (also `max` in the form), an overlong playlist link refused; **1.6** `takeNextTrack` without the dead
  retry loop; **1.9** one count less in the "up next" list; **6.2** `SongEvaluationService` tested end to end (`askAi` seam).
- **The guest's page, the owner's two wishes (after trying it):** (1) a mode tile (song / mood) no longer puts the cursor in the field
  on a phone — the keyboard opened and the page jumped (`guest-party.js`; with a mouse it still does); (2) the "party ended" page
  says "DJ nie przyjmuje teraz próśb… sprawdź za chwilę" with **"↻ Sprawdź ponownie"** (the party's own link — the DJ may resume) and
  only a small link to the landing page (it is the DJs' Google login); the error page has **"↻ Spróbuj ponownie"** (a guest's
  `/p/CODE/…` goes back to `/p/CODE`, other pages are opened again) and a small "Strona główna". New keys `party.ended.check_again`,
  `party.ended.own_party`, `error.btn.retry` (`party.ended.home_btn` removed). `HtmlLangDeclarationTest` +1.
- 532 unit tests (was 516), 24 database tests, 58 browser scenarios (+2, `page-forms.js`) — all green in copies of the repo.
- **For the owner to try:** a guest request (song, mood, a rejected one — the history shows its name), the vibe select, "end party" /
  "log out" with cancel and OK, the feedback form, the scroll memory between Panel and History, the guest page's "sending…" button,
  and **the browser console / the app log for `CSP violation`** on a YouTube party, a Spotify party and the guest page.
- **Not done:** 5.5 (the DB password default needs a `local` profile and a change of the IntelliJ run configuration — the owner's
  decision); 2.3; the other half of 7.1.

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

1. After the push of the fifth package, look at the three workflows (the Unit tests one has a new step).
2. **CSP step 3:** `CSP_ENFORCE=true` once the reports are quiet; then, optionally, the `style="…"` attributes as classes (drops
   `'unsafe-inline'` for styles) and Bootstrap from the app instead of the CDN.
3. From `REVIEW.md`: **2.3** single instance (Spring Session JDBC would keep DJs logged in across a deploy — a separate decision);
   **5.5** (needs the `local` profile in IntelliJ); the small N items left (1.8, 2.5, 3.4, 4.7, 5.3, 6.3, 7.2; what remains of
   3.5 — one source of a video's id —, 6.4 — Dependabot (it would open PRs: the owner decides), JaCoCo — and 7.3); **6.2** rest: `QrCodeService`, `FeedbackController`, Spotify (left for now — the owner).
4. `PROJECT_CONTEXT.md` (~1800 lines) — the other half of 7.1, a separate edit.

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
