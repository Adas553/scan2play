# Session handoff — moved 2026-11-17

From `SESSION_HANDOFF.md`, word for word: the "Start here" items of the branches released since 2026-11-13 — #263 (`claude/blissful-cannon-h66za3`
with `claude/quirky-faraday-mhc53m`: "Życzenia przed imprezą", V68, V69), #265 and #267 (`claude/happy-dijkstra-txy1s2`,
`claude/pensive-rubin-rpps6k`: the QR print, V70), #269, #270 and #271 (`claude/intelligent-fermi-oq8xqt`, `claude/bold-ride-a9dvlv`:
the review's fixes, V71, `/healthz`, the nightly copy, the deploy window, the backups in the privacy policy), #272
(`claude/awesome-sagan-hhc11y`: the guests' song suggestions), the owner's daily report (#251, #253, #254,
`claude/dazzling-heisenberg-oilwvm`, V65, V66) — and the previous state item; then the owner's picks on the pricing (2026-11-05,
2026-11-06). Every branch named here has no commit that `main` (`e4ff4d1`) lacks (checked 2026-11-17).

## "Start here", as it was

- **2026-11-16 — the guests' song suggestions, on `main` with #272, no migration:** each song's versions
  once, no songs without the typed words ("sweet car"), a single word completed to an artist of a hand-written list ("shak" →
  Shakira; `docs/ideas.md` item 14). The list's review every few months (next 2027-02) and the learning to come:
  `docs/song-suggestions-artists.md`. To try: the guest page on a phone, "shak", "podsia", "zene".
- **2026-11-16 — the review's fixes on `claude/intelligent-fermi-oq8xqt` (V71 → a database copy first), not merged:** (1) a reached
  limit's log line no longer names the guest's address or a wishes' link (`GuestRequestLimiter`); (2) the nightly copy to
  Cloudflare R2 (`tools/db-backup`, `docs/database-backup.md`); (3) `GET /healthz` for UptimeRobot (`docs/monitoring.md`); (4) no
  deploy of `main` Fri–Sun 18:00–6:00 (`deploy-window.yml`, `[deploy-now]`) and the party's day and the Free plan's AI checks kept
  across a restart (V71, `GuestLimitStore`). **Waits for the owner:** the R2 bucket, its token and the Railway service `db-backup`;
  the UptimeRobot account and its two monitors; after the first held commit, whether Railway deploys it on the workflow's re-run;
  a sentence on the copies (R2, 30 days) in the privacy policy. Also there: Pro's day of a party is 400 requests (Free 300;
  `guest.limit.per-party-daily-pro`, the queue's bound 400, the pricing table).
- **2026-11-13 — the state.** `main` = `d033d77` (#262: item 11 B0 — the DJ's contact for bookings, V67 — and A0; #261 the guide's
  pictures), deployed 2026-11-13 06:11 UTC, SUCCESS: Flyway 66 → 67, started in 5.3 s, no WARN, 0 × 5xx (166 requests), RAM 0.6 GB
  (Railway, read only); curl without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`. The items released: `docs/history/session-handoff-2026-11-13.md`.
- **The owner's daily report** (`AdminReportService`, V65) on `claude/dazzling-heisenberg-oilwvm`: an e-mail to
  `scan2playcontact@gmail.com` (`ADMIN_EMAIL`) when the owner sets on `/dj/stats/report` (V66; 9:10 daily by default), "Wyślij teraz". The e-mail has no
  organiser's address or name (the privacy policy); to put them in: a sentence in the policy's Brevo point first. V65, V66 → a database copy.
- **2026-11-14 — the QR print (`claude/happy-dijkstra-txy1s2`, reviewed: `docs/history/review-2026-11-14-print.md`, the fixes on
  `claude/pensive-rubin-rpps6k`), V70 → a database copy first:** #265 / #266 took only its first commit (`8162724`: on `main` the badge
  touches the code on a card and a tent); the rest in new PRs: the logo's room on every paper, "Białe tło pod logo" (V70), the 3 mm
  note, the cards' corner cut marks, the code 33 mm with the logo and three profiles.
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
