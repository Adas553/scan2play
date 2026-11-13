> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 11, 12. Keep it to what is true now.

## 11. Main Dependencies

```
GuestController            → GuestRequestService, GuestQueueService, GuestSessionService, GuestVoteService, GuestRequestLimiter, PartySettingsQueryService
GuestRequestService        → SongEvaluationService, GuestSessionService, GuestVoteService, GuestRequestLimiter, SiteCounter
KioskController            → PartySettingsRepository, PlanService, GuestRequestService, GuestQueueService, GuestSessionService, GuestVoteService, GuestRequestLimiter, PartyScheduleService
GuestVoteService           → SongRequestRepository, DjService
DjDashboardController      → DjService, PlayHistoryService, QrCodeService, GuestRequestLimiter, PartySettingsCommandService, DjSessionHelper, PushNotificationService, PartyStaffService, StaffInvitationService, PlanService
DjPartySettingsController  → PartySettingsCommandService, AccountDeletionService, DjSessionHelper, PlanService
DjSessionHelper            → PartySettingsQueryService, PartySettingsCommandService, PartySettingsRepository, PartyStaffService
StaffController            → PartyStaffService, PartySettingsCommandService, DjSessionHelper, StaffInvitationService, PlanService
StaffInvitationService     → StaffInvitationRepository, PartySettingsRepository, PartyStaffService, PartySettingsQueryService
PlanService                → DjPlanRepository (and trial_used), CacheManager
PartyStaffService          → PartyStaffRepository, PartySettingsRepository, PartySettingsQueryService
HostController             → PartySettingsRepository, PartySettingsCommandService, PlanService
TvScreenController         → PartySettingsQueryService, GuestQueueService, QrCodeService, PlanService
DjSongController           → DjService, DjSessionHelper, PlanService
DjSummaryController        → EveningSummaryService, DjSessionHelper, MessageSource, PlanService
SharedSummaryController    → EveningSummaryService, PartySettingsRepository, PlanService
DjLibraryController        → DjLibraryService, DjSessionHelper, PlanService, MessageSource
PartyLogoController        → PartyLogoService, PartySettingsQueryService, DjSessionHelper, PlanService, MessageSource
DjService                  → SongRequestRepository, SongRequestDedicationRepository, CacheManager, DjLibraryService (the queue's library marks)
SongEvaluationService      → Gemini Client, SongRequestRepository, SongRequestCommandService, PartySettingsQueryService, PushNotificationService, PartyStaffService
SongRequestCommandService  → SongRequestRepository, PartySettingsRepository, DjService (evicts the queue's cache after a new request or a vote)
AccountDeletionService     → the repositories of every table of a DJ's data, CacheManager
PushController             → PushSubscriptionService
GuestQueueService          → DjService
```

---

## 12. HTTP Endpoints

### Public

