# Session Handoff — 2026-11-20

The current state only: the branch, what waits for the owner, what comes next. **Kept short: the whole file ≤ 15 KB, "Start here"
≤ 4 KB, an item ≤ 4 lines.** After each release move to `docs/history/session-handoff-<date>.md`, word for word: the released
items and their checks (migrations applied, the deploy's lines), the previous "state" item, a review's fixed points, the owner's
words behind a decision — keep here only `main` and its deploy, open PRs and branches, what waits for the owner, the next step.
The owner's numbered ideas ("item N") are in `docs/ideas.md`; traps seen before in `CLAUDE.md` ("Mind").

**The history** (decisions, the owner's words, what was tried) is in `docs/history/session-handoff-<date>.md`, one file for each
tidying, by date (from `-2026-09` on). The latest, `session-handoff-2026-11-17.md`: the releases #251 … #272 (the daily report, the
wishes, the QR print, the review's fixes and V71, the song suggestions) and the owner's picks on the pricing (2026-11-05, -06).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-11-17-song-suggestions.md`).

## Start here

- **2026-11-17 — the state.** `main` = `c2d6254` (#276, the artist words of the song suggestions; before it #274, the fixes of the
  review of #272; `dev` has them as #275, #277), no migration since V71. The last deploy checked: `e4ff4d1` (#272), 2026-11-16 22:56
  UTC, SUCCESS (Railway, read only): Flyway at 71, started in 4.9 s, no WARN, 0 × 5xx of 681 requests in 24 h, RAM 0.48 GB; curl
  without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`, `/healthz` 200. The deploys of #274 and #276 not checked yet.
- **2026-11-17 — `claude/wonderful-edison-4qz6fs`, PRs #278 (`main`) and #279 (`dev`), not merged, no migration:** the landing page and "Dla kogo" rewritten for a DJ's
  fears: the title "Mniej ludzi przy konsoli…", "Ty decydujesz", the features by the DJ's gain, "Pierwsza impreza", the weak signal
  in the FAQ (the guest page measured: 87 KB, 2.7 s on 3G); `/dj-weselny`'s features: the wishes first.
- **2026-11-17 — `claude/optimistic-hamilton-zc7qq6` = that branch + its review (`docs/history/review-2026-11-17-landing-message.md`)
  and the fixes P1–P13 (texts, the 🎤 of the mic card, comments; P9 left: three cards stay three).** Waits for the owner: the read, then
  this branch into #278 / #279 (or merged instead of them).
- **2026-11-18 — `claude/funny-wozniak-sxv682`, PRs #280 (`main`) and #281 (`dev`), not merged, migration V72:** the login with an e-mail address and a password beside
  Google's (`/login`, `PasswordLoginController`, table `dj_account`; the owner's picks: the address confirmed by the e-mail's link,
  no linking with a Google account of the same address, "Nie pamiętasz hasła?" now). Rules: `docs/context/07-09-…` Section 8. V72 only
  adds a table and an index (no rows moved): the nightly copy is enough (the owner). After the release: try a registration on production (Brevo must send). The trial: once per Google account or address (the terms).
- **2026-11-18 — `claude/beautiful-darwin-19krex` = that branch + its review (`docs/history/review-2026-11-18-password-login.md`)
  and the fixes P1–P14, migration V73 (one column wider, no rows moved):** no account told by the login or the forms' time, limits
  a stranger cannot turn against the owner, the forms' work on its own thread, links used once by one `UPDATE`, sessions kept /
  ended right. Waits for the owner: the read, then this branch into #280 / #281 (or merged instead of them).
- **2026-11-18 — `claude/blissful-ritchie-36scit` = `beautiful-darwin-19krex` + "Nie wylogowuj mnie na tym urządzeniu", PRs #282 (`main`) and #283 (`dev`), V73 (#280 merged):**
  a box on `/login` (clear by default) for Google and the password: 14 days without a request (`StayLoggedIn`, the session's own
  `MAX_INACTIVE_INTERVAL`), a persistent cookie; Google's button posts to `POST /login/google`. Waits for the owner: a review in a new
  session, then the merge. After the release: curl `/login` without cookies — `SESSION` with `Secure; HttpOnly; SameSite=Lax` (as `/demo` today).
- **2026-11-18 — in #282 / #283 since: its review (`docs/history/review-2026-11-18-stay-logged-in.md`) and the fixes P1–P11 (no
  migration; P7, P10 left):** "Wyloguj na innych urządzeniach" (`POST /dj/logout-others`), a ceiling of account e-mails, the cookie's
  attributes in `application.properties` (`SessionCookieIT`), the Google choice valid 10 min. Waits for the owner: the merge; to try
  on an iPhone: Enter ("Idź") on `/login` = the password's login.
