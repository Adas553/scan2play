# Session Handoff — 2026-10-30

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`,
`session-handoff-2026-10-16.md`, `session-handoff-2026-10-18.md`, `session-handoff-2026-10-21.md`, `session-handoff-2026-10-24.md`
(2026-10-22 … 24: the review of 2026-10-21 and its releases, the DJ's library, the ideas list — items 1–7 — word for word),
`session-handoff-2026-10-26.md` (2026-10-26 … 28: the DJ's logo, the QR poster's editor and look, the panel's review — word for word).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Reviews: `docs/history/review-*.md` (the latest
`review-2026-10-25.md`).

## Start here

- **2026-10-30 — the state.** `main` = `dev` + the release merge #209 (`c8b1fe7`), deployed 2026-10-30 15:26 UTC, SUCCESS. Checked
  (Railway, read only): started in 5.4 s, Flyway validated 56 migrations and applied none. V55 came with #207 (`bfab063`, deployed
  13:28 UTC): "Successfully applied 1 migration … now at version v55" (0.028 s). **The party hours work on production:** the owner's
  party planned at 13:51 UTC (open 20:00, close 23:00 Polish time), the job logged "Party [...] opened by its plan" at 19:00:00.019 UTC
  — the minute's first second. Still to see once: the planned close ("closed by its plan" at 22:00 UTC that evening) and the card's
  save in place (#209) on a phone. The public pages were not curled from the cloud session (its network refuses
  `www.scan2play.com.pl`): curl them without cookies to the page's end.
- **Released 2026-10-30:** #207 "Godziny imprezy" (V55; item 5 below) and #209 its card saving in place, as every card (no jump of the
  screen, "✓" / "✗" on its small "Zapisz"). Released before it the same day and the day before (other sessions): #199 the poster
  heading's colour, #202 the QR print's editor after its review, #205 the table tent centred on a phone and the tips said to go 100 %
  to the DJ.
- **The use on 2026-10-30** (Railway's logs, read only): the owner's account alone on the panel; no guest's request all day; the
  demo opened by the owner and by one visitor on an Android phone (10:58 Polish time, three loads, nothing sent); Googlebot crawling
  `/`, `/demo` and the assets in the evening; a scanner asking for WordPress paths (404).
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
  — a glob `*Inter*` rejected nothing; check `fc-list | grep -ci inter` is 0), what is left is DejaVu and Liberation, as on GitHub. (5) in a cloud session the browser tests need a short `TMPDIR`
  (`/tmp/s2p`): Chrome's profile under the long scratchpad path passes the length limit of a UNIX socket's name and the browser aborts
  (code -6); the container's PostgreSQL (16) may stop between runs — `pg_ctlcluster 16 main start`; an `*IT` run failing everywhere at
  once is that, not the code.

## Next (the owner's ideas, 2026-10-28)

1. **A first contact with a new DJ** (the owner, 2026-10-28: "jak ktoś nowy zaloguje się, wysłać maila, że może do nas pisać") — (a) and
   (b) **in `dev` (#189, #190), waiting for a release** (V48 on production: check Flyway's line once, and `/dj/stats` shows "New
   organisers"). The owner's picks (2026-10-28): the card for new accounts only, above
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
2. **"Zmień kod imprezy"** (V49; the owner, 2026-10-28: "gość, który ma zawsze jego kod QR i spamuje imprezę DJ-a") — **built on
   `claude/affectionate-gauss-iwuhvz`, the PR to `dev` waits for the owner's review** (then a release; V49 on production: check Flyway's
   line once). The owner's picks: a new code (not a ban by address — a venue's Wi-Fi and a mobile network share one), the organiser
   only, last card of "Ustawienia imprezy", asks "Zmienić kod imprezy? Stare plakaty i karty przestaną działać.". Everything of the party
   moves (FKs ON UPDATE CASCADE, requests and feedback by UPDATE), the old code is retired for good (`party_code_retired`), the TV
   screen opened again from the panel. Not done (proposed): closing a party by itself after hours without requests.
3. **The QR print's editor, enriched** (the owner, 2026-10-29: "chcę wzbogacić edytor plakatów i kart") — the ideas, the competitors and
   the mock-ups: the artifact "Edytor wydruku: co dalej" (claude.ai, private). The owner's picks: first **the table tent** (one A4
   sheet, two A5 faces alike, the upper one upside down) and **the number of tables** saved with the party (V50, "Liczba stołów", on the
   cards and the tents, never on the poster), "Numer pierwszego stołu" (never saved) — **in `dev` (#194), waiting for a release** (V50
   on production: check Flyway's line once; print a tent on a home printer and fold it). Then (the owner, 2026-10-29: "zrób następne
   rzeczy z listy") **"Nagłówek na okazję" and "Do internetu" (a story / post PNG)** — built on
   `claude/bold-bell-w3epu5` again, no migration. The theme "Oszczędny" was not built: "Klasyczny" is already the white sheet with no
   fills (an ink note under the club theme was built and taken off again: the owner — everyone knows a dark sheet takes ink). New looks (themes, fonts, ornaments) for every plan; photos on the poster
   later, as Pro. **#195 in `dev`.** Then (the owner, 2026-10-29: "zrób na razie 1") **the print's own fonts** ("S2P Sans" = Source
   Sans 3, "S2P Serif" = Playfair Display, `static/fonts`, OFL, `/fonts/**` public and kept a year) **and the DJ's heading and line
   fitted**, and (the owner: "dodaj wybór kroju") **"Krój nagłówka"** (V51: the theme's, sans, serif, script — Dancing Script) —
   built on `claude/bold-bell-w3epu5` (the longest words were cut on a card and a tent before; V51 on production: check Flyway's
   line once), and **four more themes** (V52: Rustykalny, Gala, Neon, Konfetti; the owner picked four of seven mock-ups — Morski, Retro,
   Zimowy when DJs ask; at 10+ themes a picker of thumbnails in place of the list), and **"Kolor tła"** (V53: the theme's, six tints
   or the DJ's own — a light colour a light tint, a dark one a dark paper with light words: the owner asked for dark ones too). Next
   candidates: A5 / A6 for the acrylic stands; the TV screen in the print's look; small ones (A3, the scan distance, a ruler). Open: stickers and business cards against "Zmień kod imprezy" (V49 kills old
   prints). To try on a phone: "Do internetu" opens the share sheet (Instagram) on Android and an iPhone.
4. **To try on real devices** (the release of 2026-10-28): "Panel jako aplikacja" in an iPhone's Safari, the settings beside the queue
   on a monitor ≥ 1400 px, the history's "⋯" and folded line on a phone.
5. **"Godziny imprezy" — the party opens and closes by itself** (V55; the owner, 2026-10-30) — **released 2026-10-30** (#206 / #207,
   then #208 / #209: the card saves in place and looks as every card; V55 applied, the first planned open applied on the minute —
   above). The job runs every minute on a scheduler of two threads (`spring.task.scheduling.pool.size=2`), the due rows picked once in
   a CTE (an `IN (SELECT … LIMIT … SKIP LOCKED)` ran again per row under a nested-loop plan — `PartyScheduleIT`). The owner's picks: one-off "Otwórz o" / "Zamknij o"
   first (a weekly plan for clubs later, its own migration, writing into the same two columns); a manual change wins until the next
   planned moment; the guests read "Prośby od 20:00" / "Prośby od soboty, 20:00"; whoever has OPEN_CLOSE sets it, plan Free, the first
   card of "Ustawienia imprezy" with the panel's line "⏰ Zamknie się jutro o 3:00 ✕", quick choices "dziś 20:00" / "jutro 3:00".
   Details: `PROJECT_CONTEXT.md` 5.1. The idea of item 2 — closing a party by itself after hours without requests — fits this model:
   a job that, after N hours without a request, closes the party through `PartyScheduleService.setActive` (or writes `next_close_at`);
   not built. To try on a phone: the native day and time fields in an iPhone's Safari and on Android.
6. **"Kiosk dla gości" — a tablet at the bar for guests without a phone** (V56, Pro; the owner, 2026-10-30, after Rekwest's kiosk) —
   **built on `claude/serene-fermat-6gp9b8`, waiting for the owner's review** (then a release; V56 on production: check Flyway's line
   once — it also adds KIOSK to the co-organisers' permissions). The owner's picks: Pro; with votes; whoever has the new permission KIOSK
   makes the link; the most wanted beside the form, as the TV screen. A guest's turn is a session ("Następny gość", or by itself after a
   minute without a touch); the kiosk's own limits (3 requests a minute, 20 👍 per 10 min) in place of the address's; a request marked
   "🖥 kiosk" in the queue. After the review of 2026-10-30 (three fixes, each with a test seen red first): "Następny gość" keeps a
   session with a login (it logged the DJ out), a refused request ends its turn by itself too (the next guest found the last one's
   words and dedication), a lost 👍 goes to the kiosk's address instead of reloading (on an answer that sent the request again).
   Then the review's other points: the list not put in place under a finger (5 s after a touch, 15 s after a 👍's note, an answer
   asked for before a 👍 dropped, the focus kept), "Następny gość" sent in the background (the Wi-Fi down: no browser error page), a
   party closed meanwhile sends the kiosk to its page, the kiosk's limit of 👍 said as the kiosk's, "Wyłącz" without Pro, the request's
   way in `GuestRequestService` (no controller in a controller), "🖥 kiosk" in the history and "Z kiosku" in the summary and a column of the CSV,
   a guard test that V56's words are the stored whole set.
   Details: `PROJECT_CONTEXT.md` 5.1. To try on a real tablet: an iPad in Guided Access and an Android tablet
   with the screen pinned, the native keyboard over the form, the suggestions.

## Later (not now)

A. **The library for guests:** first suggestions from the DJ's library before iTunes; a full "library only" mode (no AI, karaoke) only
   after talks with DJs. `dj_library_track` keeps keys only — names need a migration and a new upload; server-side search (`pg_trgm`,
   a rate limit); the DJ's consent to a public list. Asked again 2026-10-28 ("gość widzi tylko piosenki z biblioteki?" — and the cheaper
   "przyjmuj tylko z biblioteki", ✗ refused at once): the owner, "jednak nie warto" — the library stays the DJ's marks only.
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
