# Session Handoff — 2026-11-07

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
`session-handoff-2026-10-30.md` (the "Start here" of 2026-10-28 … 30: the state, the releases and the use of those days — word for word), `session-handoff-2026-11-06.md` (the "Start here" of 2026-11-04 … 05: the state, the releases #219 … #236, the review of 2026-11-05), `session-handoff-2026-11-07.md` (the state of 2026-11-06, H2–H4, the pricing's state and the review of PR #239 — released with #246 … #249), `session-handoff-2026-11-13.md` (the state of 2026-11-07, the releases #261, #262).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-11-13-wishes.md`: "Życzenia przed imprezą", before its PR).

## Start here

- **2026-11-13 — the state.** `main` = `d033d77` (#262: item 11 B0 — the DJ's contact for bookings, V67 — and A0; #261 the guide's
  pictures), deployed 2026-11-13 06:11 UTC, SUCCESS: Flyway 66 → 67, started in 5.3 s, no WARN, 0 × 5xx (166 requests), RAM 0.6 GB
  (Railway, read only); curl without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`. The items released: `docs/history/session-handoff-2026-11-13.md`.
- **The owner's daily report** (`AdminReportService`, V65) on `claude/dazzling-heisenberg-oilwvm`: an e-mail to
  `scan2playcontact@gmail.com` (`ADMIN_EMAIL`) when the owner sets on `/dj/stats/report` (V66; 9:10 daily by default), "Wyślij teraz". The e-mail has no
  organiser's address or name (the privacy policy); to put them in: a sentence in the policy's Brevo point first. V65, V66 → a database copy.
- **Only Cloudflare reaches the app — on since 2026-11-06 22:47 UTC.** The owner set the Cloudflare Transform Rule `X-Scan2Play-Origin`
  and Railway's `ORIGIN_SECRET` (the first value showed in a screenshot and was replaced at once); checked: the site 200 through
  Cloudflare, Railway's own address (`oqz5xl6q.up.railway.app`, `153.52.255.99`) 403. To change the secret: the Cloudflare rule and the
  variable together — between the two the site is 403 for a minute (`PROJECT_CONTEXT.md` Sections 8, 10).
- **The database's copy:** the owner took one on 2026-11-06 (`scan2play-2026-11-06.dump`, 250 KB, on their computer); the Postgres TCP
  proxy was removed after it (checked: only the private endpoint). Weekly, and before a release with a migration
  (`docs/database-backup.md`: the proxy only for the copy).
- **The repository is public** (`LICENSE`: viewing only) — GitHub Actions have no minute limit. Nothing secret in the repo, the docs
  or a commit — ever.
- **Cloud sessions:** the environment `scan2play` (personal) allows `www.scan2play.com.pl` — the curl of a release check; the
  Default environment allows no site.
- **Railway:** neither service sleeps; a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy`.
- **2026-11-14 — PRs #265 (`main`), #266 (`dev`) from `claude/happy-dijkstra-txy1s2`, V70 → a database copy first:** the print's logo
  keeps its size and place on every paper; "Białe tło pod logo" (V70); the editor warns of a card's part within 3 mm of the cut; the
  cards' corner cut marks, the table number 3 mm in; a card with the logo and three profiles: the code 33 mm (every part 3 mm in).
- **2026-11-13 — PRs to `main` and `dev` from `claude/blissful-cannon-h66za3` (V68, V69 → a database copy first):** the reviewed
  `claude/happy-carson-5odcet` (`docs/ideas.md` item 12 — "Życzenia przed imprezą": `/w/{token}`, `/dj/wishes`, the invitation's print,
  WISHES, Pro; the hint to vote 👍 above the guests' list, on the kiosk and the TV) with the review's fixes on top
  (`docs/history/review-2026-11-13-wishes.md`: W1 reopening kept to five open, 20 events a party; W2–W3 tests; D1, D3, D4, D6, S1–S3;
  D2, D5 left, said why); then the owner's notes: no page of the wishes or the settings jumps (no anchors, `scroll-restore.js`, the
  answer in a bar at the bottom, the settings' folds remembered), the guests' hint shorter, and an event's own look (V69). Waits for the owner: the three older Polish texts with "DJ" and a masculine verb (`guest.vote.gone`,
  `party.ended.subtitle`, `party.tip.title`, listed in `ImpersonalPolishTextsTest`). Item 13 waits for the owner's picks. To try: an
  invitation printed, its link on a phone, "⭐" from the wishes before a party.
- **2026-11-13 — the second review's fixes on `claude/quirky-faraday-mhc53m`** (that branch = `claude/blissful-cannon-h66za3` + the fixes;
  `docs/history/review-2026-11-13-wishes-2.md`): the look on a line of its own, the invitation's look the event's, `pagehide`, "Dodaj 10"
  sends what it names, the toast goes by itself, "Zobacz życzenia" to the event. To try on an **iPhone**: "⭐" and "Nowy link" in
  "Obsługa" come back where they were.

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

**State:** (1)–(4) built and released (#239 and its review, on `main` since #246); (5) not switched — everyone keeps Pro
(`PLANS_ENFORCE=false`) until there are customers and a registered business; the day's steps: `docs/plans-switch-on.md`.

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
