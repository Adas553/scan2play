# Session Handoff — 2026-10-16

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package),
`session-handoff-2026-10-16.md` (2026-10-05 … 16: the staff, the plans, the review of 2026-10-14 and its fixes).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. The review of 2026-09-30: `docs/history/review-2026-09-30.md` (done; 2.3, one instance, left open).
The review of 2026-10-14: `docs/history/review-2026-10-14.md` (K1, W1–W15, the order of PRs, "Status poprawek").

## Start here

- **2026-10-16 — the site's icon for Google Search, on the branch `claude/site-icon` from `origin/dev`; a PR to `dev`.** The owner: Google
  showed a grey globe beside scan2play.com.pl. `/favicon.ico` (16 / 32 / 48 px) was there, but no page named it — Google needs
  `<link rel="icon">` on the home page. The `seo` fragment (the public pages) names `/favicon.ico`, `images/icon-192.png` and the
  apple-touch-icon. Tests: 723 unit (`SmokeTest`, red before), 129 browser. **The owner, after the release:** Search Console → URL
  Inspection of the home page → "Request indexing" (Google takes days to weeks otherwise).
- **2026-10-16 — the hosts' lists count their entries, on the branch `claude/host-lists-limit` from `origin/dev`; a PR to `dev`.** The
  owner: "co jak DJ wpisze 100 piosenek?" — a list keeps 100 (`SongList.MAX_LINES`, the owner: "zostaw 100"; the lists never reach the
  AI), but 120 lines were saved as 100 without a word. Now under each list "N z 100 pozycji" as it is typed and a warning over 100
  (`js/song-list-count.js`, on "Ustawienia imprezy" and `/h/…`); the hosts' page after such a save says the rest did not fit. Tests:
  723 unit, 129 browser (`host-lists-count`, `host-page-count`, red without the script).
- **2026-10-16 — the guest is asked for the artist and the title, on the branch `claude/artist-and-title` from `origin/dev`; a PR to
  `dev`.** Production: one line of lyrics ("Dziewczyno jak wino uderzasz do głowy") gave four different made-up songs, each rejected
  by the vibe. The owner: ask for "wykonawca i tytuł" — a line of lyrics typed is the guest's own risk. The field's hint, the empty
  request's note, the QR poster and cards, the guide (PL / EN); the AI still tries lyrics. Next, if wanted: Gemini's Google Search
  grounding for a line of lyrics — a test with `GeminiComparison` first (the owner's yes for the key). Tests: 720 unit, 127 browser.
- **2026-10-16 — the state.** `main` = `dev` in content (release #93, deployed by Railway 2026-10-16 06:49 UTC; Railway waits for
  the CI). The review of 2026-10-14 is done and released: K1, W1–W15, the small items, the deferred ones (#85–#89) and the look
  (#92) — `docs/history/review-2026-10-14.md`, "Status poprawek". Of the review of 2026-09-30 only 2.3 (one instance) is left.
  Production: the CSP enforced (`CSP_ENFORCE=true` since 2026-10-15, no "CSP violation" in the log since), `gemini-3.6-flash`, the
  plans not enforced (`PLANS_ENFORCE` unset: every party has every feature), the notifications on (the VAPID keys on Railway; the owner tried them on a phone, 2026-10-16: "działa"), the
  service sleeps when idle (`sleepApplication`: the first guest after a quiet spell waits a few seconds; the log then shows Hikari's
  "Failed to validate connection").
- **The history of 2026-10-05 … 16** (every branch and PR, the owner's words, how it was verified):
  `docs/history/session-handoff-2026-10-16.md`.

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

## Waiting for the owner (not code)

- **The CSP's week:** `CSP_ENFORCE=true` since 2026-10-15 — watch Railway's log for "CSP violation" until about 2026-10-22; back:
  delete the variable (a restart, no deploy).
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
