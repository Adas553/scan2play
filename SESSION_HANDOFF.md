# Session Handoff — 2026-10-21

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package),
`session-handoff-2026-10-16.md` (2026-10-05 … 16: the staff, the plans, the review of 2026-10-14 and its fixes),
`session-handoff-2026-10-18.md` (2026-10-16 … 18: the artist and the title, the hosts' lists, the site's icon, the DJ's contact,
the dedications, the landing page, the TV screen, the language switch, the landing page's cut-off).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. The review of 2026-09-30: `docs/history/review-2026-09-30.md` (done; 2.3, one instance, left open).
The review of 2026-10-14: `docs/history/review-2026-10-14.md` (K1, W1–W15, the order of PRs, "Status poprawek").

## Start here

- **2026-10-21 — the state.** `main` = `dev` in content. Release #125 (#124: nothing on the landing page sticks out sideways on a
  phone; #123: this file) deployed 2026-10-19 20:52 UTC, SUCCESS (Railway shows it SLEEPING now: asleep, not failed). The releases
  before it (#108 … #122: the dedications, the landing page, the TV screen, the language switch, the landing page's cut-off and its
  fix): `docs/history/session-handoff-2026-10-18.md`. Checked live on 2026-10-21 with curl, no cookies: the home page whole to
  `</html>` in Polish and with `?lang=en`, the footer, "Polski · English" at the top, "Masz link z zaproszeniem od DJ-a? Wklej go
  tutaj" under the last "Zbieraj prośby gości"; `/p/{code}/tv` 200 (an unknown code 404). No "Cannot create a session after the
  response has been committed" since 2026-10-18 19:12 UTC (the fix #121 was deployed at 19:42). Search Console: the owner has
  requested the home page's indexing (done by 2026-10-21).
- **The CSP's week (2026-10-15 … 21): not one "CSP violation"** in Railway's log — `CSP_ENFORCE=true` stays. No AI error either
  ("AI evaluation failed", "the AI could not be asked", 402): 10 requests asked that week (9 on 2026-10-16, 1 on 10-18), each
  answered by `gemini-3.6-flash`. The plans are not enforced (`PLANS_ENFORCE` unset), the notifications on.
- **Railway's sleep is still on** for both `scan2play` and `Postgres` (2026-10-21). The app started ~213 times in the week (~30 a
  day: any visit after a quiet spell wakes it, crawlers too). A sleeping app runs no `@Scheduled` job; the nightly ones left one
  line — "Queue cleanup: 15 request(s) waiting more than 24 h cleared from the queues" on 2026-10-20 02:40 UTC (the app happened to
  be awake) — and no "Song request cleanup" / "Staff invitation cleanup" (they log only when they deleted something, so that proves
  nothing). Waking shows the app starting before its database, "Connection to postgres.railway.internal:5432 refused" (Flyway, at
  start; Railway restarts the app and it is up a moment later): 2026-10-15 16:45, 10-16 10:55, 10-18 02:19, 10-19 02:24, 20:53 (the
  deploy of #125), 22:39 and 22:40, 10-20 15:01 and 18:44 UTC. Decided (the owner, 2026-10-18): switch the sleep off rather than
  change the code; with both awake these should stop.
- **Mind (the lessons of 2026-10-18 … 19):** (1) the `@WebMvcTest`s have no Spring Session filter: a form with a CSRF token rendered
  after the first 8 KB of a public page, for a visitor without a session, fails only in a test with `SessionRepositoryFilter`
  (`LandingPageSessionWebTest`) — such a form gets its token loaded in the controller and such a test. (2) A browser scenario that
  checks a page fits the screen measures against `document.documentElement.clientWidth`, never `window.innerWidth` (a phone widens
  `innerWidth` by what sticks out). (3) After a release, curl production without cookies to the page's end, not only for a 200.

## Next (the owner picks)

0. **"⚠ Sprawdź"** — built 2026-10-07 (live). Still a case for the Gemini comparison (item 4).

1. **Show the requests-only party to DJs** and tell what they said. Questions (2026-10-01): how many requests per wedding and how
   many they do not have; is searching a pain at all; should a guest hear "the DJ does not have it" at once.
   New questions (2026-10-06): during the party, do you look at the laptop, or should the phone vibrate; would you upload your
   library.
   Later (2026-10-07, the owner): **the DJ's login.** Only Google today — no passwords kept, no table of users (the DJ is the
   Google subject, `owner_id`), Google's own 2FA; it began with the YouTube API, gone since 2026-10-04. Ask: does any DJ lack a
   Google account or mind it; does it work on an iPhone from the Home Screen (Next, the iPhone test). If they ask, by effort:
   "Sign in with Apple" (an Apple Developer account, $99 a year), a sign-in link by e-mail (a mail service — Resend / Postmark —
   and a table of accounts), e-mail and password (not advised: hashing, resets, lockouts). Until then: Google only.
