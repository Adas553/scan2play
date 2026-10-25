# Session Handoff — 2026-10-24 (evening)

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

- **2026-10-24 (late evening) — the state.** Released in #158 (deployed 18:40 UTC, SUCCESS, no 5xx): R2 (#155), C1 (#156), the
  handoff (#157). In `dev`, not released: #159 (the wedding page's search description without the couple's list); open: the PR of
  **the DJ's library** (V41, Pro — below, item 3). Before them, release #153 (`c80e2ab`) deployed 17:45 UTC, SUCCESS, no 5xx
  since; every deploy now waits for the healthcheck `/privacy` (passing since #145). Today's releases, the last first:
  - #153: #151 (hover: the logo that leads home turns the accent, the action button rises 2 px — not on a phone, not with
    `prefers-reduced-motion`), #152 (`/` is the home page for a logged-in DJ too, its start buttons "Przejdź do panelu", their
    visit not counted), #154 (the logo of the DJ's bar — panel, history, settings, staff — is that link too).
  - #150 (16:06): #148 (`PromptBuilder`, R1), #149 (S1 b live: an insult or a message instead of a song is a "mood").
  - #147 (14:35): #146 (the pages "Dla kogo" `/dj-weselny`, `/kluby`, `/puby`; the vibe "Dowolny" without "(ocenia AI)").
  - #145 (13:12): the review of 2026-10-21 — #140 docs, #141 S2 + P1 and the demo's limit, #142 O2, #143 U2 + U3, #144 T2 + U4 +
    O3 + Tomcat 11.0.27. Checked by the owner with curl: `/` sends no `Set-Cookie`; `/images/logo.svg` `public, max-age=86400`,
    `cf-cache-status: HIT`.
  - #139 (08:54): #131 (S1, V39), #132 (C2, C3), #134, #136 (`/dj/stats`, V40), #137 (the demo), #138 (`LICENSE`).
