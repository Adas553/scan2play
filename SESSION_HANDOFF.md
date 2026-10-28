# Session Handoff — 2026-10-28

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`,
`session-handoff-2026-10-16.md`, `session-handoff-2026-10-18.md`, `session-handoff-2026-10-21.md`, `session-handoff-2026-10-24.md`
(2026-10-22 … 24: the review of 2026-10-21 and its releases, the DJ's library, the ideas list — items 1–7 — word for word),
`session-handoff-2026-10-26.md` (2026-10-26 … 28: the DJ's logo, the QR poster's editor and look, the panel's review — word for word).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-10-25.md`).

## Start here

- **2026-10-28 — the state.** `main` = `dev` + the release merge #187 (`6318a28`), deployed 2026-10-28 15:53 UTC, SUCCESS. Checked
  (Railway, read only): started in 5.5 s, Flyway validated 48 migrations and applied none (none in the release), the owner's first
  minutes on `/dj/dashboard`, the History tab and `/dj/settings` all 200, the new assets under the deploy's version. The public pages
  were not curled from the cloud session (its network refuses `www.scan2play.com.pl`): curl them without cookies to the page's end.
- **Released 2026-10-28** (#186 / #187, the review of the DJ's panel against TipTunes and Rekwest, 10 commits): one heading and tabs
  "Panel DJ-a | Historia | Ustawienia" on every DJ page (a tab opens its page at the top), the devices' own fonts named
  (`-apple-system` … Arial — never `system-ui` first: DejaVu on GitHub's runner broke three one-line layouts), one accent (cyan; green a
  status), "Impreza i kod QR", the rows of "Więcej" off the panel (the network's limit near is a warning above the queue), from
  1400 px the settings beside the queue, quiet "Kopiuj" / "Podejrzyj", the print's editor in the panel's colours, "Panel jako
  aplikacja" under an empty queue on a phone, the history's "⋯" menu and its folded line in columns, "Ukryj ✗" / "+ Dodaj" on one line.
  Released before it the same day: the DJ's QR poster and its look (V46, V47; #185).
- **The repository is public** (`LICENSE`: viewing only) — GitHub Actions have no minute limit. Nothing secret in the repo, the docs
  or a commit — ever.
- **Railway:** neither service sleeps; a spending limit of 15 USD (an alert at 10 USD); Healthcheck Path `/privacy`.
- **Mind:** (1) a form with a CSRF token on a public page for a visitor without a session needs a test with `SessionRepositoryFilter`
  (`LandingPageSessionWebTest`); (2) a browser scenario that checks a page fits measures `document.documentElement.clientWidth`, never
  `window.innerWidth`; (3) after a release, curl production without cookies to the page's end, not only for a 200; (4) a layout that
  must stay on one line: run its scenario with the runner's fonts too — locally take Inter out of fontconfig (`FONTCONFIG_FILE` with a
  `<rejectfont>` globbing Inter's directory, e.g. `/usr/share/fonts/opentype/inter/*`, before the `<include>` of `/etc/fonts/fonts.conf`
  — a glob `*Inter*` rejected nothing; check `fc-list | grep -ci inter` is 0), what is left is DejaVu and Liberation, as on GitHub.

## Next (the owner's ideas, 2026-10-28)

1. **A first contact with a new DJ** (the owner, 2026-10-28: "jak ktoś nowy zaloguje się, wysłać maila, że może do nas pisać") — (a) and
   (b) **built on `claude/affectionate-gauss-iwuhvz`, the PR to `dev` waits for the owner's review** (then a release; V48 on production:
   check Flyway's line once, and `/dj/stats` shows "New organisers"). The owner's picks (2026-10-28): the card for new accounts only, above
   the queue until closed, closed by "✕" or "Napisz do nas", the list of new organisers on `/dj/stats`.
   (a) **The organiser's e-mail** (V48): `party_settings.owner_email` — Google's verified address, kept with the name when the organiser
   opens their panel (`DjDashboardController.keepOwner`; never the staff's), gone with the account (`AccountDeletionIT`); `created_at`
   (the parties of before: null — never "new"); the privacy policy PL / EN (sections 2, 3, 5, Google's Gmail in 6). `/dj/stats`: the
   organisers new in the last 7 days with their address (at most 50, `NewOrganisersIT`). The message, sent by hand from
   `scan2playcontact@gmail.com`: `docs/welcome-email.md` (PL, EN). The accounts of before fill the address at their next login.
   (b) **The welcome card** above the queue (`welcome_dismissed_at`; the accounts of before closed by the migration): "Pierwszy raz? Napisz
   do nas", "Napisz do nas" → "Pomoc i kontakt", "Jak to działa →"; closed on the server (`POST /dj/dashboard/welcome-dismiss`,
   `welcome.js`); browser scenarios `welcome-card-*`, seen red first, the one-line heading also with the runner's fonts.
   (c) **an automatic welcome e-mail — later**, when new accounts come several a day or the plans are enforced. Its choices then: the
   sender (Resend, Postmark, Brevo… or Google Workspace SMTP; its key a Railway secret; SPF / DKIM / DMARC at Cloudflare), the
   reply-to, once only (`welcome_sent_at`, sent after the commit off the login's thread, retried by the nightly job, never blocking the
   login), the language, no marketing; the sending behind an interface (a fake in the tests), an IT for "once only" under two logins.
2. **To try on real devices** (the release of 2026-10-28): "Panel jako aplikacja" in an iPhone's Safari, the settings beside the queue
   on a monitor ≥ 1400 px, the history's "⋯" and folded line on a phone.

## Later (not now)

A. **The library for guests:** first suggestions from the DJ's library before iTunes; a full "library only" mode (no AI, karaoke) only
   after talks with DJs. `dj_library_track` keeps keys only — names need a migration and a new upload; server-side search (`pg_trgm`,
   a rate limit); the DJ's consent to a public list.
B. "Opis klimatu" → "Opis imprezy" with a note that the AI reads it.
C. (Done 2026-10-28: on a phone "Ukryj ✗", "+ Dodaj" and the search on one line.)
D. GitHub Actions' minutes: no limit while the repository is public; estimate the use from the workflows' history if it goes
   private (2 000 minutes, budgets at $0 — the tests would stop and Railway might hold `main`'s deploy).
E. From the history (`session-handoff-2026-10-24.md`): talks with DJs (its item 1), Gemini + Google Search for lyrics (item 6), the
   terms before `PLANS_ENFORCE` (item 5), the Gemini comparison for the next model (item 4).

## Waiting for the owner (not code)

- `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` in the `dev` environment (IntelliJ or a dev server): without them "To urządzenie" has no
  switch of the notifications (the owner's screenshot, 2026-10-28) — the same pair as production, never in the repo or a chat.
- Gemini's prepaid credits: top up or auto-reload (at zero every request goes to the DJ unchecked); a budget alert in Google Cloud.
- Old PRs to `main`: #2 and #7 (April) — close them.
- YouTube key "Klucz API 2" and `YOUTUBE_API_KEY` in IntelliJ, the Spotify app and `SPOTIFY_*` in IntelliJ — delete them; the
  disabled OAuth secret `****IgiS` — delete it.
- Search Console: request the indexing of `/dj-weselny`, `/kluby`, `/puby`.
- `SCAN2PLAY_GUEST_URL` in IntelliJ follows the local network's address — change it when the network changes.
- `server.forward-headers-strategy=FRAMEWORK` trusts `X-Forwarded-*`: safe while Railway's own address cannot be reached past
  Cloudflare — keep it so.
