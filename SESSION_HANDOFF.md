# Session Handoff — 2026-11-17

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

- **2026-11-17 — the state.** `main` = `e4ff4d1` (#272, the guests' song suggestions; before it #263 … #271, V68 … V71), no open PR.
  Deployed 2026-11-16 22:56 UTC, SUCCESS (Railway, read only): Flyway at 71, nothing to apply, started in 4.9 s, no WARN, 0 × 5xx of
  681 requests in 24 h, RAM 0.48 GB;
  curl without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`, `/healthz` 200.
- **2026-11-17 — `claude/kind-knuth-7ryiki`, PRs #274 (`main`) and #275 (`dev`), not merged, no migration:** the review of #272
  (`docs/history/review-2026-11-17-song-suggestions.md`) and all its fixes, P1–P9: "zenek martyniuk" keeps Zenon Martyniuk's songs,
  the album counts ("grease you"), no padding after an artist, a minute without iTunes after it refused. To try: the guest page on a
  phone, "zenek", "grease you", "ac/d". Waits for the owner: the merges; Classic, Perfect, Feel, Survivor kept or not.
- **2026-11-17 — `claude/artist-words` (`98e15f4`), PRs #276 (`main`) and #277 (`dev`), not merged, no migration:** words that begin
  an artist's name are completed ("bad bu" → Bad Bunny, "lady p", "rolling st"); 41 artists (422 in all, each checked with
  `tools/check_artists.py`); new scenario `song-suggestions-artist-words`, all 18 `song-suggestions-*` pass. To try: the guest page,
  "bad bu", "zenek m", "sco" (still Scooter). Waits for the owner: the merges.
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
  `scan2play-2026-11-17.dump`, 255 KB, done — the bucket itself and a restore not checked yet). By hand before a release with a
  migration (`docs/database-backup.md`: the Postgres TCP proxy only for the copy, removed after it).
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