| Method | Path | Handler / notes |
|--------|------|-----------------|
| GET | `/` | `HomeController.home` |
| GET | `/start`, `/start/{kind}` | → Google's login (`{kind}`: the old tile links, ignored) |
| GET | `/p/{partyCode}` | the guest's page (`party_ended` when closed); an unknown code is a 404 — the error page's "Ten kod imprezy nie istnieje lub link jest niepoprawny" (until 2026-10-21 the landing page for DJs; a request sent to one, too). No session is made for an unknown code or a party that has ended (review 2026-10-14 W3: every `GET /p/{anything}` was a row in `spring_session`); an open party's page makes one — its form's CSRF token |
| GET | `/p/{partyCode}/library/has` | `song` (repeated, "Artist - Title", the first 10 looked at): which of them the DJ has — JSON `[true, false, …]` in their order (V58, `LibraryHintService`); 404 for an unknown party or one with nothing to tell (ended, no library, no Pro, switched off); 429 past 120 asks a minute of the address (the suggestions shown unmarked); no session made |
| GET | `/p/{partyCode}/queue` | the guest's list alone: the first five (empty when closed, 404 for an unknown party) |
| GET | `/p/{partyCode}/queue/more` | the rest of the guest's list, fetched when "Pokaż pozostałe prośby" is unfolded |
| GET | `/p/{partyCode}/tv` | the screen for a TV (2026-10-18): in the language of the DJ's panel (the panel's link carries `?lang=`; Polish: Polish and English at once, any other: English alone — 2026-10-23); the QR code, the ten most wanted songs and their votes, "Głosuj 👍 na telefonie" under the list's heading while it has songs — no guests' words, no dedications, no request the AI did not judge (V39); "DJ nie przyjmuje teraz próśb" when closed; 404 for an unknown party; no session |
| GET | `/p/{partyCode}/logo.png` | the DJ's logo (V45): our PNG, ETag = its version (304), `Cache-Control: public, max-age=3600`; 404 without one or without the plan |
| GET | `/p/{partyCode}/tv/list` | the screen's list alone (`tv :: tvList`), fetched by `tv.js` every 10 s |
| POST | `/p/{partyCode}/request` | a request: `songName`, `dedication` (optional, V38) |
| POST | `/p/{partyCode}/vote` | a guest's 👍: `id`, `on` (false = take it back); with `X-Requested-With: fetch` that song's row (or a note), else a redirect to the party page |
| GET | `/h/{token}` | the hosts' lists (V29): no account, the secret is the key; 404 for an unknown or old one, or one made more than 30 days ago (V37) |
| GET | `/s/{token}` | an evening summary's hosts' link (V61): the hosts' version, no account; 404 for an unknown, old or switched-off one, or without the summary in the party's plan |
| GET | `/k/{token}` | the venue's kiosk (V56, Pro): the request form and the most wanted with 👍, or — closed — when it opens; a session (the guest's turn, the forms' CSRF token); the kiosk's cookie `S2P_KIOSK`; 404 for an unknown token or a party without Pro; `noindex` |
| GET | `/k` | the same kiosk by its cookie (the address kiosk.js shows: the secret out of the address bar); 404 without it or with a token made new since |
| GET | `/w/{token}` | "Życzenia przed imprezą" (V68, Pro): the event's guests' page — its name and day, the field, or a farewell when it is closed, past or full; 404 for an unknown token or a party without Pro |
| POST | `/w/{token}` | `song`: a guest's wish (five a session, 30 an address in 10 min, 2 000 an event), back to the page saying what became of it |
| GET | `/k/{token}/list` | the kiosk's list alone (`kiosk :: kioskList`), fetched by `kiosk.js` every 10 s |
| POST | `/k/{token}/request`, `/k/{token}/vote`, `/k/{token}/next` | the kiosk's request (async, the guests' way, `viaKiosk`, the kiosk's limit), 👍 (`id`, `on`; with `X-Requested-With: fetch` the list), "Następny gość" (the session ends; with a login in it, only the turn's state at the party is forgotten); CSRF; an expired form back to the kiosk |
| GET | `/join` | `link`: an invitation link pasted on the landing page (V30) — a party's → its invitation `/join/{token}`, else `/?staffLink=invalid#staffLogin` (`guests` for a guests' link); keeps nothing, no session (review 2026-10-21, S2) |
| GET | `/join/{token}` | the staff's invitation (V30): names the party, who invites, the role; logged in it asks "Dołącz", else keeps the token for the login; 404 "Ten link zaproszenia już nie działa" for an unknown or old one |
| POST | `/join/{token}` | "Dołącz" (V32): joins the logged-in person (not logged in: the login first) → their panel on that party; 409 when the staff is full |
| POST | `/h/{token}` | `blocked`, `wanted`: the hosts save their lists (`SongList.tidy`), back to the page with "✓ Zapisane" (a list over 100: the first 100 kept, `hostDropped` says so) |
| GET | `/privacy`, `/terms` | legal pages |
| GET | `/jak-to-dziala`, `/how-it-works` | the guide for DJs (public; the language by the locale) |
| GET | `/dj-weselny`, `/kluby`, `/puby` | the pages "Dla kogo" (public; `ForWhomController`, 2026-10-21) |
| GET | `/robots.txt`, `/sitemap.xml` | for search engines (`SeoController`, 2026-10-14) |
| GET | `/pro` | the pricing page (V35): what stays free, what Pro adds, "Napisz, że chcesz Pro" (`mailto:`), the questions; while the plans are not enforced one card "Za darmo" (everything free, no word of Pro) |
| POST | `/csp-report` | a browser's CSP report (no CSRF; 204) |
| GET | `/manifest.webmanifest`, `/sw.js` | the web app manifest (`application/manifest+json`) and the notifications' service worker |

