# Session Handoff — 2026-11-06

The current state only: the branch, what waits for the owner, what comes next. **Kept short: the whole file ≤ 15 KB, "Start here"
≤ 4 KB, an item ≤ 4 lines.** After each release move to `docs/history/session-handoff-<date>.md`, word for word: the released
items and their checks (migrations applied, the deploy's lines), the previous "state" item, a review's fixed points, the owner's
words behind a decision — keep here only `main` and its deploy, open PRs and branches, what waits for the owner, the next step.
The owner's numbered ideas ("item N") are in `docs/ideas.md`; traps seen before in `CLAUDE.md` ("Mind").

**The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`,
`session-handoff-2026-10-16.md`, `session-handoff-2026-10-18.md`, `session-handoff-2026-10-21.md`, `session-handoff-2026-10-24.md`
(2026-10-22 … 24: the review of 2026-10-21 and its releases, the DJ's library, the ideas list — items 1–7 — word for word),
`session-handoff-2026-10-26.md` (2026-10-26 … 28: the DJ's logo, the QR poster's editor and look, the panel's review — word for word),
`session-handoff-2026-10-30.md` (the "Start here" of 2026-10-28 … 30: the state, the releases and the use of those days — word for word), `session-handoff-2026-11-06.md` (the "Start here" of 2026-11-04 … 05: the state, the releases #219 … #236, the review of 2026-11-05).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-11-05.md`: the releases #219 … #236).

## Start here

- **2026-11-06 — the state.** `main` = `32af3bf` (the release merge #246; #245 the same into `dev`), deployed 2026-11-06 22:34 UTC,
  SUCCESS. Checked (Railway, read only): Spring Boot 4.1.2, Tomcat 11.0.27, "Schema is up to date" (no migration), "Origin guard on".
  It brought the full review of 2026-11-06 (`docs/history/review-2026-11-06.md`, nothing critical): the logo's upload reads its body
  only once it has its turn (a burst of uploads could fill the 512 MB heap); the guests' limit per address 30 → 60 a 10 minutes (the
  owner: a room on one Wi-Fi); "Ostatnie wieczory" whole on a phone; the pages "Dla kogo"' ways back 44 px; no "👍 0" on the TV; stack
  traces cut to 30 lines a cause; **Spring Boot 4.1.2** (4.0's free updates end 2026-12-31; 4.2 is at RC2 — 4.1 holds until ~2027-07);
  **`OriginGuardFilter`** (Railway's own address answered past Cloudflare).
- **Only Cloudflare reaches the app — on since 2026-11-06 22:47 UTC.** The owner set the Cloudflare Transform Rule `X-Scan2Play-Origin`
  and Railway's `ORIGIN_SECRET` (the first value showed in a screenshot and was replaced at once); checked: the site 200 through
  Cloudflare, Railway's own address (`oqz5xl6q.up.railway.app`, `153.52.255.99`) 403. To change the secret: the Cloudflare rule and the
  variable together — between the two the site is 403 for a minute (`PROJECT_CONTEXT.md` Sections 8, 10).
- **The database's copy:** the owner took one on 2026-11-06 (`scan2play-2026-11-06.dump`, 250 KB, on their computer); the Postgres TCP
  proxy was removed after it (checked: only the private endpoint). Weekly, and before a release with a migration
  (`docs/database-backup.md`: the proxy only for the copy).
- **The review's hypotheses H2–H4** — fixed on `claude/adoring-edison-dr87gy` (restarted from `main` after #246): H2 a login's return
  from Google that fails for a person logged in already (a second "Zaloguj" replaced the first) opens the panel, not "Błąd logowania"
  (`SecurityConfig.loginFailure`, `LoginReturnTest`); H3 a library's file must arrive within 5 minutes, or 408 "Wgrywanie trwało za
  długo" and the one turn of reading is free again (`DjLibraryController.READ_LIMIT`); H4 past the party's daily limit a request from a
  new address is refused without a window kept for it (`GuestRequestLimiter.tryAcquire`, an IPv6 flood no longer fills the party's map).
- **The repository is public** (`LICENSE`: viewing only) — GitHub Actions have no minute limit. Nothing secret in the repo, the docs
  or a commit — ever.
- **Railway:** neither service sleeps; a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy`.

## Pricing and the Free plan's limit (the owner's picks, 2026-11-05)

The analysis (competitors, Pro / Free checked with the code, the mock-ups, the AI's cost): the artifact "Cennik i limity Free"
(https://claude.ai/artifact/HmAVim6owQkhpoe6hTg1vZ, private). The AI costs ~$0.002–0.003 a request from 2027 (half until 2026-12-31);
the logs Railway keeps (since 2026-10-27) hold no guest's request — nobody but the owner has tested it, so there is no real party size.
The owner's picks:
1. **Free: 150 AI evaluations a party an evening, soft** — past it a request still reaches the DJ, unchecked (the path of "AI down",
   `withoutTheAi`); the hard 300 a day stays for everyone; Pro: every request checked. Counted: what goes to Gemini (moods and repeats too; a failed
   call given back); not: 👍,
   refusals before the AI, the demo; the kiosk counts. The guest sees nothing of a limit; the DJ a quiet line at 80 %, a bar past it,
   "bez oceny AI" on those requests — no Pro offer during the party; the next day the organiser's panel ("Wczoraj 212 próśb, AI oceniło
   150. W Pro AI ocenia każdą prośbę.", "Zobacz, co daje Pro"; only until the next evening's first request). Property `guest.limit.free-ai-per-party` (150, 0 = off), only while enforced.
2. **The pricing page: variant A** — Free "0 zł zawsze", Pro "WCZESNY DOSTĘP" with "Napisz do nas" (no price, no payment, no date), a
   comparison table, FAQ ("Ile będzie kosztować Pro?" — not known yet; we write before anything is paid).
3. **A 404 while `PLANS_ENFORCE=false`** (as `/pro` now); links: the footer ("Cennik"), the landing page's FAQ "Ile to kosztuje?", the
   account menu ("Twój plan: Free · Cennik", the organiser only); never in the queue.
4. **`contact@scan2play.com.pl` everywhere public** (`/pro`, the footer's `contact.email`, terms, guide, the privacy policy's contact);
   Gmail only as Reply-To (and named so in the privacy policy).
5. **30 days everywhere**: a gift of Pro for the accounts that exist on the switch (`PLANS_GIFT_UNTIL` = the switch + 30 — see the
   state below) and `PLANS_TRIAL_DAYS=30` for new ones.
6. The address: `/pro`, with `/cennik` (PL) and `/pricing` (EN) as the same page, one canonical; in the sitemap only once enforced.

**State:** (1) done on `claude/compassionate-cori-g35pvj` (template tests green: `ProPageRenderTest` with the new
`everyPlanFeature_isOnThePage`, `GuidePageRenderTest`, `DashboardPageRenderTest`, `SmokeTest`; the browser tests — `design.js` has the new
address). (2) done on the same branch: `/pro` is the pricing page (variant A; `/cennik`, `/pricing` redirect to it), the footer's
"Cennik", the landing page's "Zobacz cennik →", the account menu's "Twój plan: Free · Cennik" — all only while enforced. Unit tests
1131 + the new ones green, the browser tests 381 / 381 (in a cloud session: `--no-render --work <a copy where mvn test rendered the
pages>`, `--chrome /opt/pw-browsers/…/chrome --no-sandbox`, `TMPDIR=/tmp/s2p` — the copy's mvnw cannot download Maven there; `mvn`
is installed). (3) done on the same branch: `FreeAiLimit` (PROJECT_CONTEXT 5.2, "The Free plan's AI checks") — the request past
150 goes to the DJ unchecked, the panel's line at 80 % and bar at the limit (polled), the settings' badge, the organiser's note the next
day with "Zobacz, co daje Pro"; unit tests 1141 green (`FreeAiLimitTest`, `SongEvaluationServiceTest`, `DjDashboardControllerGuestLimitsTest`,
`DashboardPageRenderTest`), the browser tests 382 / 382 (`free-ai-limit-follows-the-poll` seen red on the old `polling.js`). Off for
everyone while `PLANS_ENFORCE=false`. The next day's note: a render test (`theDayAfter_…`), no browser scenario. (4) done on the same branch, changed from
the plan: **the gift is a variable, not a migration** — `PLANS_GIFT_UNTIL` (a day): a V65 would have written the 30 days at the next
deploy, long before a switch; the panel's line "Do … masz Pro w prezencie" (`PlanServiceTest`, `DashboardPageRenderTest`); the terms'
new section 5 "Plany Free i Pro" (PL, EN; true now and after); the privacy policy needed nothing (the plan and the trial were in it).
(5) **not switched — the owner, 2026-11-05: everyone keeps Pro (`PLANS_ENFORCE=false`) until there are customers and a registered
business**; the day's steps: `docs/plans-switch-on.md`. Unit tests 1143, browser tests 382 / 382.

**The review of PR #239** (2026-11-05, `docs/history/review-2026-11-05-pr239.md`; the owner agreed to every proposal): fixed on
`claude/pensive-carson-guv7kw`, which is the PR's branch plus seven commits — to be merged into the PR or opened as its own PR to `dev`.
W1 no "Free" in the "bez oceny AI" tooltip while the plans are off; W2 `X-Guest-Limits-Use`'s third number only where the Free limit
applies (an open panel's old `polling.js` takes two); W3 the pricing page true to the code (Pro: every request checked; 300 a day for
both); W4 the terms' section 5 true before and after the switch; W5 (a) the Free card says requests past the limit are not on the
guests' list nor the TV; W6 an unchecked request for a song played lately refused by the code (`playedLately`); W7 a failed AI call
given back; W8 the next day's note only until the evening's first request, and only when some came unchecked; D1–D12 (a 301 for
`/cennik`, the table on a phone, the staff not told of the plan, the count by the party's row so "Zmień kod" keeps it, the trial during
the gift starts at its end, `PLANS_GIFT_UNTIL` badly written names itself). Unit tests and the browser tests green (the numbers: the
last commit's message). Then: the switch, on a Tuesday / Wednesday morning (`docs/plans-switch-on.md`; rollback `PLANS_ENFORCE=false`).

## The pricing page before the switch, and the trial's abuse (the owner's picks, 2026-11-06)

1. **Variant B**: `/pro` (and `/cennik`, `/pricing`) is public while `PLANS_ENFORCE=false` too — one card "Za darmo", "0 zł", every
   feature, "Zacznij za darmo", the questions "Czy tak zostanie?" (the terms' section 5, `/terms#plans`), "Czy potrzebuję karty?",
   "A goście?"; no word of Pro (PROJECT_CONTEXT 6.7). "Cennik" beside the language at the top of `/` and the pages "Dla kogo", in the
   footer of the site's pages (`pricingLink`), the landing page's "Zobacz cennik →"; `/pro` in the sitemap always. The canonical address
   stays `/pro` (the panel links it). Tests: `ProPageRenderTest.withThePlansOff_everythingIsFree`, `SmokeTest`, `ForWhomPagesWebTest`,
   `GuestPageRenderTest`; browser scenarios `pricing-at-the-top-*` (320 px), `pro-page-plans-off-*`.
2. **The trial**: kept as it is (30 days from the account, or from the gift's end). Its abuse costs little (≤ 150 AI checks an evening,
   a morning e-mail) and gains nothing while Pro is free on request; the terms' one sentence against it is a step of
   `docs/plans-switch-on.md` (added on the day of the switch, not before); a card for the trial when payments come.
3. **The privacy policy**: the trial's hash is kept "while Scan2Play offers a trial", on the legitimate interest (was: with no end).

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

## Waiting for the owner (not code)

- `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` in the `dev` environment (IntelliJ or a dev server): without them "To urządzenie" has no
  switch of the notifications (the owner's screenshot, 2026-10-28) — the same pair as production, never in the repo or a chat.
- Gemini's prepaid credits: top up or auto-reload (at zero every request goes to the DJ unchecked); a budget alert in Google Cloud.
- Old PRs to `main`: #2 and #7 (April) — close them.
- YouTube key "Klucz API 2" and `YOUTUBE_API_KEY` in IntelliJ, the Spotify app and `SPOTIFY_*` in IntelliJ — delete them; the
  disabled OAuth secret `****IgiS` — delete it.
- Search Console: request the indexing of `/dj-weselny`, `/kluby`, `/puby`.
- `SCAN2PLAY_GUEST_URL` in IntelliJ follows the local network's address — change it when the network changes.
- `server.forward-headers-strategy=FRAMEWORK` trusts `X-Forwarded-*` and the guests' limit `CF-Connecting-IP`: safe because only
  Cloudflare reaches the app (`OriginGuardFilter`, `ORIGIN_SECRET`) — keep the Cloudflare rule and the variable together.