- **The repository is public** since 2026-10-24 (`LICENSE`: viewing only). GitHub Actions have no minute limit now; CI
  runs on PRs and on pushes to `main` only (O3, #144). Nothing secret in the repo, the docs or a commit — ever.
- **Railway (checked 2026-10-24, read only):** the sleep is off for `scan2play` and `Postgres` (the nightly jobs and the demo's
  04:55 reset run now; C1 is less urgent); a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy` (set 2026-10-24, passing since #145).
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

A. **The review of 2026-10-21 — what is left** (done and released: S1 a #131, C2/C3 #132, S2 + P1 #141, O2 #142, U2 + U3 #143,
   T2 + U4 + O3 + Tomcat #144; the details: the PRs and PROJECT_CONTEXT).
   - **S1 b + R1:** done — R1 #148 (`PromptBuilder`), S1 b #149, live since #150: an insult or a message is a "mood" (the owner's
     `GeminiComparison offensive`: the messages kept out 12/12, before 5/12 twice with a made-up song; 83 ok / 1 WRONG, before
     75 / 9; 1.4 s). Left to watch: a sarcastic comment may be sharp with the guest (style SARCASTIC); "somos hermanos" with the
     note "Salsa" accepted once of two (chance — the genre's rules did not change).
   - **R2:** done, #155 — the rule on comments in `AGENTS.md` (and its Copilot copy): why it is so now in the
     code; who and when in `docs/history/`, the PR, the commit. Tidy such comments when touching them anyway.
   - **C1:** done, #156 — the nightly cleanups (requests waiting more than 24 h cleared, the 30-day purge, old
     invitations) also run after a start (`atStart`); a failure is logged, never stops the start. Only what is a day old — never a
     party's queue of tonight.
   - **O4's google-genai 1.78:** left at 1.74 (the owner, 2026-10-24) — 1.75–1.78 bring nothing we use (continuation tokens,
     the Interactions API, streaming fixes, longer default timeouts — we set our own in `GeminiConfig`), no security fix. Back to it
     with a security fix or a feature we need; then a try on the real Gemini first (`GeminiComparison`): 1.77's automatic
     continuation in `generateContent` could ask again on its own.
B. **For the owner (not code):** try S1 b on a test party of your own (not the demo, which asks no AI): "pan młody to frajer"
   comes back to the field asking for a title; switch the notifications on in your own panel (the account `ADMIN_GOOGLE_ID`) for the alarm of O2;
   request the indexing of `/dj-weselny`, `/kluby`, `/puby` in Search Console; Cloudflare's "Managed robots.txt" (U4) if `/dj/`
   should be disallowed for every robot. (The wedding page's search description no longer names the
   couple's list, a Pro feature, beside "Za darmo" — the PR after #158.)
C. **Search Console (2026-10-24, the owner's screenshots):** a 404 for `/oauth2/authorization/google` (last crawled 2026-07-23 —
   a login link, disallowed in `robots.txt`; nothing to fix); `/privacy`, `/terms`, their `?lang=en` and `/jak-to-dziala?lang=en`
   not crawled yet; `/jak-to-dziala` crawled, not indexed — Google's own pace for a young site. The sleep (until 2026-10-24) may
   have slowed the crawl (a first request ~6 s, a failure when the app woke before its database), not caused these.

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
3. **The DJ's library — built 2026-10-24 (V41, released #161; improved in V42, the PR after #162).** "Twoja biblioteka" in the
   settings (the organiser alone) takes a rekordbox collection (File → Export Collection in xml format) — whole or one of its
   playlists ("Co wgrać", read from the file in the browser) — or an M3U / M3U8 playlist (≤ 50 000 tracks, ≤ 64 MB). Each waiting
   request shows "✓ Masz" (the title and an artist in common), "? Podobny" (the title alone) or "✗ Nie masz" (`util/LibraryKey`: no
   case, accents, punctuation, versions in brackets or after a dash, "feat. X", the order of the artists, a "The"). "Ukryj „Nie masz”" beside
   the queue's search hides the "✗" ones; "Podsumowanie wieczoru" lists "Nie masz w bibliotece" and its CSV has "W bibliotece".
   **V42 deleted the libraries uploaded under V41** (only a key was kept, the new ones cannot be made from it): upload the file again.
   To try it without a library of your own: the files of `src/test/resources/library/` (`rekordbox.xml` with the playlists
   "Wesele" and "Kluby / Piątek", `playlist.m3u8`) — ask for "Wilki - Baśka" (✓), "Dżem - Baśka" (?), "Eminem - Mockingbird" (✗); or a
   free rekordbox / Mixxx with a few mp3 files, exported. Left, if DJs ask: Serato's CSV, Traktor's NML, typos (`pg_trgm`), BPM and
   key at "✓", "the DJ does not have it" to the guest.
   **Ideas, only if the DJs ask:** a lyric matched to the version the DJ has (by the library);
   (notifications on the DJ's phone: built 2026-10-06, see the history 2026-10-16 — a store app only if Web Push is not enough). Other
   ideas raised: a "do not play" list of the couple / the DJ checked before the AI; a link for the couple to fill in their
   must-play / do-not-play list and the vibe before the wedding; parties planned ahead (several, each with its own QR code).
   Decided against (2026-10-03): "play later" without rejecting; a sound / a count in the tab title on a new request.
   Left for later (the owner, 2026-10-24): an AI assistant's chat in the panel for the DJ's questions — Gemini with the guide and
   the FAQ as its only source, ~1 grosz a question (less with context caching), a daily limit per DJ, a candidate for Pro; first
   see what DJs ask through the contact form (a search in the guide costs nothing and makes nothing up).
4. **The Gemini comparison** — last full run 2026-10-15 for `gemini-3.6-flash` (level low: 64 ok / 2 wrong, 1.6 s on average,
   ~$2.1 per 1000; the review of 2026-10-14, "Status poprawek"); 2026-10-24 the mode `offensive` for S1 b (A above), run by the
   owner's local agent with the key from IntelliJ's run configuration. `GeminiComparison` stays for the next model or prompt change. The
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