- **2026-11-19 — "Pytanie do gości" merged (#286 `main` = `f393a90`, #287 `dev`), V74–V76 (no rows moved):** with its review
  (`docs/history/review-2026-11-19-polls.md`) and the fixes P1–P15 (P13 left: the owner's local `scan2play` has V74–V76). After the
  deploy: Flyway at 76, curl `/p/<code>` without cookies to `</html>`, a try on a phone with the TV.
- **2026-11-19 — the poll edit merged (#288 `main` = `553390e`, #289 `dev`), no migration:** "Edytuj" on a question saved for later,
  "Popraw jako nowe" on one asked, in its own card (review `docs/history/review-2026-11-19-poll-edit.md`, E1–E5 fixed). After the
  deploy: a try on a phone.
- **2026-11-20 — `claude/admiring-cannon-fud6t3` (`71e7bbd`), PRs #290 (`main`) and #291 (`dev`), no migration:** "Ustawienia imprezy" in
  four groups (Impreza / Dla gości / Ty jako DJ / Dostęp, "Limity gości" under "Godziny"), "Życzenia" and "Pytanie" rows "›" like
  "Obsługa", the folded states by one rule (`06-7-look.md`; nothing set greyed, `.s2p-fold-state-empty`). Checked live on a computer
  (the owner's Chrome); browser 454/454, audit OK.
- **2026-11-20 — its review (`docs/history/review-2026-11-20-settings-groups.md`), uncommitted on the same branch:** P1–P7 fixed (the
  states' colours under `.page-dj` — the guest's poll line and "Obsługa" as they were, "Brak linku" grey; a limit near yellow again;
  "Ustawione: N" never split (a non-breaking space); a test that each group shows exactly its cards; docs). P8–P10, P13 done too (the owner: "zrób jak
  będzie najlepiej"): "Urządzenie" over this device for the staff, "Kiosk — Włączony", the rows' names `h3`, the hosts' state one line
  on 320 px. Waits for the owner: commit into #290 / #291, a look on a phone, the merge. P11 (the closed-party banner no
  heading before `h1`) and P12 fixed too: the two unit tests red locally (not charset — CRLF, the JVM's Polish locale) pass on Windows.
- **2026-11-20 — `claude/trendy-report` (from `main` after #290), merged into `dev` (#293), its PR to `main` open, no migration:** "Trendy próśb", variant 0
  (`docs/ideas.md` item 16, the analysis https://claude.ai/artifact/RuFmijFHyhEftWPBEtJAWk): a "Trendy" section in the owner's daily
  report and on `/dj/stats/report` — the top 20 songs of 30 days by parties (`SongTrendsService`, Section 5.1c). Waits for the owner:
  the merge into `main`; then 2–4 weeks of the report's numbers decide A (the DJs' card). "Plan imprezy" (`docs/ideas.md` item
  17) is apart, on `claude/event-plan`.
- **2026-11-20 — `claude/event-plan` (from `main` after #290, in the worktree `.claude/worktrees/event-plan`), pushed with PRs
  to `main` and `dev`, migration V77 (only adds):** "Plan imprezy", variants A and B (`docs/ideas.md` item 17): an event's plan of moments and its
  own two lists, the hosts' link `/h/e/{token}` without a CSRF token, "Użyj list na imprezie", the print; on the event's evening
  "Plan wieczoru" above the queue and "📋 z planu" in it (`docs/context/05-1i-wishes.md`). Waits for the owner: a look on a phone (the
  DJ's page, the hosts' page, the print, the panel on the evening), the review and the merge. "Trendy próśb" (variant 0)
  is merged into this branch too, so its PR to `dev` has no conflict.
- **2026-11-21 — `claude/affectionate-clarke-pb1e2w` (from `dev` = `9840a8d`), pushed, no PR, no migration:** the review of both
  (`docs/history/review-2026-11-21-event-plan-trends.md`) and its fixes P1–P17 (left: see "Poprawki" there). Waits for the owner: the
  read, a look on a phone (the hosts' page, the panel's card), then PRs to `dev` / into the branches' PRs to `main`; on Railway
  `TRENDS_EXCLUDE_PARTIES` = the owner's test parties' codes (optional).
- **2026-11-21 — "Zagrane samo" merged (#297 `main`, #298 `dev`), V78; tried on the owner's Windows 11 (VirtualDJ 8.5) — works**
  (`docs/history/review-2026-11-21-play-history.md`, "Próby na prawdziwych komputerach"). `claude/gallant-ritchie-rl80ux`:
  #299 merged; its rest (Serato 5.1, the look through the folders, the new window) was PRs #301 (`main`) / #302 (`dev`).
- **2026-11-22 — #301 / #302's branch (`claude/gallant-ritchie-rl80ux`) = its review (`review-2026-11-22-zagrane-serato-window.md`) + P3**
  (Serato 5.1: a song goes once `asset.dj_play_count` rose). **`claude/youthful-heisenberg-3whpcr` = it + P3's review**
  (`review-2026-11-22-p3-serato-play-count.md`: P1–P7 fixed — a play given to the song's newest load, kept for the session; P8 and
  4 checks on Serato open); the guide's "Zagrane samo" (`#deck`, its 4th picture `program.webp`). **Tried on Windows: P1, P3, P8,
  VirtualDJ work** ("Próba po review P3" there). Merged into `claude/peaceful-bardeen-pu9g7y` (from `main` after #305) with PRs #307
  (`main`) and #308 (`dev`), which replace #301 / #302 (close them after the merge). Waits for the owner: the merge; after it "Run
  workflow" of "Program DJ-a (deck-agent)" from `main`. Untried: Mac, P23, P24.
- **2026-11-22 — the library key without "- Prod. by X" merged (#305 `main` = `7f0c87c`, #306 `dev`), V79 (moves rows)**, review
  `review-2026-11-22-library-key-producer.md`; #303 / #304 to close. Held back by the deploy window: deploy by hand; then Flyway at 79.
- **2026-11-25 — `claude/sleepy-johnson-0z01t9` (from `main` = `e2d96d1`), PRs #309 (`main`) and #310 (`dev`), no migration:** the guide (PL / EN) caught up:
  a section "Przed imprezą" (the wishes, the plan moved there), "🗳 Pytanie do gości" in "W trakcie imprezy", the settings' four
  groups, "Poranne podsumowanie", the guest's side (the question, the theme, "Zarezerwuj tego DJ-a"). Then its format (the owner:
  one page, as recommended): the jumps stuck to the top (`guide-nav.js` marks the section read), "W skrócie" (five steps, links),
  the rarer details under "Więcej" (`<details>`). `kiosk-full-fits-820x1180` is red here on `main` too (this container's fonts?).
- **2026-11-25 — its review (`docs/history/review-2026-11-25-guide-format.md`) on the same branch:** P1–P8 fixed (a link `#deck`
  lands at once — Bootstrap's smooth scroll off until the first touch; the chip tapped stays marked; the focus not under the bar,
  `scroll-padding-top`; `role="list"`; three sentences; Section 6); then P9, P10, P12 as the owner chose (the phone's row to the
  screen's edges and faded, "W skrócie" one line a step — 710 → 420 px, the bar's height measured; P11 stays). 10 new scenarios seen
  red first; browser 476/477 (only `kiosk-full-fits-820x1180`). Waits for the owner: a look on a phone, the merge.
- **2026-11-25 — `claude/tender-bardeen-g98av8` (from `main` = `192d77b`), PRs #311 (`main`) and #312 (`dev`), migration V80 (only adds):** "Nowości" (`docs/news.md`):
  a card above the queue for the organisers of before (the three: Zagrane samo, Pytanie do gości, Plan imprezy; "✕" for good) and the
  news e-mail **only with consent** ("Wysyłaj mi nowości mailem" on the card, "Nowości mailem" in "Ty jako DJ"; the privacy policy PL / EN
  changed — "nigdy do newslettera" → only with consent). Sent by hand from `/dj/stats/news` (a try to `ADMIN_EMAIL` first).
- **2026-11-25 — its review (`docs/history/review-2026-11-25-news.md`) on the same branch:** P1–P10 fixed (a full e-mail queue no longer
  leaves "Wysyłka trwa" until a restart; "jednym kliknięciem" → the link asks first; the admin page and the closed card tested — two
  mutations had survived; "Wyślij do N" ≤ 200; the e-mail's text part). P3 as the owner chose: a DJ logged in while a news is there gets
  no e-mail of it (`markNewsKnown`) — **news 1's e-mail has nobody to go to**, the e-mail starts with news 2. P11, P12 done as
  recommended (the policy: "nigdy w innych celach reklamowych", no "sam"; "Zasady: Polityka prywatności, pkt 3" at the consent). Waits
  for the owner: the merge.
- **2026-11-25 — `claude/gracious-bell-iap7ms` (from `main` = `0316624`), PRs to `main` and `dev`, no migration:** "Zagrane samo" for
  Spotify in `deck-agent` (`NowPlaying`, `docs/ideas.md` item 20; works on the owner's Windows), its review
  (`review-2026-11-25-spotify.md`) and fixes P1–P17; then TIDAL and Windows' media sessions (`WindowsMedia`; tried on the owner's Windows: Spotify works, TIDAL read). PR #313
  (`dev`); the one to `main` refused by GitHub (500) — by hand. Waits for the owner: a try on Windows (a long
  pause) and on a Mac (macOS's question, "brak zgody"), a new `program.webp`, the merge; then
  "Run workflow" of deck-agent from `main`.
- **Waits for the owner (from #269):** after the first commit the deploy window holds back (Fri–Sun 18:00–6:00): does Railway
  deploy it on the workflow's re-run? UptimeRobot is set up (the owner, 2026-11-17; `docs/monitoring.md`).
- **Waits for the owner (the wishes, #263):** the three older Polish texts with "DJ" and a masculine verb (`guest.vote.gone`,
  `party.ended.subtitle`, `party.tip.title`, listed in `ImpersonalPolishTextsTest`); item 13 parked (the owner). To try on an
  **iPhone**: "⭐" and "Nowy link" in "Obsługa" come back where they were.
- **The plans:** everyone keeps Pro (`PLANS_ENFORCE=false`) until there are customers and a registered business; `/pro` public with
  one free card; the day's steps: `docs/plans-switch-on.md`; the owner's picks: `docs/history/session-handoff-2026-11-17.md`.
- **Only Cloudflare reaches the app** (since 2026-11-06): the Cloudflare Transform Rule `X-Scan2Play-Origin` and Railway's
  `ORIGIN_SECRET`; Railway's own address is 403. To change the secret: the rule and the variable together — between the two the
  site is 403 for a minute (`PROJECT_CONTEXT.md` Sections 8, 10).
- **The database's copies:** nightly to Cloudflare R2 (the Railway service `db-backup`, since 2026-11-16; 2026-11-17 03:19 UTC
  `scan2play-2026-11-17.dump`, 255 KB, done — the bucket itself and a restore not checked yet). By hand only before a migration that
  changes existing rows (`docs/database-backup.md`: the Postgres TCP proxy only for the copy, removed after it).
- **The repository is public** (`LICENSE`: viewing only) — GitHub Actions have no minute limit. Nothing secret in the repo, the docs
  or a commit — ever.
- **Cloud sessions:** the environment `scan2play` (personal) allows `www.scan2play.com.pl` — the curl of a release check; the
  Default environment allows no site. **Railway:** neither service sleeps; a spending limit of 15 USD (an alert at 10 USD);
  Healthcheck Path `/privacy`.

## Next

The owner's numbered ideas — what each is, what is built, what waits — are in `docs/ideas.md`. Open now: see "Start here".

## Later (not now)

A. **The library for guests:** first suggestions from the DJ's library before iTunes; a full "library only" mode (no AI, karaoke) only
   after talks with DJs. Asked again 2026-10-28 ("gość widzi tylko piosenki z biblioteki?" — and the cheaper "przyjmuj tylko z
   biblioteki", ✗ refused at once): the owner, "jednak nie warto". Analysed again 2026-11-04 (item 7): B (V57) and D0 (V58, the
   suggestions the DJ has marked) built; the full D (the library searched) and the hard mode wait for the owner.
B. "Opis klimatu" → "Opis imprezy" with a note in the panel that the AI reads it (the guide says it since 2026-10-28: optional, counts
   for more than the vibe, adds to it or — with the vibe "Dowolny" — replaces it).
C. (Done 2026-10-28: on a phone "Ukryj ✗", "+ Dodaj" and the search on one line.)
D. GitHub Actions' minutes: no limit while the repository is public; estimate the use from the workflows' history if it goes
   private (2 000 minutes, budgets at $0 — the tests would stop and Railway might hold `main`'s deploy).
E. From the history (`session-handoff-2026-10-24.md`): talks with DJs (its item 1), Gemini + Google Search for lyrics (item 6), the
   terms before `PLANS_ENFORCE` (item 5), the Gemini comparison for the next model (item 4).
F. **A light theme — only when someone asks** (the owner, 2026-10-28: not now). Dark is right where the app is used: a DJ's booth in
   a dark room (a bright panel dazzles and lights the room; DJ software is dark), guests' phones in the dark; the print is light
   already (its four themes). The cost: the colours are tokens (`app.css`), but the templates carry dark Bootstrap classes
   (`text-light` ×71, `btn-outline-light` ×60, `border-secondary` ×58, `bg-dark` ×28, `bg-black` ×24, `text-white` ×23) and
   `color-scheme: dark` on 22 pages — each moved to a token, then every contrast checked twice. When DJs of daytime outdoor events
   ask: first the guest page alone, light by the phone's own setting (`prefers-color-scheme`), the panel staying dark.

G. **The comment style "Ciekawostki"** (`CommentStyle.TRIVIA`, V65): built in `93938b2`, reverted after `GeminiComparison trivia`
   (2026-11-08): the world's hits right, the Polish songs about half made up ("Orła cień" sung by Kasia Stankiewicz, "Sen o
   Victorii" on "Detox") although the prompt forbids guessing. Parked (the owner); again only with Google Search, measured first.

## Waiting for the owner (not code)

- `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` in the `dev` environment (IntelliJ or a dev server): without them "To urządzenie" has no
  switch of the notifications (the owner's screenshot, 2026-10-28) — the same pair as production, never in the repo or a chat.
- Gemini's prepaid credits: top up or auto-reload (at zero every request goes to the DJ unchecked); a budget alert in Google Cloud.
- YouTube key "Klucz API 2" and `YOUTUBE_API_KEY` in IntelliJ, the Spotify app and `SPOTIFY_*` in IntelliJ — delete them; the
  disabled OAuth secret `****IgiS` — delete it.
- Search Console: request the indexing of `/dj-weselny`, `/kluby`, `/puby`.
- `SCAN2PLAY_GUEST_URL` in IntelliJ follows the local network's address — change it when the network changes.
- `server.forward-headers-strategy=FRAMEWORK` trusts `X-Forwarded-*` and the guests' limit `CF-Connecting-IP`: safe because only
  Cloudflare reaches the app (`OriginGuardFilter`, `ORIGIN_SECRET`) — keep the Cloudflare rule and the variable together.
