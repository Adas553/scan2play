# Session Handoff — 2026-10-26

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`,
`session-handoff-2026-10-16.md`, `session-handoff-2026-10-18.md`, `session-handoff-2026-10-21.md`, `session-handoff-2026-10-24.md`
(2026-10-22 … 24: the review of 2026-10-21 and its releases, the DJ's library, the ideas list — items 1–7 — word for word).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-10-25.md`).

## Start here

- **2026-10-26 — the state.** `main` = `dev` + the release merge #178 (`161a6a4`), deployed 2026-10-25 19:36 UTC, SUCCESS. Checked
  2026-10-26 (Railway, read only): Flyway applied V44 once (0.016 s); no 5xx in 48 h (3 016 requests; the 1 414 4xx are scanners
  asking for `/.env`, `/.git/config`, `/id_rsa` … — all 404); the nightly jobs ran on time (03:40 queues, 03:45 the 30-day purge,
  03:50 invitations, 03:55 the demo's reset — UTC, Polish winter time since 2026-10-25) and the start's `atStart` cleared 1 request.
- **Released 2026-10-25:** the review of 2026-10-25 and the library's fixes (#168 / #169, V43), the new mark — a play triangle of QR
  modules (#170 / #171), folded cards on "Ustawienia imprezy" and "Obsługa", the library's card, the buttons (#172 – #174), "+ Dodaj
  piosenkę" by the DJ (#175, V44), outline buttons on a phone (#176), the history's filters keep "Podsumowanie wieczoru" (#177).
- **The repository is public** (`LICENSE`: viewing only) — GitHub Actions have no minute limit. Nothing secret in the repo, the docs
  or a commit — ever.
- **Railway:** neither service sleeps; a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy`.
- **Mind:** (1) a form with a CSRF token on a public page for a visitor without a session needs a test with `SessionRepositoryFilter`
  (`LandingPageSessionWebTest`); (2) a browser scenario that checks a page fits measures `document.documentElement.clientWidth`, never
  `window.innerWidth`; (3) after a release, curl production without cookies to the page's end, not only for a 200.

## Now (the owner's priorities, 2026-10-26)

1. **The DJ's logo** (Pro, `PlanFeature.LOGO`, V45) — built on `claude/sleepy-feynman-kxaj43`, the PR to `dev` waits for the owner's
   review (then a release; V45 on production: check Flyway's line once). The owner's picks: shown on all five
   places (the guest page and the result page, the TV screen, the QR poster and cards, "Podsumowanie wieczoru", "impreza zakończona");
   the browser scales any image it reads to ≤ 512 px and sends a PNG, the server takes only PNG / JPEG (its magic bytes), reads the
   size before decoding, re-encodes to PNG without metadata; our mark stays small at the bottom; the privacy policy says it.
2. **Editing the QR poster** (`/dj/qr-print`, V46, released to `dev` in #183) and **its look** (V47: four themes, a colour made
   readable; the owner left the choices to Claude, 2026-10-28: all four themes, a palette and the DJ's own colour, any plan) — built on `claude/sleepy-feynman-kxaj43` (the owner's picks, 2026-10-28: saved
   with the party; the DJ's heading ≤ 40 and line ≤ 60, the language, boxes for the logo / profiles / tip / address; the organiser and
   the staff with VIBE, any plan). Released before it: the DJ's logo (#179 / #180, V45), the poster without its numbered steps, a
   larger logo, the cards' texts centred on the code (#181 / #182).
3. **The DJ's panel against TipTunes and Rekwest** (the review of 2026-10-28, on `claude/scan2play-dj-panel-review-m9yrpb`):
   items 1–7 (`61b3de3`, the owner tested it: "fajnie wygląda") — the device's own font, one accent, one heading and tabs "Panel DJ-a |
   Historia | Ustawienia", "Impreza i kod QR", the card's gaps; then items 8–10 and 12 and the owner's own: the rows of "Więcej" off the
   panel (the settings were in two places; the network's limit near is now a warning above the queue), the print's editor in the
   panel's colours, from 1400 px the unfolded settings beside the queue, "Kopiuj" / "Podejrzyj" quiet on a wide screen, "Panel jako
   aplikacja" under an empty queue on a phone. Then "Uprawnienia po kolei" on "Obsługa" 44 px on a phone and the landing page's
   pictures made again (`screenshots.py`). Item 11 "Moje imprezy": not now (the owner, 2026-10-28 — a party is one per organiser and the
   history is purged after 30 days; a list of past evenings would need a model of its own, worth it once DJs ask).

## Later (not now)

A. **The library for guests:** first suggestions from the DJ's library before iTunes; a full "library only" mode (no AI, karaoke) only
   after talks with DJs. `dj_library_track` keeps keys only — names need a migration and a new upload; server-side search (`pg_trgm`,
   a rate limit); the DJ's consent to a public list.
B. "Opis klimatu" → "Opis imprezy" with a note that the AI reads it.
C. A phone with a library: "Ukryj „Nie masz”" and "+ Dodaj piosenkę" in one row with shorter labels.
D. GitHub Actions' minutes: no limit while the repository is public; estimate the use from the workflows' history if it goes
   private (2 000 minutes, budgets at $0 — the tests would stop and Railway might hold `main`'s deploy).
E. From the history (`session-handoff-2026-10-24.md`): talks with DJs (its item 1), Gemini + Google Search for lyrics (item 6), the
   terms before `PLANS_ENFORCE` (item 5), the Gemini comparison for the next model (item 4).

## Waiting for the owner (not code)

- Gemini's prepaid credits: top up or auto-reload (at zero every request goes to the DJ unchecked); a budget alert in Google Cloud.
- Old PRs to `main`: #2 and #7 (April) — close them.
- YouTube key "Klucz API 2" and `YOUTUBE_API_KEY` in IntelliJ, the Spotify app and `SPOTIFY_*` in IntelliJ — delete them; the
  disabled OAuth secret `****IgiS` — delete it.
- Search Console: request the indexing of `/dj-weselny`, `/kluby`, `/puby`.
- `SCAN2PLAY_GUEST_URL` in IntelliJ follows the local network's address — change it when the network changes.
- `server.forward-headers-strategy=FRAMEWORK` trusts `X-Forwarded-*`: safe while Railway's own address cannot be reached past
  Cloudflare — keep it so.
