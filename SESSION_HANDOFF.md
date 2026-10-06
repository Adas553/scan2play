# Session Handoff — 2026-10-06

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`. The product is the requests-only party** ("Twój program DJ-a"; the owner's decision 2026-10-04: YouTube — 100 API
  searches a day shared by every party, its terms; Spotify — development mode, its policy). The full app is archived: tag
  `full-player-2026-10-04`, branch `archive/full-player` (both pushed), the clone `D:\Coding\scan2play-full` with its own database
  `scan2play_full` — leave them alone.
- **Stage 3 (YouTube goes) — done, all three packages committed and pushed, CI green:** `5a1bf79` (the player and Auto-Pilot,
  browser side), `e44d00f` (the server side, one kind of party, **V19** — deletes every party that is not requests-only with its
  data, drops the player's tables and the kind's columns; the owner tried it locally: "działa"), `1b461b8` (texts and docs: the
  landing page's guide, privacy and terms PL / EN without the YouTube API, `AGENTS.md` + the Copilot copy, `PROJECT_CONTEXT.md`
  cut to the current state — Sections 5.4 and 14 are pointers to the history, old migrations refer to them —, `REVIEW.md`).
- **After it:** `1fa2dff`, `c08ab71` (our logo on the pages, the QR prints and the favicon), `c83642d` (a request the AI rejects for
  a song that waits is a vote on it, with the song's verdict; a rejected request gives the guest's limit back — the server's limits
  still count it), `b2e9620` (the guest's own words under every song in the queue and the history; `util/GuestWords` gone).
- **Review of the removal (2026-10-05):** complete; leftovers tidied — the dead `vibe.ANY` text ("guests choose": `vibe.ANY` is now
  "the AI judges", `vibe.ANY.requests` gone), `DjResponse.withRequestId`, stale comments about the player, the DJ pick and the
  "guest" / "background" filters. The privacy policy (PL / EN) now names the guest's IP address: `GuestRequestLimiter` keeps
  it in memory with the party's code for the per-network limit, forgotten 10 minutes after the last request from that network
  (`guest.limit.per-ip-window-minutes`; `SmokeTest` ties the pages to it); not in the database, not in the application's logs.
- **Code review, class by class (2026-10-05), fixed:** the AI's answer read defensively (an unknown field is ignored; a decision
  other than `accepted` in any case is `rejected` — "Accepted" was saved and then shown nowhere); an empty request goes back to
  the form before any limit or the AI (with the AI down it was an empty row in the queue); `@DynamicUpdate` on
  `SongRequestEntity` (a vote that committed while the DJ marked the song played was lost — `SongRequestVotesIT`, seen red); the
  duplicate rule's songs by when they played; the logout deletes Spring Session's cookie `SESSION`; no `@Data` on
  `PartySettingsEntity`; the limits form rounds all three numbers alike (Infinity became 1); `Texts.oneLine` never splits an
  emoji; the cache names as constants; dead `authentication == null` branches and `REDIRECT_LOGIN` gone. Kept on purpose: the
  guest's own requests stay in the session (they outlive a deploy; two requests at the same moment may forget one — the form
  sends one at a time). Merged into `dev` (PR #5).
- **After the review (2026-10-05, the owner's picks), PR #8:** a song the DJ skipped ("Pomiń") is not saved again for 2 hours
  from the skip (`skipped_at`, V20; the owner: 12 h was too long) — the next guest hears "Tej piosenki DJ teraz nie zagra —
  wybierz inną" (option A of three; a song only cleared with the queue may come back); a skip by mistake is undone with "Cofnij"
  (a bar for 8 s after the skip) or "↩ Przywróć" in the history (`POST /dj/dashboard/restore`); Gemini gets the schema of its answer (`ANSWER_SCHEMA`); a first login in several tabs at once gives every tab the one
  party (`FirstLoginIT`, seen red: the UNIQUE owner_id); `AiHealthMonitor` writes "AI check: N of M guest requests … unchecked"
  to the log every 5 minutes while Gemini fails; a browser in English (or any language without a bundle) gets the English texts
  on a Polish server too (`spring.messages.fallback-to-system-locale=false` — no `messages_en` file needed: `messages.properties`
  is the English one). Tests: 267 unit, 24 database (PostgreSQL 18), 26 browser scenarios.
- **Live in production since 2026-10-06** (`PROJECT_CONTEXT.md` Section 10): `main` fast-forwarded to `dev` (`b61f4fd`, CI green),
  Railway built it, Flyway baselined the April schema and applied V2..V20, Hibernate validated, the app runs at
  `https://www.scan2play.com.pl`. The owner chose no backup (only their and friends' April tests, deleted by V18 / V19 anyway). Railway
  variables tidied (YouTube, Spotify and `SPRING_JPA_HIBERNATE_DDL_AUTO` removed). The owner's manual test passed: the DJ's login, a
  guest's request through the QR code, "Pomiń" → "Cofnij", the history; the live Gemini answered with `ANSWER_SCHEMA` (no "the AI
  could not be asked" in the log). **Both Railway services sleep** — kept on purpose for now (only friends use it); the log showed
  the cost: an app that woke before its database failed once and was restarted by Railway, and one request at 16:04 got an error
  page when the sleeping database dropped its connection. Before real customers: switch the database's sleep off (or at least
  `spring.flyway.connect-retries` for the start).