### DJ (logged in; every endpoint works on the party the page names — `partyCode` / `party`, none: the default panel — and checks the person's permission there, V32; an endpoint of a Pro feature — the hosts' lists and link, the profiles, the tip link and 💸, a comment style but "Klasyczne", the limits, the summary and its CSV — also the organiser's plan, V35)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/dj/dashboard` | `party` (none: the default panel): the dashboard; "no-panel" when the person has none (V32); with an invitation waiting after the login, back to it; with an invitation by e-mail for the verified address, `/dj/invitation` first (V34) |
| GET | `/dj/invitation` | the invitation by e-mail waiting for the person's verified address (V34): `join.html` with "Dołącz" / "Nie, dziękuję"; none → the panel |
| POST | `/dj/invitation/accept` | `id`: joins with the invitation's role (V34; only the verified address it names) → that party's panel; gone / too old → the panel with a note; 409 full — and the invitation goes too (kept, it asked "Dołączyć?" on every opening of the panel for 30 days) |
| POST | `/dj/invitation/decline` | `id`: the invitation goes (V34; only the verified address it names) → the panel with a note |
| GET | `/dj/dashboard/updates` | the guest queue `<tbody>` (ETag / 304); every answer, 304 too, carries `X-Guest-Limits`, `X-Guest-Limits-Use`, `X-Party-Active`, `X-Panel-Access` (V32); 403 when the access was taken away |
| POST | `/dj/dashboard/add-song` | `song`: a song the DJ adds to the queue by hand (V44; QUEUE; nothing typed: 400) |
| POST | `/dj/dashboard/play` | `id`: a request marked played |
| POST | `/dj/dashboard/dismiss` | `id`: a waiting request skipped by the DJ → rejected, `skipped_at`, the AI's comment kept (the song stays out for 2 h) |
| POST | `/dj/dashboard/restore` | `id`: a request the DJ skipped or marked played → waiting again ("Cofnij", "↩ Przywróć"; only the DJ's own, not one the AI rejected or cleared with the queue, not when the same song waits) |
| POST | `/dj/dashboard/clear-queue` | "Wyczyść kolejkę": every waiting request of the DJ's own party → rejected, `cleared_at`, the AI's comment kept (one `UPDATE`, `SongRequestRepository.rejectWaiting`) |
| POST | `/dj/dashboard/clear-history` | "Wyczyść historię": the DJ's own party's played and rejected requests deleted, except a skip of the last 2 hours (`SongRequestRepository.deleteHistory`); → `/dj/history-view` |
| GET | `/dj/settings` | `party`: the page "Ustawienia imprezy" (2026-10-10) — its cards by the person's permissions; anyone with access to the party, 403 for another |
| GET | `/dj/wishes` | `party`, `event`: "Życzenia przed imprezą" (V68, WISHES) — the events, and the one named with its wishes (Section 5.1, `05-1i-wishes.md`) |
| POST | `/dj/wishes/create`, `/dj/wishes/{id}/closed`, `/dj/wishes/{id}/link`, `/dj/wishes/{id}/delete`, `/dj/wishes/{id}/must-play` | `partyCode`; a new event (`name`, `date`; Pro, five open and twenty kept at most), "Zakończ" / "Wznów zbieranie" (`closed`; reopened with Pro, five open at most), "Nowy link" (Pro), "Usuń" (any plan), "⭐" (`song` repeated, or `top=true`: the ten most wished; only the event's named songs, to `host_wanted`, 100 lines; Pro) — back to the page with what was done |
| GET | `/dj/wishes/{id}/csv` | `party`: the event's wishes as CSV — every song, how many wrote it, the library's mark |
| POST | `/dj/dashboard/vibe`, `/comment-style`, `/limits` | settings |
| POST | `/dj/dashboard/dedications` | `on`: the guests' dedications on or off (V38, permission VIBE) |
| POST | `/dj/dashboard/party-words` | `vibeNote`, `djName`: the card "Impreza"'s words under one "Zapisz" (one line each, empty clears; until 2026-10-10 `/vibe-note` and `/dj-name`) |
| POST | `/dj/dashboard/dj-links` | `instagram`, `facebook`, `tiktok`: the DJ's profiles (`SocialLinks`; 400 and nothing saved when one is not a profile on its site) |
| POST | `/dj/dashboard/booking-contact` | `bookingEmail`, `bookingPhone`: the DJ's contact for bookings (V67, Pro `PROFILES`, the organiser's; `BookingContact`; empty clears; 400 and nothing saved when one is not an address or a number) |
| POST | `/dj/dashboard/host-lists` | `blocked`, `wanted`: the hosts' lists (V29, `SongList.tidy`; empty clears) |
| GET | `/dj/staff` | `party`: the owner's page "Obsługa" (V32; the owner's) |
| POST | `/dj/staff/permissions` | `partyCode`, `id`, `role` (VIEWER / QUEUE / CO_ORGANISER / CUSTOM), `permissions` (with CUSTOM): what one person may do (V32; the owner's, a row of their own party only) |
| POST | `/dj/staff/link` | `partyCode`, `link` = `new` / `off`, with `new` `role` and `permissions` (with CUSTOM; no role: "Obsługa kolejki"): the staff's invitation link and its role (V30, V33; the owner's) |
| POST | `/dj/staff/invite` | `partyCode`, `email`, `role`, `permissions` (with CUSTOM): an invitation by e-mail (V34; the owner's) — the page says what came of it |
| POST | `/dj/staff/invitation/cancel` | `partyCode`, `id`: an invitation by e-mail cancelled (V34; the owner's, one of their own party only) |
| POST | `/dj/staff/remove` | `partyCode`, `id`: one person's access taken away (V30, moved in V32; the owner's, a row of their own party only) |
| POST | `/dj/join` | `link`: an invitation link pasted in the app → that invitation, which asks (V30, V32); else back with a note |
| POST | `/dj/staff/leave` | `partyCode`: "Opuść obsługę" — the person leaves the staff of the party the page shows (V30; nothing for the owner) |
| POST | `/dj/panel` | `party` (none = "Załóż własną imprezę", made now): the default panel (V30; 403 for a party the person does not work at) → `?party=` |
| POST | `/dj/dashboard/host-link` | `link` = `new` (a new secret: the old link dead) / `off` (no link); anything else 400; → `/dj/settings?party=…#hostListsCard` |
| POST | `/dj/dashboard/tip-count` | `id`, `add` (default true; false takes one back): the DJ's tip for their own numbered song (V28) |
| POST | `/dj/dashboard/guest-limit-reset` | the party's 24 h limit of guest requests starts again ("Odblokuj prośby gości" in the warning above the queue; `LIMITS`, not a plan's feature — review 2026-10-14 W4) |
| POST | `/dj/dashboard/welcome-dismiss` | `partyCode`: the welcome card closed for good (V48; the owner's alone, 204; a second close keeps the first time) |
| POST | `/dj/dashboard/party-code` | `partyCode`: "Zmień kod imprezy" (V49; the owner's) — a new code, the old one dead for good → `/dj/settings?party=<new>#partyCodeCard` with the flash `codeChanged` |
| POST | `/dj/dashboard/tip-link` | `tip`: the DJ's tip link (`TipLinks`; 400 and nothing saved when it is not a page on one of the tipping services; empty clears it) |
| GET | `/dj/history-view`, `/dj/history-view/fragment` | `limit` (50..300), `filter` |
| GET | `/dj/qr-print` | `layout` = poster (the heading, the code, the address and the party's code — no numbered steps: the heading says what to do) / cards / tent (V50; the cards and the tents a table each, numbered, when the party has tables); as the DJ set it (V46: the heading, the line, the language — by default the panel's: Polish prints Polish and English, any other English —, the parts shown); the editor for whom has VIBE; `wish` (V68): the invitation of an event of "Życzenia przed imprezą" — its link's code and address, its own words, an editor that saves nothing |
| POST | `/dj/qr-print/settings` | `partyCode` and the form as one record (`DjPartySettingsController.PosterForm`, `applyTo`): `heading`, `line` (one line each, cut to 40 / 60; empty = ours / none), `language` (AUTO / BOTH / PL / EN, anything else AUTO), `theme` (CLASSIC / ELEGANT / CLUB / PASTEL / RUSTIC / GALA / NEON / CONFETTI, anything else CLASSIC, V47, V52), `accent` ("#rrggbb", anything else none), `font` (THEME / SANS / SERIF / SCRIPT, anything else THEME, V51), `size` (SMALL / NORMAL / LARGE, anything else NORMAL, V54), `paper` ("#rrggbb", anything else none, V53), `tables` (V50: 1 … 50, more is 50, anything else none), `showLogo`, `showLinks`, `showTip`, `showAddress` (a box not sent is off): the QR print as the DJ sets it (V46, VIBE, any plan) — 204 |
| GET | `/dj/stats` | the site's counter (V40) as text, the last 30 days, the organisers new in the last 7 days with their address (V48), and the e-mails' state (V64: the key set, the last send, a later failure); only the account `ADMIN_GOOGLE_ID` names, else 404 |
| GET | `/dj/stats/report` | the owner's daily report as it would go now (with the organisers' addresses); the same owner only, else 404 |
| POST | `/dj/stats/report/schedule` | when the report goes (V66): `on` (none = off), `time` "HH:mm" (else 400), `days` (MONDAY … SUNDAY, several) → `?saved=yes`; the owner only |
| POST | `/dj/stats/report` | sends the report now → `/dj/stats/report?sent=yes` / `no`; the owner only |
| POST | `/dj/dashboard/morning-summary` | "Poranne podsumowanie" (V64): `on` (none = off); the organiser's alone; with `X-Requested-With: fetch` the card alone, else back to the settings |
| GET | `/demo`, `/demo/updates`, `/demo/history` | the demo (public, GET only): the demo party's panel to look at, its poll and History tab (`DemoController`) |
| POST | `/dj/library` | `partyCode`, `playlist` (optional: one playlist of a rekordbox collection, "Kluby / Piątek"), `occurrence` (optional, default 1: the second playlist of that name is 2); the body is the file (`application/octet-stream`): the DJ's library (V41 – V43, Pro, the organiser) — text: 200 saved, 413 over 64 MB, 422 not a library / no song / over 50 000 tracks / a collection over 100 000 tracks for one playlist / no such playlist, 503 another file being read (after 30 s), 408 the file still arriving after 5 minutes |
| POST | `/dj/logo` | `partyCode`; the body is the picture (`application/octet-stream`): the DJ's logo (V45, Pro, the organiser) — text: 200 saved, 413 over 2 MB, 422 not a PNG / JPEG, over 4096 px a side or 16 megapixels, unreadable; 503 two others being decoded |
| POST | `/dj/logo/delete` | `partyCode`: the logo deleted (the organiser, any plan); back to `/dj/settings#logoCard` |
| POST | `/dj/library/delete` | `partyCode`: the library deleted (the organiser, any plan); back to `/dj/settings#libraryCard` |
| POST | `/dj/library/hints` | `partyCode`, `on`: "Podpowiedzi gości" (V58, the organiser; `on=true` needs Pro); back to `/dj/settings#libraryCard` with "✓ Zapisane" (a flash attribute) |
| GET | `/dj/summary`, `/dj/summary/csv` | `evening` ("2026-10-03"; none or not a date = the latest with requests), on the page `view` ("hosts": the hosts' version): the evening summary, its CSV (404 with no evening) |
| POST | `/dj/summary/title`, `/dj/summary/share`, `/dj/summary/share/off` | `evening` (one with a request, kept or not — `EveningSummaryService.hasRequests`, one EXISTS —, else 404), `title`: the evening's title, a new hosts' link (30 days), the link switched off (V61); back to the hosts' version |
| POST | `/dj/push/subscribe`, `/dj/push/unsubscribe` | JSON: the browser's push subscription (204; 400 when not a push service's address or malformed keys); unsubscribe removes only the DJ's own |
| POST | `/dj/start-party`, `/dj/end-party`, `/dj/delete-account`, `/dj/logout`, `/dj/feedback` | (start / end: `partyCode`, the permission OPEN_CLOSE; a planned moment they make pointless goes, V55) |
| POST | `/dj/dashboard/guest-theme` | "Motyw dla gości" (V59, VIBE, any plan): `partyCode`, `theme` (CLASSIC / CHRISTMAS / SCARY / CONFETTI / WEDDING, anything else CLASSIC); with `X-Requested-With: fetch` the card alone (`settings :: guestThemeCard`, `guestThemeSaved`), else back to `/dj/settings#guestThemeCard` |
| POST | `/dj/dashboard/schedule` | "Godziny imprezy" (V55, OPEN_CLOSE, any plan): `openDate`, `openTime`, `closeDate`, `closeTime` (Polish time, empty = none) or `cancel=open\|close`; with `X-Requested-With: fetch` (schedule.js) the card alone (`settings :: partyHoursCard`) with `scheduleSaved` or `scheduleError` (+ the fields as typed), else a page load back to `/dj/settings#partyHoursCard` with them as flash attributes |
| POST | `/dj/dashboard/schedule/cancel` | the panel's "✕" beside a planned moment (`event=open\|close`, OPEN_CLOSE), in the background |
| POST | `/dj/dashboard/kiosk-link` | the kiosk's link (V56, `link=new\|off`, KIOSK; `new` needs Pro): with `X-Requested-With: fetch` (cards-in-place.js) the card alone (`settings :: kioskCard`), else a page load back to `/dj/settings#kioskCard` |

---