2. **The owner liked (2026-10-06):** the **"clear the history"** button — built 2026-10-07 (the history 2026-10-16) —, best paired with a
   **summary of the night** to download first (built 2026-10-08, the history 2026-10-16); the
   **DJ's branding on the guest page**: the profiles are built (2026-10-07, the history 2026-10-16); left: **the DJ's logo** (small,
   re-encoded on the server to PNG, kept in the database, served from our address — the CSP needs no change; the privacy policy
   to update). A candidate "premium" feature.
3. **Ideas, only if the DJs ask:** the DJ's library (an export from rekordbox / Serato / M3U) → "✓ you have it" beside each request,
   a lyric matched to the version the DJ has, "the DJ does not have it" at once, a list of what guests asked for and the DJ lacks;
   (notifications on the DJ's phone: built 2026-10-06, see the history 2026-10-16 — a store app only if Web Push is not enough). Other
   ideas raised: a "do not play" list of the couple / the DJ checked before the AI; a link for the couple to fill in their
   must-play / do-not-play list and the vibe before the wedding; parties planned ahead (several, each with its own QR code).
   Decided against (2026-10-03): "play later" without rejecting; a sound / a count in the tab title on a new request.
4. **The Gemini comparison** — last run 2026-10-15 for `gemini-3.6-flash` (level low: 64 ok / 2 wrong, 1.6 s on average,
   ~$2.1 per 1000; the review of 2026-10-14, "Status poprawek"). `GeminiComparison` stays for the next model or prompt change. The
   key's limits (Tier 1): 1 000 requests a minute, 10 000 a day — ~30–60 weddings a day.
5. **Before `PLANS_ENFORCE`:** the terms (the staff, the hosts' link, the plan Pro) are the owner's text; then the plans can be
   enforced (PROJECT_CONTEXT 4.1 / 13).
6. **A song found by a line of its lyrics — Gemini's Google Search** (the owner, 2026-10-16: "zapisz to do zrobienia"). Production
   2026-10-16: "Dziewczyno jak wino uderzasz do głowy" gave four different made-up songs; since #96 the guest is asked for the artist
   and the title, the AI still tries lyrics. The plan: the first call as today (no search); a second call with "Grounding with Google
   Search" only when the answer says `requestKind` "lyrics" (`DjResponse.isLyrics()`) or its song shares no word with the guest's
   text (`SongNames.sharesNoWord`, the "⚠ Sprawdź" case). Before building: (a) a test with `GeminiComparison` — the four requests of
   2026-10-16 and similar lines, without and with the search: right / wrong, time; it needs the Gemini key from IntelliJ's run
   configuration (the owner's yes, read by a script, never written) and the right title of that song from the owner; top up the
   prepaid credits first (2.95 zł on 2026-10-16); (b) how Google bills it for Gemini 3 — per request with the search on, or per
   search the model makes ("5,000 free search requests per month, then $14 per 1,000"; the docs and third parties differ); (c) how
   many requests are lyrics, from Railway's log. Weak spots: a lyric the model labels a title and matches to a made-up title skips
   the search; the guest waits 1–3 s longer on a searched request.
7. **Ideas from Rekwest (rekwest.app, the biggest competitor; the owner, 2026-10-16: "dopisz")** — features to borrow, never their
   look, texts or screenshots. Rekwest: Free $0 (unlimited, ads), Professional $4.99 / month, Enterprise $29.17 / month (2026-10-16);
   no AI on their page — ours (the vibe filter, the comments, "⚠ Sprawdź"), the hosts' lists by a link and Polish are what to show.
   - **A dedication with a request:** built 2026-10-17 (the history 2026-10-18). Left, if DJs ask: the dedications in the history and the
     evening's summary (today only the queue shows them).
   - **The DJ's contact after the party:** built 2026-10-16 (the history 2026-10-18). Left, if DJs ask: a phone number or a website as a contact
     (the profiles are Instagram / Facebook / TikTok only).
   - **A TV screen:** built 2026-10-18 (#113, released in #114; the history 2026-10-18). The owner's picks: public (`/p/{code}/tv`, no login on
     the TV), the ten most wanted of the waiting songs (the guests' list's order, `GuestQueueService.mostWanted`), plan Free. The QR
     code and the address on the left, the songs with their 👍 on the right, Polish and English at once like the poster; no guests'
     words, no dedications, no session; `tv.js` fetches the list every 10 s and keeps the screen awake; "📺 Ekran na TV" beside
     "Drukuj plakat i karty" in the panel's card "Kod QR" (the staff's too). Tests: `TvScreenWebTest`, `GuestQueueServiceTest`,
     `DashboardPageRenderTest`; browser `tv-fits-a-full-hd-tv`, `tv-fits-a-small-tv` (a laptop's window of 1280 × 720 shows
     ~570 px), `tv-list-refreshes`, `tv-keeps-the-list-when-offline`. Left, if DJs ask: the DJ's logo or a "now playing" line.
   - **The landing page:** built 2026-10-17 (the history 2026-10-18). Left: pages of their own for whom (wedding DJs, clubs, pubs, karaoke —
     for search engines, in the sitemap).

## Waiting for the owner (not code)

- **Railway's sleep:** still on (2026-10-21) — switch `sleepApplication` off for `scan2play` and `Postgres` (Settings → Deploy →
  Serverless). Next morning: the log after 04:40 / 04:45 Polish time (02:40 / 02:45 UTC until 2026-10-25, then 03:40 / 03:45) — a
  "Queue cleanup" line only if a request had waited more than a day.
- **Old PRs to `main`:** #2 and #7 (April) — close them.
- **Gemini's prepaid credits:** top up or turn on auto-reload (at zero the API answers 402 and every request goes to the DJ
  unchecked); a spending limit for the project in AI Studio.
- **`server.forward-headers-strategy=FRAMEWORK`** trusts `X-Forwarded-*`: safe while Railway's own address cannot be reached past
  Cloudflare — keep it so.

- **YouTube API key:** delete the key **"Klucz API 2"** in Google Cloud Console and the variable **`YOUTUBE_API_KEY`** in IntelliJ's
  run configuration (Railway: done 2026-10-06).
- **Spotify:** the Spotify app in the Developer Dashboard and the variables `SPOTIFY_*` and `SPOTIFY_TOKEN_KEY` in IntelliJ are no
  longer used — delete them (Railway: done 2026-10-06).
- **A budget alert** for the Gemini key's project in Google Cloud Billing (each request is paid now).
- **The OAuth client "Klient internetowy 1"** shows a warning in Google Cloud Console (2026-10-03): probably the disabled secret
  `****IgiS` — delete it (`****pfTe` is the one in use).
- **`SCAN2PLAY_GUEST_URL` in IntelliJ** holds the computer's address in the local network (the QR code's link): it changes with
  the network (2026-10-03: 192.168.68.54, the run configuration said 192.168.100.184). Change it in Run → Edit Configurations.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
