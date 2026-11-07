> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 13, 14. Keep it to what is true now.

## 13. Known Limitations & Technical Debt

### Architecture
- No user table: the DJ is the `owner_id` of the party; one party per DJ. Old parties are never cleaned up (their requests are,
  after 30 days).
- **The demo's daily limit** is one for all its visitors: a flood blocks its guest page until the nightly reset (Section 5.5).
- **Single instance:** the caches and the guest limits are in memory — a second instance would need them shared. The HTTP sessions
  are in the database (review 2.3); each request with a session reads it and writes its last access time (the dashboard: one request
  per shown window every 3 s).

### Security
- The CSP is enforced on Railway since 2026-10-15 (`CSP_ENFORCE=true`; locally it is on). Reported so far (2026-10-06): only
  Cloudflare's Web Analytics beacon (`static.cloudflareinsights.com`, injected by Cloudflare into the HTML) — switched off since
  (2026-10-15: "Disabled" for the site, no beacon in the page). Switched on again, it needs its host in the CSP and a sentence in
  the privacy policy.
- The review of 2026-09-30 (`docs/history/review-2026-09-30.md`) is done; the one point left open is 2.3 (single instance, above).

### Front end
- Polling every 3 s (ETag / 304), no WebSockets; a hidden window rests until it is shown (review 3.4).
- `<html lang>` follows the bundle that wrote the texts (`th:lang="#{html.lang}"`; `HtmlLangDeclarationTest`). The English texts are
  `messages.properties` itself: a browser in any language without a bundle (English, German…) gets them, whatever the server's own
  language (`spring.messages.fallback-to-system-locale=false`; before it, Polish on a Polish machine — `SmokeTest`).

### Testing
- **Unit tests** (`mvnw test "-Dtest=!*IT"`, no database): 1161 (2026-11-06). Pure Mockito, plus template rendering with
  the real bundles (`DashboardPageRenderTest`, `GuestPageRenderTest`, fragment tests), `SmokeTest` (`@WebMvcTest` with the real
  security chain) and `DjDashboardControllerQueueCacheTest` (the queue poll with the real `dashboardQueue` cache of `AppConfig`). **Coverage** (JaCoCo, a report, not a gate): `target/site/jacoco/index.html` after `mvnw test`; the Unit tests
  workflow writes the totals per package to its summary and keeps the report as the artifact `coverage-report`.
- **Database tests** (`mvnw verify -Pit`): 152 tests in 40 `*IT` classes (2026-11-06) on a real PostgreSQL — `PostgresIntegrationTest` creates and drops its own
  `s2p_it_*` database and starts the whole application on it: `MigrationIT`, `SongRequestRepositoryIT` (the history's order, the
  queue's fingerprint — also changes that cancel out in counts —, the night's clearing of the queues, the retention), `ApplicationSetupIT`,
  `SessionStoreIT` (what the app keeps in a session survives the database and another repository; the cleanup of expired sessions),
  `SongRequestVotesIT` (votes under the lock; a vote while the DJ skips the song; "Zagrane" plays only a waiting request; "Pomiń" right
  after "Zagrane"; "↩ Przywróć", "Wyczyść historię", "Wyczyść kolejkę" at the same moment as a new request or a click; a song the DJ skipped, and put back), `PartySettingsWritesIT` (two settings' writes at once keep both; the hosts' old
  link writes nothing), `AccountDeletionIT` ("Dołącz" during the deletion waits, no deadlock; a request in flight is not saved; the
  requests in one statement), `FirstLoginIT` (a first login in eight tabs at once — one party), `PartyStaffRepositoryIT`,
  `StaffInvitationIT`, `PushSubscriptionRepositoryIT`, `DjPlanIT` (V35: a plan's row as written, gone with the account; V36: no second trial after "Usuń konto", the key the same in Java
  and SQL); with a
  database of their own, migrated to the version before and given rows the old way: `TimestampMigrationIT` (V12 — the same moments;
  red when the old values are read as UTC), `VibeMigrationIT` (V16), `SpotifyRemovalMigrationIT` (V18), `YouTubeRemovalMigrationIT`
  (V19: the YouTube parties go with their data, a requests-only party stays), `SkipCommentMigrationIT`, `CommentStyleMigrationIT`,
  `RequestNumberMigrationIT` (V28), `StaffLinkMigrationIT`, `StaffPermissionMigrationIT` (V32), `TrialUsedMigrationIT` (V36: the trials before it remembered), `RequestCheckedMigrationIT` (V39: the requests that went unchecked before it). New SQL that locks or counts gets a
  test there.
- **The landing page's pictures** (`python src/test/browser/screenshots.py`): the same pieces as the browser tests (a copy of the
  repo, Maven there, the stand-in, headless Chrome) write `static/images/landing/*.webp` — look at them before committing.
- **Browser tests** (`python src/test/browser/run.py`; guide: its `README.md`): the real scripts on the real rendered dashboard
  (`DashboardPageRenderTest` writes it), the guest page and the QR print page (`GuestPageRenderTest`, `QrPrintPageTest`) in a headless
  Chrome, with a Python stand-in server that the scenarios configure; 388 scenarios (2026-11-06). The stand-in sends the real CSP **enforced** and
  every scenario fails on a violation. They do not cover two real devices, how a page looks, and the guest's behaviour beyond the CSP.
- **CI** (GitHub Actions, every PR and every push to `main` — not to `dev`: its changes come by PRs that ran them on the same
  tree; the review of 2026-10-21, O3): `unit-tests.yml` (also checks that
  `.github/copilot-instructions.md` is `AGENTS.md`), `db-tests.yml` (`postgres:18`), `browser-tests.yml` — none for a change of the documentation alone (`*.md`, `docs/`; the unit
  tests still for `AGENTS.md` and its Copilot copy), and the browser tests only for what they render or run (`src/main` but the
  migrations and the repositories, the render tests, the scenarios, `pom.xml`; the owner, 2026-10-21: the month's 2,000 free minutes
  were at 90 %, three quarters of them the browser tests; `workflow_dispatch` runs any by hand); the actions pinned by their commit (the version in a comment beside it — Dependabot
  reads it). `gh` is not installed
  locally; the public API shows the runs, and failed tests are written as public annotations. **Dependabot**
  (`.github/dependabot.yml`): security fixes only (version updates off by `open-pull-requests-limit: 0`); it works from the default
  branch `main`; "Dependabot alerts" and "security updates" are on in the repository's settings (2026-10-16).

### AI
- No retry when Gemini is down (the request goes to the DJ unchecked; `AiHealthMonitor` writes it to the log, tells the owner and
  shows the panels' bar); no check that a song
  exists.

---

## 14. (Roadmap V2.0 "Master Queue" — the server-driven YouTube queue; removed with the player on 2026-10-04)

The old migrations V2–V6 refer here. The record of the phases, their decisions and how each was verified:
`docs/history/project-context-2026-10-01.md`, Section 14. One rule from it still holds: SQL that two requests can run at once for
the same party takes a per-party `pg_advisory_xact_lock` and gets a test on a real database (today: the votes, "S2PR").
