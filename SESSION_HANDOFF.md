# Session Handoff — 2026-10-24

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package),
`session-handoff-2026-10-16.md` (2026-10-05 … 16: the staff, the plans, the review of 2026-10-14 and its fixes),
`session-handoff-2026-10-18.md` (2026-10-16 … 18: the artist and the title, the hosts' lists, the site's icon, the DJ's contact,
the dedications, the landing page, the TV screen, the language switch, the landing page's cut-off),
`session-handoff-2026-10-21.md` (2026-10-19 … 21: release #125, the CSP's week, Railway's sleep and its effects, word for word).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. The review of 2026-09-30: `docs/history/review-2026-09-30.md` (done; 2.3, one instance, left open).
The review of 2026-10-14: `docs/history/review-2026-10-14.md` (K1, W1–W15, the order of PRs, "Status poprawek").
The review of 2026-10-21: `docs/history/review-2026-10-21.md` (S, C, P, R, T, U, O; the order of PRs) — what is done and left: "Next", item A.

## Start here

- **2026-10-24 — the state.** `main` = `dev` = `7ff26ed` (#139), deployed on Railway 2026-10-24 08:54 UTC, SUCCESS. Since the
  review of 2026-10-21: #131 (S1: an unchecked request stays off the TV and the guests' list, V39), #132 (C2: a DJ whose session
  expired comes back through Google's login to the panel; C3: an unknown party or address is a 404), #134 (the QR poster and the
  TV screen in the DJ panel's language), #136 (the site's counter `/dj/stats`, V40; `ADMIN_GOOGLE_ID` set on Railway), #137 (the
  demo `/demo`: the parties `DEMO` in Polish and `DEME` in English, no Gemini, a visitor's request seen only by that visitor; the
  logo links to the home page on the public pages), #138 (`LICENSE`, "All rights reserved").
- **The repository is public** since 2026-10-24 (`LICENSE`: viewing only). GitHub Actions have no minute limit now, so O3 (CI
  running twice) is not urgent. Nothing secret in the repo, the docs or a commit — ever.
- **Railway (checked 2026-10-24, read only):** the sleep is off for `scan2play` and `Postgres` (the nightly jobs and the demo's
  04:55 reset run now; C1 is less urgent); a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy` (set 2026-10-24, from the next deploy).
  After Railway's incident of 2026-10-21 the owner replaced the Gemini key, the Google OAuth secret and the database's password,
  and removed the database's public TCP proxy.
- **The market:** after the posts on Facebook ~15 visits, 2 clicks on "Zacznij", 1 login — watch `/dj/stats`. Ideas: a short film
  for the posts; talks with 3–5 DJs (the questions: item 1).
- **Mind (the lessons of 2026-10-18 … 19):** (1) the `@WebMvcTest`s have no Spring Session filter: a form with a CSRF token rendered
  after the first 8 KB of a public page, for a visitor without a session, fails only in a test with `SessionRepositoryFilter`
  (`LandingPageSessionWebTest`) — such a form gets its token loaded in the controller and such a test. (2) A browser scenario that
  checks a page fits the screen measures against `document.documentElement.clientWidth`, never `window.innerWidth` (a phone widens
  `innerWidth` by what sticks out). (3) After a release, curl production without cookies to the page's end, not only for a 200.

## Next (the owner picks)

A. **The review of 2026-10-21 — what is left**, one PR per group. The owner's order (2026-10-24): this file and PROJECT_CONTEXT →
   S2 + P1 → O2 → U2 + U3; the rest after.
   - **S2 + P1:** done in #141 (2026-10-24): the landing page's invitation form is `GET /join?link=` → `/join/{token}`,
     no CSRF token, no session (`LandingPageSessionWebTest`: no `SESSION` cookie); `/webjars/**`, `/images/**`, `/favicon.ico`
     kept a day (`VersionedAssetCacheFilter`; after changing a picture purge Cloudflare's cache); `DemoParty.reset` starts the
     demos' daily limit again (`DemoPartyTest`). After the release: curl `/` without cookies — no `Set-Cookie`, the whole page —
     and the headers of `/webjars/bootstrap/css/bootstrap.min.css` and `/images/logo.svg`.
   - **O2:** done in #142 (2026-10-24; the owner's picks: both, after 3): 3 unchecked requests in a row → one push
     "⚠ AI nie ocenia próśb" to the devices of `ADMIN_GOOGLE_ID` (at most every 6 h) and a red bar above the queue of every
     panel (`X-Guest-Limits: ai-down`) until the AI answers. **For the owner:** switch the notifications on in your own panel
     ("To urządzenie" → Powiadomienia) on the phone that should get the alarm — the push goes only to devices subscribed by
     that account.
   - **U2 + U3:** done in #143 (2026-10-24): the owner's words — "Limit {0} piosenek wykorzystany. Daj szansę innym
     i spróbuj za {1}.", "Zagrana — prośba przechodzi do historii", "Czas na następną imprezę?"; `ImpersonalPolishTextsTest` keeps
     "-łeś" / "-łaś" / "Gotowy" out of the Polish bundle. The dedication comes back after an expired page
     (`GuestFormExpiredHandler`, `GuestNoSessionWebTest`).
   - **C1:** the nightly cleanups also after a start (less urgent: the sleep is off).
   - **T2 + U4 + O3 + O4's Tomcat:** done in the PR after #143 (2026-10-24): `HostPageSessionWebTest` (the hosts' form token
     is written right after `<form>`, at the page's top — within 8 KB however long the lists; it was never a live bug); the
     language switch 44 px, the FAQ's links in the action's cyan (4.0 → 9 : 1; browser scenarios); CI on PRs and on pushes to
     `main` only; Tomcat 11.0.27. **For the owner (U4):** Cloudflare's "Managed robots.txt" adds a second `User-agent: *` group
     before ours (Security → Bots) — switch it off if `/dj/` should be disallowed for every robot.
   - **R1** (`PromptBuilder` out of `SongEvaluationService` — best with S1 b), **R2** (a rule on comments in `AGENTS.md`),
     **O4's google-genai 1.78** (a try on the real Gemini, with the owner's yes).
   - **S1 b:** a step in the prompt that rejects an offensive "title" — first `GeminiComparison` with the real key (the owner's
     yes; the key only from IntelliJ's run configuration, never in the repo).
B. **Railway: Healthcheck Path** — set to `/privacy` (2026-10-24; Railway allows no dot, so not `/robots.txt`): check the next
   deploy's log for the healthcheck passing.
C. **The branch `claude/for-whom-pages`** (`/dj-weselny`, `/kluby`, `/puby`): waits for the owner's yes to its texts; to fix: "Co
   zagrałeś" (an impersonal form) and Pro features promised as free; bring `dev` in before its PR.

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

- **The nightly jobs now that the sleep is off:** a morning's log after 04:40 / 04:45 / 04:55 Polish time (02:40 … UTC until
  2026-10-25, then 03:40 …) — "Queue cleanup" only if a request had waited more than a day; the demo's reset every night.
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
