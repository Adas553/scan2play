# Scan2Play — Project Context

> The application as it is on branch `dev` (2026-10-04). Read it before an architectural change; `AGENTS.md` says how to work,
> `SESSION_HANDOFF.md` what is going on now, `REVIEW.md` the review of 2026-09-30 and what was fixed.
> **History** — the decisions, the owner's reports, what was tried and measured, how every stage was verified — is in
> `docs/history/project-context-2026-10-01.md` (this file word for word before it was cut down, review 7.1; it still describes the
> YouTube player, Auto-Pilot and the background playlist) and `docs/history/session-handoff-2026-09.md`. The full application with
> YouTube and Spotify is the tag `full-player-2026-10-04` (branch `archive/full-player`). Keep this file to what is true now.
> Section numbers are referred to from the code and from applied migrations (5.4, 10, 13, 14) — keep them.

---

## 1. Product Overview

**Scan2Play** is an AI-powered music request platform for events. Guests scan a QR code and send song requests from a phone;
Google Gemini decides whether each one fits the party's vibe. The DJ plays from their own software (VirtualDJ, Serato, rekordbox…) —
**Scan2Play plays nothing**: it collects and filters the requests, counts requests for the same song as votes, and the DJ marks each
one played or skips it. One kind of party ("Twój program DJ-a"). YouTube and Spotify were removed on 2026-10-04 (Spotify: development
mode only, and its policy forbids this use; YouTube: 100 API searches a day shared by every party, and its terms limit playing to
personal use) — migrations V18 and V19.

**Production URL:** `https://www.scan2play.com.pl` (Railway, behind Cloudflare; paused at the moment — `main` is not live).

---

## 2. Technology Stack

| Layer            | Technology |
|------------------|------------|
| Language         | Java 21 |
| Framework        | Spring Boot 4.0.3 (Spring MVC, Thymeleaf, Spring Security + OAuth2 Client, Spring Data JPA) |
| Front end        | Thymeleaf pages, Bootstrap 5 (webjar `org.webjars:bootstrap`, served by the app at `/webjars/bootstrap/…`, the version only in `pom.xml` — `webjars-locator-lite`), plain JavaScript — the dashboard's scripts are ES modules, no bundler, no framework |
| Database         | PostgreSQL 18 (Railway `postgres-ssl:18`), schema by **Flyway** (Section 10) |
| AI               | Google Gemini (`google-genai` 1.38.0), model `gemini-2.5-flash` |
| Other            | ZXing 3.5.3 (QR codes), Caffeine (caches), Maven; the guests' song suggestions come from Apple's iTunes Search API, asked by the browser |
| i18n             | `messages.properties` (EN), `messages_pl.properties` (PL) — non-ASCII as `\uXXXX` escapes |

---

## 3. Architecture

A monolithic Spring Boot application, layered:

```
controller/   HTTP: Thymeleaf views, HTML fragments for AJAX, a few JSON endpoints
service/      business logic
repository/   Spring Data JPA
entity/       JPA entities        model/   enums, records        config/  Spring beans
util/         CodeGenerator, GuestWords, SongNames, Texts, Times, YouTubeSearchLinks
```

Server-rendered pages with AJAX: the DJ dashboard polls the guest queue every 3 s (ETag / 304) and sends its forms by `fetch` (the
DJ keeps their place in the list; on a phone the settings stay as they were). A hidden window does not poll (review 3.4) and asks at
once when it is shown again. **Single instance by design** (Section 13): the caches and the guest limits are in memory. The HTTP
sessions are in PostgreSQL (Spring Session JDBC, `V11`; review 2.3): a deploy or a restart does not log the DJs out.

---

## 4. Domain Model

### 4.1 Entities (database tables)