- **Notifications on the DJ's devices (2026-10-06, the owner's request; not deployed yet):** a switch "🔔 Powiadomienia na tym
  urządzeniu" per browser (the owner: a checkbox for the DJ); a **new** song on the list (not a vote) vibrates the DJ's phone with
  "🎵 Nowa prośba — <song>" (the owner's picks: new songs only, the title on the lock screen); several fold into "🎵 Nowe prośby: 3".
  Web Push (`PushNotificationService`, `zerodep-web-push-java`), **V21** `push_subscription`, `/sw.js`, the web app manifest and its
  icons (an iPhone takes notifications only from the dashboard added to the Home Screen, iOS 16.4+ — the switch says so), the privacy
  policy PL / EN. Tests: 287 unit, 27 database, 31 browser (5 new scenarios, seen red on a broken `push.js`); the app started locally
  with test keys (V21 applied, the manifest typed, `/sw.js` public, `/dj/push/*` behind the login and CSRF). **Not checked: a real
  delivery to a phone** (no push service reachable from the session) — needs the keys on Railway and a try on Android and an iPhone.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs?head_sha=…`;
  failed tests are public **annotations**: `.../check-runs/<id>/annotations`): Unit tests, Browser tests, Database tests. Green up
  to `b2e9620`. Unit tests also check that the Copilot copy of `AGENTS.md` matches it.

## Next (the owner picks)

1. **Show the requests-only party to DJs** and tell what they said. Questions (2026-10-01): how many requests per wedding and how
   many they do not have; is searching a pain at all; should a guest hear "the DJ does not have it" at once.
   New questions (2026-10-06): during the party, do you look at the laptop, or should the phone vibrate; would you upload your
   library.
2. **The owner liked (2026-10-06), not started:** a **"clear the history"** button (reconsidered — a weekend's guests' words should
   not stay on the DJ's list; only this party's played / skipped / rejected requests, never the waiting ones, and not a skip of
   the last 2 hours, or its block goes with it), best paired with a **summary of the night** to download first (CSV / PDF); the
   **DJ's branding on the guest page** (a logo — small, re-encoded on the server, kept in the database — and https links to
   Instagram / Facebook / a website; maybe on the QR print too; a candidate "premium" feature).
3. **Ideas, only if the DJs ask:** the DJ's library (an export from rekordbox / Serato / M3U) → "✓ you have it" beside each request,
   a lyric matched to the version the DJ has, "the DJ does not have it" at once, a list of what guests asked for and the DJ lacks;
   (notifications on the DJ's phone: built 2026-10-06, see Start here — a store app only if Web Push is not enough). Other
   ideas raised: a "do not play" list of the couple / the DJ checked before the AI; a link for the couple to fill in their
   must-play / do-not-play list and the vibe before the wedding; parties planned ahead (several, each with its own QR code).
   Decided against (2026-10-03): "play later" without rejecting; a sound / a count in the tab title on a new request.
4. **After go-live:** watch the log for "CSP violation" and "AI check" for a few days, then `CSP_ENFORCE=true` — first the Cloudflare
   Web Analytics beacon (the only report so far): switch it off in Cloudflare (Analytics → Web Analytics, the automatic setup for
   the site) or allow it in the CSP. Dependabot alerts and security updates: GitHub → the repository's Settings → "Advanced
   Security" (older UI: "Code security and analysis") → "Dependabot alerts" and "Dependabot security updates" → Enable.
5. **A Gemini comparison:** 2.5 Flash (now) against 3.5 Flash (better, ~4–5× the price; it takes `thinkingLevel`, not the
   `thinkingBudget` of `SongEvaluationService`) and a Flash-Lite, on 30–50 sample requests (hard Polish ones too), answers / time /
   cost side by side. The key's limits (Tier 1): 1 000 requests a minute, 10 000 a day — ~30–60 weddings a day.
6. From `REVIEW.md`: what is left is 2.3 (one instance) and 7.x (small tidy-ups; also the needless `hibernate.dialect` the start
   log warns about).

## Waiting for the owner (not code)

- **The notifications' keys:** run `VapidKeyGenerator` (`src/test/java/com/scan2play`, IntelliJ's green arrow) once, put its two lines
  `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` on Railway (and in IntelliJ's run configuration for a local try) — secrets, never in the
  repo or a chat; then switch the notifications on on an Android phone and on an iPhone (from the Home Screen) and send a request.

- **YouTube API key:** delete the key **"Klucz API 2"** in Google Cloud Console and the variable **`YOUTUBE_API_KEY`** in IntelliJ's
  run configuration (Railway: done 2026-10-06).
- **Spotify:** the Spotify app in the Developer Dashboard and the variables `SPOTIFY_*` and `SPOTIFY_TOKEN_KEY` in IntelliJ are no
  longer used — delete them (Railway: done 2026-10-06).
- **Dependabot** alerts and security updates: switch them on in GitHub (Next, item 4).
- **A budget alert** for the Gemini key's project in Google Cloud Billing (each request is paid now).
- **The OAuth client "Klient internetowy 1"** shows a warning in Google Cloud Console (2026-10-03): probably the disabled secret
  `****IgiS` — delete it (`****pfTe` is the one in use).
- **`SCAN2PLAY_GUEST_URL` in IntelliJ** holds the computer's address in the local network (the QR code's link): it changes with
  the network (2026-10-03: 192.168.68.54, the run configuration said 192.168.100.184). Change it in Run → Edit Configurations.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
- **`D:\Users`**: an empty directory tree left by a mistaken path in an earlier session; safe to delete by hand.