**Every moment is a `timestamptz` column and an `Instant` in Java** (`V12`, review 1.8) — the same on a machine in Poland and on a
server in UTC; the pages show them in Polish time (`util/Times`: the lists show `clock` — "20:04" — with the day under it, "dziś" /
"wczoraj" / `day` "29.09" (`daysAgo`; fragment `components :: moment`), and `display` — "29.09.2026 20:04:59" — as the cell's title;
a fixed-width UTC `sortKey` for the lists' `data-val`).

**`PartySettingsEntity` → `party_settings`** — one party, owned by one DJ (`owner_id` UNIQUE: one party per DJ).
`partyCode` (5 characters of `[A-Z0-9]`, unique — in the QR code), `ownerId` (the OAuth2 subject), `active` (accepting requests),
`globalVibe` (`VibeType`; `ANY` = no genre: the AI judges by the DJ's note alone), `vibeNote` (V16, ≤ 150: the DJ's own words about
the vibe — the AI gets them as a block of the prompt, `prompt-vibe-note_{pl,en}`, the guests see them above the form;
`POST /dj/dashboard/vibe-note`, one line, empty clears), `djName` (V17, ≤ 60: who plays, e.g. "DJ Koko" — the guests see
"🎧 Gra: DJ Koko" under the page's title; `POST /dj/dashboard/dj-name`, one line, empty clears), `requestLimit` / `cooldownMinutes`
(the guest's own limit, 1–100 / 1–1440), `duplicateCheckWindow` (0–50 recently played songs the AI must not repeat).

**`SongRequestEntity` → `song_requests`** — a guest's request. `partyCode`, `songName` (255; the song as the AI named it), `guestText`
(150, V14: what the guest typed, as typed — one line, what the AI is given; null for older requests; the queue and the history show
it under the song when it says something else, `util/GuestWords`), `votes` (V15, ≥ 1: how many guests asked for it — see below),
`style` (the vibe it was judged against), `decision` (`accepted` / `rejected` / `played`), `djComment` (500), `energyLevel`,
`requestedAt`, `trackUrl` (500; the "🔍 Podejrzyj" link — YouTube's search results for the song, `util/YouTubeSearchLinks`: a page the
DJ's browser opens, no API; a `lyrics` request is searched by the guest's own words, everything else by the AI's name), `playedAt`
(V6; set only when the DJ marks it played). Index `idx_party_decision_time (party_code, decision, requested_at DESC)`. `@PrePersist`
truncates the long fields. **Retention: 30 days from `requestedAt`** — `SongRequestRetentionService` deletes nightly at 04:45 in
batches of 1000, at most 200 batches a night; and with the account.

**Votes** (`SongRequestCommandService.saveOrVote`, V15): an accepted request for a song that already waits in the party's queue (the
same name by `util/SongNames.comparable` — case, accents, punctuation ignored) is not a row of its own: the waiting song gets
`votes + 1` (a conditional `UPDATE … WHERE decision = 'accepted'`; when the DJ played it meanwhile, the request is a new row) and
keeps its name, link and first guest's words. The guest's own waiting song asked for again changes nothing and gives the guest's limit
back (`DjResponse.ownSong`). Under a per-party advisory lock ("S2PR"), so guests asking at once make one row (`SongRequestVotesIT`,
20 rounds × 16 guests — red without the lock). A rejected request is always its own row. The AI's duplicate rule lists only PLAYED
songs (a waiting one is a vote). The queue's ETag counts the votes too (`computeFingerprint`: count-maxId-votes). The DJ's queue and
history have a "Głosy" column (sorted most-first on the first click, `data-sort-first="desc"`; the history sorted by it is the
party's ranking); the guest page lists "🔥 Najwięcej głosów" — up to 3 waiting songs with more than one vote — and the result page
says "Ktoś już o to prosił — dodaliśmy Twój głos! Głosów: N".

**`FeedbackEntity` → `feedback`** — the DJ's bug reports and ideas (`message` ≤ 2000).
**`spring_session`, `spring_session_attributes`** (V11, Spring Session's own schema, no entity) — the HTTP sessions; expired ones are
deleted every minute.

### 4.2 Enums

`HistoryFilter` all / played / rejected (an old link's "guest" / "background" reads as all) · `VibeType` ANY and 16 genres (V16:
Polish hits, 2000s/2010s, R&B & soul, folk / biesiada, kids added; bachata, salsa and reggaeton merged into LATINO — the rows moved
by the migration).

### 4.3 Records

`DjResponse` (the AI's answer: `decision`, `comment`, `songName`, `energyLevel`, `requestKind` title / artist / lyrics / mood /
unchecked, `requestId`, votes, `ownSong`), `HistoryEntry` (one line of the history), `GuestQueueService.GuestQueue` (what the guest
sees under the form), `PlayHistoryService.Page`.

---

## 5. User Flows

### 5.1 DJ Flow

Landing page `/` → one tile, "Zbieraj prośby gości" → `/start` → Google's login → `/dj/dashboard` (`DjSessionHelper.getPartySettings`
creates the DJ's party on the first visit; the old tile links `/start/{kind}` lead to the login too). The dashboard: the guest queue
(polled every 3 s; sort, search, a number per request) with "Oznacz jako zagrane", "Pomiń" (`POST /dj/dashboard/dismiss`: the request
leaves as rejected with the DJ's note) and "🔍 Podejrzyj" on every waiting request; the vibe, the vibe note, "Kto gra", the guest
limits and the use of the server limits; the QR code (`/dj/qr-print`: an A4 poster or eight table cards, Polish and English); the
history; feedback; end / resume the party, delete the account, log out. The forms are sent in the background (`forms.js`; not logout
and account deletion); a song played or skipped leaves the list at once (`s2p:guest-queue-changed` → the queue is fetched again).

**When the AI cannot be asked** (an error, a timeout), the request goes on to the DJ unchecked — accepted, the guest's words, the note
`ai.unavailable.to_dj`, `requestKind` `unchecked`; the guest sees "PRZEKAZANE".

**On a phone** (narrower than 768 px): the vibe, the limits and the QR code fold under one button "⚙️ Ustawienia, klimat i kod QR"
(`.s2p-phone-settings`; folded by `app.css` alone, `settings-toggle.js` opens them and keeps the choice for the tab in
`sessionStorage`); the queue comes first, every waiting request is a card with big buttons; the list scrolls with the page.

**The active queue**: each request has its number — a CSS counter in `app.css`, so it follows the polled list, the sort and the
search by itself. "🧹 Wyczyść kolejkę" beside the heading (shown only while a request waits — `:has`) asks first (`data-confirm`) and
sends `POST /dj/dashboard/clear-queue`: the waiting requests go to the history's rejected ones. Ending the party does not clear the
queue (the DJ may pause it for a break or a limit).

Every dashboard window follows the party's state within one poll: the party open or closed (`X-Party-Active` — the "party closed"
banner and the "end party" button) and the guest limits (`X-Guest-Limits` — the warning `party-full` —, `X-Guest-Limits-Use`).

**The history** (`PlayHistoryService`): the party's requests that played or were rejected, by `COALESCE(played_at, requested_at)`,
newest first, one bounded query (`limit + 1`). The History tab (in place of the queue) and the standalone page `/dj/history-view`:
filters All / Played / Rejected applied by the server, "Show more" (50 at a time, ≤ 300); both keep the search text and the sort by a
column (`captureListState` / `restoreListState` in the tab; on the standalone page, which loads itself again, through
`sessionStorage` once). A heading row where a new day starts, Polish time (`tr[data-day-heading]`: "dziś — sobota, 03.10",
"wczoraj — …", "wtorek, 29.09"; `Times.dayKey` / `weekday`) — the history keeps 30 days, so the parties of a month share it.
`list-tools.js`: the search hides a heading with no row left under it; a sort by a column hides them all (`.s2p-sorted`), undoing it
puts the server's order back.

### 5.2 Guest Flow

`/p/{partyCode}` (no login) → the form: one field for a song — a title, an artist or a line of the lyrics (song suggestions from
iTunes, asked by the browser) → `POST /p/{partyCode}/request` (`songName`; an async `Callable`) → the result page (decision, the AI's
comment, the votes). A request the AI reads as a mood is not saved: the guest is back at the form with the text and
`guest.error.song_only` (the DJ sets the mood). Under the form: "🔥 Najwięcej głosów", "Ostatnio wysłane" (the 5 newest waiting
requests, unnumbered — the DJ picks the order) and "Twoja prośba „…” czeka u DJ-a" (`GuestQueueService`; the guest's requests are
remembered in the session) — fetched again when the guest comes back to the page and on "↻ Odśwież", no timer. An ended party shows
"DJ nie przyjmuje teraz próśb" with "↻ Sprawdź ponownie" (the party's link) — the landing page is for DJs.

**What reaches the AI:** the guest's text as one line ≤ 150 characters, `"` made `'` (`SongEvaluationService.forPrompt`); the style
decided by the server (`GuestController.styleOf`): the DJ's vibe when set, else `ANY` — the guests pick no vibe.

**Limits** — each counted **before** the AI is asked:
1. the guest's own: up to the DJ's `requestLimit` requests, then a wait of `cooldownMinutes` **from the last of them**, after which the
   whole limit is back; counted by the session's id in memory (`GuestSessionService.tryAcquire`, one atomic `compute` — not in the
   session: with the sessions in the database every request works on its own copy, so parallel requests could not see each other's
   count there) — `guest.error.rate_limit`. A request that came to nothing gives its place back (`giveBack`: a mood sent back to
   the form, the guest's own waiting song asked for again). The guest reads the wait of this limit and of the next one as
   `GuestController.waitText` says it: whole minutes rounded up from a minute on ("3 min"), seconds below it ("45 s");
2. client IP + party: `guest.limit.per-ip-party` (30) per `guest.limit.per-ip-window-minutes` (10) — loose, a venue's Wi-Fi is one
   address — `guest.error.too_many_requests`;
3. party: `guest.limit.per-party-daily` (300) per 24 h — `guest.error.party_daily_limit`.

2 and 3 are `GuestRequestLimiter` (Caffeine, in memory); 0 switches a limit off. The address is `getRemoteAddr()` or the header named
by `guest.client-ip-header` (`CF-Connecting-IP` — set on Railway). The DJ sees the use of 2 and 3 under the limits form (badges:
grey, yellow from 80 %, red) and a warning above the queue while the party's limit stops guest songs.

### 5.3 (Spotify — removed 2026-10-04, migration V18)

### 5.4 (The YouTube player, Auto-Pilot and the background playlist — removed 2026-10-04, migration V19)

The old migrations V2–V9 and the browser tests' history refer here: the description is in `docs/history/project-context-2026-10-01.md`,
Section 5.4, and the code in the tag `full-player-2026-10-04`.

---

## 6. File Inventory

### 6.1 Controllers

| Class | Purpose |
|-------|---------|
| `HomeController` | `/`: the landing page, or the dashboard for a logged-in DJ; `/start` → Google's login |
| `DjDashboardController` | the dashboard, the queue poll (`/dj/dashboard/updates`), the history page and fragment, the QR print page |
| `DjPartySettingsController` | start / end party, vibe, vibe note, "Kto gra", limits (bounded), account deletion |
| `DjSongController` | mark played, skip, clear the queue |
| `DjSessionHelper` | the party of the logged-in DJ (cached in the session) and **`validateOwnership`** (IDOR) |
| `GuestController` | the guest's page, its list, the request (limits, style, evaluation) |
| `FeedbackController` | `POST /dj/feedback` (JSON) |
| `CspReportController` | `POST /csp-report` — the browsers' CSP reports, logged once an hour per violation |
| `LegalController` | `/privacy`, `/terms` (a file per language) |
| `ViewAttributes` | names of the model attributes |

### 6.2 Services

| Class | Purpose |
|-------|---------|
| `SongEvaluationService` | the guest's request: Gemini (`askAi`, the prompt per language) → the "🔍 Podejrzyj" link → save or vote |
| `DjService` | the guest queue (`dashboardQueue` cache), its fingerprint (ETag), mark played, skip (`dismissSong`), clear the queue |
| `PlayHistoryService` | the history (Section 5.1) |
| `GuestQueueService` | what the guest sees under the form (with the most wanted songs) |
| `SongRequestCommandService` | saves a guest's request, or counts it as a vote on the same waiting song (advisory lock) |
| `GuestSessionService` / `GuestRequestLimiter` | the guest limits (Section 5.2) |
| `PartySettingsQueryService` / `PartySettingsCommandService` | read (cached copy) / write (evicts after commit) of the party |
| `QrCodeService` | QR codes (ZXing, cached) |
| `AccountDeletionService` | deletes all of a DJ's data and evicts the caches after the commit |
| `SongRequestRetentionService` | the nightly purge of song requests (Section 4.1) |

### 6.3 Configuration

`SecurityConfig` (routes, OAuth2 login, logout, CSRF, the CSP — Section 8), `AppConfig` (caching, scheduling, the Caffeine caches),
`GeminiConfig` (the Gemini client, 10 s timeout; the `ObjectMapper` bean).

### 6.4 Templates

`landing.html`, `dashboard.html`, `history.html` (its `historyTableContent` fragment is also the dashboard's History tab),
`qr-print.html`, `index.html` (the guest's page), `result.html`, `party_ended.html`, `error.html`, `privacy[_pl].html`,
`terms[_pl].html`; `fragments/`: `components.html` (`dj-nav`: the account buttons, the sticky tabs Panel / Kolejka / Historia, the
feedback modal; `scroll-restore-script`; `moment`; `logo` — the mark and "Scan2Play" with a cyan "2", the heading of the
dashboard and the guest page; `footer`), `guest-queue.html`. Texts the scripts need travel in `data-*`
attributes. **No inline script, no `on…=` handler and no `style="…"`** on any page (`NoInlineCodeInTemplatesTest`).

### 6.5 Static assets

| File | Purpose |
|------|---------|
| `js/dashboard/*.js` | the dashboard as ES modules: `main.js` imports `list-tools.js` (sort, search, filters, "Show more" — the history page loads it alone), `forms.js` (AJAX forms — not logout and account deletion —, `data-auto-submit`; played / skipped / cleared → `s2p:guest-queue-changed`), `tabs.js` (the history in place), `settings-toggle.js` (on a phone: the settings folded), `polling.js` (the queue every 3 s and its headers; at once on `s2p:guest-queue-changed` and when the window is shown again; none while hidden), `common.js`; they talk only through the `s2p:*` events of `events.js`, never through `window` |
| `js/dj-nav.js` | `form[data-confirm]` (capture phase, before `forms.js`) and the feedback form |
| `js/scroll-restore.js` | the scroll memory of the DJ pages (in `<head>`) |
| `js/guest-party.js` | the guest's page: the list refresh, "sending…" |
| `js/song-autocomplete.js` | song suggestions from the iTunes Search API (debounced, client side) |
| `js/qr-print.js`, `css/qr-print.css`, `css/app.css` | the print page; the shared styles |
| `images/logo.svg`, `favicon.ico` | our mark: three QR finder corners and a cyan play triangle on the dark tile (2026-10-04); on the landing page, the dashboard, the guest page, the QR poster and cards; the favicon is the same mark at 16 / 32 / 48 px |

### 6.6 Resources

`application.properties` (all configuration, env overrides — Section 10), the message bundles, `prompts/` (`prompt-template_{en,pl}`,
`prompt-duplicate-rule_{en,pl}`, `prompt-vibe-note_{en,pl}`), `db/migration/V1..V19`.

---

## 7. External API Integrations

### 7.1 Google Gemini

- Evaluates guests' requests. Model `gemini-2.5-flash` (pinned; env `GOOGLE_AI_MODEL`); a request may think up to
  `google.ai.thinking-budget` tokens (1024). Timeout 10 s per call.
- The prompt per language (PL / EN by the guest's locale, else EN). The answer is JSON (`DjResponse`). A request may be a title, an
  artist or a line of lyrics; never replace it by another song; not knowing a song (a new one) is no reason to reject it; a mood or
  an occasion is `requestKind` `mood` (sent back to the guest); on a rejection `songName` is what the guest asked for (the code also
  falls back to the guest's text).
- AI down → the request goes to the DJ unchecked (Section 5.1; no retry). Duplicates: the last `duplicateCheckWindow` played songs go
  into the prompt (a waiting song asked for again is a vote, not a duplicate).

### 7.2 (Spotify — removed 2026-10-04)

### 7.3 YouTube — no API

Scan2Play uses no YouTube API (removed 2026-10-04, V19). "🔍 Podejrzyj" is a plain link to YouTube's search results
(`https://www.youtube.com/results?search_query=…`, `util/YouTubeSearchLinks`), opened by the DJ's browser.

---

## 8. Security Model

Public: `/`, `/start/**`, `/p/**`, `/privacy`, `/terms`, `/oauth2/**`, `/login/**`, `/css/**`, `/js/**`, `/images/**`, `/webjars/**`,
`/error`, `POST /csp-report`. Everything else needs the DJ's login; `/dj/**` validates the party's ownership
(`DjSessionHelper.validateOwnership` — IDOR). CSRF on (tokens in `<meta>` for AJAX; `/csp-report` is exempt). Logout `POST
/dj/logout`. `th:utext` only for texts of our own bundles; song names and the guests' words are escaped.

**Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`): `script-src 'self'`, styles and fonts from the app only —
**no `'unsafe-inline'` at all**: no inline script, no `on…=` handler, no `style="…"` (the templates use `s2p-…` classes of `app.css`;
scripts change styles through `element.style`, which the policy allows), checked over every template by
`NoInlineCodeInTemplatesTest`; images from the app and `data:`; `connect-src` the app and `itunes.apple.com`; no frames;
`object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; `report-uri /csp-report`. **Report-only**
while `security.csp.enforce=false` (env `CSP_ENFORCE`): the log says `CSP violation: …` for what it would block. Switch it on once
real use leaves the log quiet. The browser tests run the dashboard, the guest page and the QR print page under this policy, enforced
(Section 13); the standalone history page is not covered by them.

---

## 9. Caching Strategy

| Cache | Key | TTL / size | Usage |
|-------|-----|------------|-------|
| `partySettings` | partyCode | 24 h / 500 | `PartySettingsQueryService.getSettings` — every caller gets a **copy**; `updateSettings` evicts after its commit |
| `qr-codes` | text + size | 24 h / 1000 | `QrCodeService` |
| `dashboardQueue` | partyCode | 3 s / 200 | `DjService.getDashboardQueue`; evicted when a song is played, skipped or the queue cleared |

The DJ's party code is cached in the `HttpSession`. Account deletion evicts the party from the caches after its commit.

---

## 10. Production Configuration

### Environment variables

`GOOGLE_CLIENT_ID` / `_SECRET`, `GOOGLE_AI_API_KEY`, the database (`PGHOST`, `PGPORT`, `PGDATABASE`, and **`PGUSER` / `PGPASSWORD`
required** — no defaults since review 5.5; Railway sets all five), `SCAN2PLAY_GUEST_URL` (the base URL in the QR code; locally the LAN
address, so a phone on the same Wi-Fi can open it), `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` (Railway), and the optional overrides
below. The app no longer reads `YOUTUBE_API_KEY`, `SPOTIFY_*` or `YOUTUBE_SEARCH_DAILY_BUDGET`. The DJ's login works only on
`localhost` or a public HTTPS address (Google refuses a LAN IP as a redirect URI); the guest side works through the LAN IP.

### Key application properties

| Property | Value |
|----------|-------|
| `google.ai.model-name` / `google.ai.thinking-budget` | `gemini-2.5-flash` / 1024 |
| `spring.jpa.hibernate.ddl-auto` | `validate` (Flyway owns the schema) |
| `server.forward-headers-strategy` | `FRAMEWORK` |
| `server.compression.*` | gzip for HTML / CSS / JS / JSON from 2 KB |
| `server.tomcat.max-http-form-post-size` | 10 KB |
| `server.shutdown` | graceful, 30 s |
| `spring.task.execution.pool.*` | 16 core / 32 max threads, queue 50 (env `ASYNC_POOL_*`) — the guests' requests (`Callable`) |
| `spring.datasource.hikari.*` | 15 max, 5 idle, 5 s connect timeout |
| `guest.limit.*` | 30 per 10 min per address + party, 300 per 24 h per party (env `GUEST_LIMIT_*`) |
| `guest.client-ip-header` | empty (env `GUEST_CLIENT_IP_HEADER`) |
| `security.csp.enforce` | `false` = report only (env `CSP_ENFORCE`) |
| `spring.session.jdbc.initialize-schema` | `never` (the tables are Flyway's, `V11`); a session lives 30 min without a request |

**The profile `local`** (`application-local.properties`): the developer's defaults `PGUSER=postgres`, `PGPASSWORD=1111`. IntelliJ's run
configuration has *Active profiles: local* (or `SPRING_PROFILES_ACTIVE=local`); without it and without the variables the application
does not start (`Could not resolve placeholder 'PGUSER'`).

### Database migrations (Flyway)

The schema is a sequence of files `src/main/resources/db/migration/V<n>__<what>.sql`, applied in order at startup before Hibernate
validates; **never edit an applied one** — not even a comment: Flyway checksums the whole file; add the next. An entity change that
touches the schema needs its migration in the same change, or the application does not start.
`spring.flyway.baseline-on-migrate=true`: a database that had tables before Flyway (production) is recorded as V1 without running it.
**`SPRING_JPA_HIBERNATE_DDL_AUTO`** on Railway was `validate`; the owner removed it (2026-10-01) — the removal is a **staged change**
that Railway applies with the next deploy. Never set it to `update`: Hibernate would change the schema behind Flyway's back.

| Version | What |
|---------|------|
| V1 | baseline (the schema of 2026-09-28) |
| V2, V4, V5, V7, V8, V9 | the background playlist's `fallback_track`, its order, moves, play log `fallback_play`, status `SKIPPED`, the queue indexes (all dropped by V19) |
| V3 | data fix: request limits 0 → 2 / 3 |
| V6 | `song_requests.played_at` (no back-fill) |
| V10 | `youtube_search_budget` (dropped by V19) |
| V11 | `spring_session`, `spring_session_attributes` (Spring Session JDBC's schema, word for word) |
| V12 | every `timestamp` → `timestamptz`; the old values read in the session's zone = the JVM's that wrote them (Polish time locally, UTC on Railway) |
| V13 | `party_settings.active_provider` may be `REQUESTS_ONLY` (the check constraint) |
| V14 | `song_requests.guest_text` varchar(150), nullable (no back-fill: the words of older requests were never kept) |
| V15 | `song_requests.votes` integer NOT NULL DEFAULT 1 |
| V16 | `party_settings.global_vibe`: BACHATA_AND_KIZOMBA / SALSA_AND_TIMBA / REGGAETON_AND_DANCEHALL → LATINO, the check constraint with the new list; `party_settings.vibe_note` varchar(150) |
| V17 | `party_settings.dj_name` varchar(60), nullable |
| V18 | Spotify removed: Spotify parties deleted with their requests, background tracks, plays and their DJ's feedback; `spotify_*` columns dropped; the kind's check without SPOTIFY |
| V19 | YouTube removed: every party that is not `REQUESTS_ONLY` (and one with no kind) deleted with its requests and its DJ's feedback; `fallback_play`, `fallback_track`, `youtube_cache`, `youtube_search_budget` dropped; the columns `active_provider`, `playback_mode`, `fallback_playlist_url`, `fallback_shuffle` dropped |

Checked by `MigrationIT` (`mvnw verify -Pit`, Section 13) on an empty PostgreSQL 18, locally and on GitHub; V16, V18 and V19 also on
rows of the old kind (`VibeMigrationIT`, `SpotifyRemovalMigrationIT`, `YouTubeRemovalMigrationIT`).

**First production deploy checklist** (the next deploy applies V2..V19 at once): (0) **V18 and V19 delete every production party
that was made before 2026-10-04** — in April only YouTube and Spotify parties existed, so every DJ gets a new party with a new code
at their next login, and a QR code printed before no longer works (their requests are long gone anyway: 30-day retention). The
variables `YOUTUBE_API_KEY`, `YOUTUBE_SEARCH_DAILY_BUDGET`, `SPOTIFY_*` can go from Railway; (1) back up the database; (2) dump the
production schema (`pg_dump --schema-only --no-owner`) and compare it with `V1__baseline.sql` — the same tables and columns, or
Hibernate's validation refuses to start; (3) deploy — Flyway creates `flyway_schema_history`, baselines, and applies the rest.
What is known (Railway, read 2026-10-01): the service `scan2play` (project `celebrated-enjoyment`) builds `main`, last deployed
2026-04-07 from `5314006`; the app and its `postgres-ssl:18` are paused (the database must run for steps 1–2). The entities did not
change between `5314006` and V1 (only `@Builder.Default`), so V1 should match production; V9 drops only `IF EXISTS`. No `TZ` /
`-Duser.timezone` on Railway: the JVM is UTC, which is what V12 assumes for the old values. `main` is an ancestor of `dev`: the merge
is a fast-forward. After it: `CSP_ENFORCE` stays off on Railway until a few days of real use leave the log quiet, then `true`;
Dependabot alerts and security updates switched on in GitHub.

---

## 11. Main Dependencies

```
GuestController            → SongEvaluationService, GuestQueueService, GuestSessionService, GuestRequestLimiter, PartySettingsQueryService
DjDashboardController      → DjService, PlayHistoryService, QrCodeService, GuestRequestLimiter, PartySettingsQueryService, DjSessionHelper
DjPartySettingsController  → PartySettingsCommandService, AccountDeletionService, DjSessionHelper
DjSongController           → DjService, DjSessionHelper
SongEvaluationService      → Gemini Client, SongRequestRepository, SongRequestCommandService, PartySettingsQueryService
GuestQueueService          → DjService
```

---

## 12. HTTP Endpoints

### Public

| Method | Path | Handler / notes |
|--------|------|-----------------|
| GET | `/` | `HomeController.home` |
| GET | `/start`, `/start/{kind}` | → Google's login (`{kind}`: the old tile links, ignored) |
| GET | `/p/{partyCode}` | the guest's page (`party_ended` when closed) |
| GET | `/p/{partyCode}/queue` | the guest's list alone (empty when closed, 404 for an unknown party) |
| POST | `/p/{partyCode}/request` | a request: `songName` |
| GET | `/privacy`, `/terms` | legal pages |
| POST | `/csp-report` | a browser's CSP report (no CSRF; 204) |

### DJ (logged in; every endpoint with a `partyCode` checks ownership)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/dj/dashboard` | the dashboard |
| GET | `/dj/dashboard/updates` | the guest queue `<tbody>` (ETag / 304); every answer, 304 too, carries `X-Guest-Limits`, `X-Guest-Limits-Use`, `X-Party-Active` |
| POST | `/dj/dashboard/play` | `id`: a request marked played |
| POST | `/dj/dashboard/dismiss` | `id`: a waiting request skipped by the DJ → rejected, the DJ's note |
| POST | `/dj/dashboard/clear-queue` | "🧹 Wyczyść kolejkę": every waiting request of the DJ's own party → rejected, "Cleared by the DJ 🧹" (one `UPDATE`, `SongRequestRepository.rejectWaiting`) |
| POST | `/dj/dashboard/vibe`, `/vibe-note`, `/dj-name`, `/limits` | settings |
| GET | `/dj/history-view`, `/dj/history-view/fragment` | `limit` (50..300), `filter` |
| GET | `/dj/qr-print` | `layout` = poster / cards |
| POST | `/dj/start-party`, `/dj/end-party`, `/dj/delete-account`, `/dj/logout`, `/dj/feedback` | |

---

## 13. Known Limitations & Technical Debt

### Architecture
- No user table: the DJ is the `owner_id` of the party; one party per DJ. Old parties are never cleaned up (their requests are,
  after 30 days).
- **Single instance:** the caches and the guest limits are in memory — a second instance would need them shared. The HTTP sessions
  are in the database (review 2.3); each request with a session reads it and writes its last access time (the dashboard: one request
  per shown window every 3 s).

### Security
- The CSP is report-only on Railway until switched on (`CSP_ENFORCE=true`; locally it is on).
- `REVIEW.md` lists what else is open.

### Front end
- Polling every 3 s (ETag / 304), no WebSockets; a hidden window rests until it is shown (review 3.4).
- `<html lang>` follows the bundle that wrote the texts (`th:lang="#{html.lang}"`; `HtmlLangDeclarationTest`). There is no
  `messages_en`: a browser in a language without a bundle gets the bundle of the JVM's own locale (Polish on a Polish machine).

### Testing
- **Unit tests** (`mvnw test "-Dtest=!Scan2playApplicationTests,!*IT"`, no database): 250. Pure Mockito, plus template rendering with
  the real bundles (`DashboardPageRenderTest`, `GuestPageRenderTest`, fragment tests) and `SmokeTest` (`@WebMvcTest` with the real
  security chain). **Coverage** (JaCoCo, a report, not a gate): `target/site/jacoco/index.html` after `mvnw test`; the Unit tests
  workflow writes the totals per package to its summary and keeps the report as the artifact `coverage-report`.
- **Database tests** (`mvnw verify -Pit`): 19 `*IT` on a real PostgreSQL — `PostgresIntegrationTest` creates and drops its own
  `s2p_it_*` database and starts the whole application on it: `MigrationIT`, `SongRequestRepositoryIT`, `ApplicationSetupIT`,
  `SessionStoreIT` (what the app keeps in a session survives the database and another repository; the cleanup of expired sessions),
  `SongRequestVotesIT` (votes under the lock); with a database of their own, migrated to the version before and given rows the old
  way: `TimestampMigrationIT` (V12 — the same moments; red when the old values are read as UTC), `VibeMigrationIT` (V16),
  `SpotifyRemovalMigrationIT` (V18), `YouTubeRemovalMigrationIT` (V19: the YouTube parties go with their data, a requests-only party
  stays). New SQL that locks or counts gets a test there.
- **Browser tests** (`python src/test/browser/run.py`; guide: its `README.md`): the real scripts on the real rendered dashboard
  (`DashboardPageRenderTest` writes it), the guest page and the QR print page (`GuestPageRenderTest`, `QrPrintPageTest`) in a headless
  Chrome, with a Python stand-in server that the scenarios configure; 23 scenarios. The stand-in sends the real CSP **enforced** and
  every scenario fails on a violation. They do not cover two real devices, how a page looks, and the guest's behaviour beyond the CSP.
- **CI** (GitHub Actions, every push to `dev` / `main` and every PR): `unit-tests.yml` (also checks that
  `.github/copilot-instructions.md` is `AGENTS.md`), `db-tests.yml` (`postgres:18`), `browser-tests.yml`. `gh` is not installed
  locally; the public API shows the runs, and failed tests are written as public annotations. **Dependabot**
  (`.github/dependabot.yml`): security fixes only (version updates off by `open-pull-requests-limit: 0`); it works from the default
  branch `main` and needs "Dependabot alerts" and "security updates" switched on in the repository's settings.

### AI
- No retry when Gemini is down (the request goes to the DJ unchecked); no check that a song exists.

---

## 14. (Roadmap V2.0 "Master Queue" — the server-driven YouTube queue; removed with the player on 2026-10-04)

The old migrations V2–V6 refer here. The record of the phases, their decisions and how each was verified:
`docs/history/project-context-2026-10-01.md`, Section 14. One rule from it still holds: SQL that two requests can run at once for
the same party takes a per-party `pg_advisory_xact_lock` and gets a test on a real database (today: the votes, "S2PR").
