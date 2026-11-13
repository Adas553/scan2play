> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 4, 4.1. Keep it to what is true now.

## 4. Domain Model

### 4.1 Entities (database tables)

**Every moment is a `timestamptz` column and an `Instant` in Java** (`V12`, review 1.8) — the same on a machine in Poland and on a
server in UTC; the pages show them in Polish time (`util/Times`: the lists show `clock` — "20:04" — with the day under it, "dziś" /
"wczoraj" / `day` "29.09" (`daysAgo`; fragment `components :: moment`), and `display` — "29.09.2026 20:04:59" — as the cell's title;
a fixed-width UTC `sortKey` for the lists' `data-val`).

**`PartySettingsEntity` → `party_settings`** — one party, owned by one DJ (`owner_id` UNIQUE: one party per DJ).
`partyCode` (5 characters of `[A-Z0-9]`, unique — in the QR code; never one another party has or had, `PartyCodeService.freshCode`; the
organiser may change it, "Zmień kod imprezy", V49), `ownerId` (the OAuth2 subject), `active` (accepting requests),
`globalVibe` (`VibeType`; `ANY` = no genre: the AI judges by the DJ's note alone), `vibeNote` (V16, ≤ 150: the DJ's own words about
the vibe — the AI gets them as a block of the prompt, `prompt-vibe-note_{pl,en}`, the guests see them above the form;
one line, empty clears), `djName` (V17, ≤ 60: who plays, e.g. "DJ Koko" — the guests see "🎧 Gra: DJ Koko" under the page's title;
one line, empty clears; both saved by one form, `POST /dj/dashboard/party-words`), `instagramUrl` / `facebookUrl` /
`tiktokUrl` (V24, ≤ 200: the DJ's profiles, set on the page "Ustawienia imprezy" — the guests see buttons with the sites' icons (Bootstrap Icons' paths inline, MIT) under "🎧 Gra: …" (new tab, `rel="noopener noreferrer
nofollow"`), the QR print "Instagram @djkoko"; `POST /dj/dashboard/dj-links` takes "@name", a name or a link copied from the app and
keeps an https address on that site, `util/SocialLinks` — anything else, a look-alike host too, is a 400 and nothing is saved; the
form shows why, `[data-form-error]` in `forms.js`; no "follow the DJ" on the result page, by the owner's choice of 2026-10-07 —
but since 2026-10-16 "Zarezerwuj tego DJ-a na swoją imprezę" over the same buttons on the "party ended" page, with who played, and
under an accepted request: `components :: djBooking`, only with a profile or a contact for bookings; an idea from Rekwest, whose guests are e-mailed after
the party — Scan2Play keeps no guest's address), `bookingEmail` (≤ 254) / `bookingPhone` (≤ 20) (V67, Pro with the profiles —
`PlanService.asPlanned` clears them with the profiles: the DJ's contact for bookings, `POST /dj/dashboard/booking-contact`, a form of
the card "Profile, kontakt i napiwki", the organiser's alone; `util/BookingContact` keeps a plain address — letters, digits, `. _ % + -`
before the "@", a domain with a dot: nothing a `mailto:` would read as a header — in lower case, and a number as written with 6–15
digits and a "+" only in front; anything else a 400, nothing saved, the log without the value; the guests see in `djBooking`, above
the profiles, "Skontaktuj się prosto z DJ-em:" with a `mailto:` button — its subject "Zapytanie o rezerwację DJ-a — impreza
14 listopada 2026" under an accepted request (the evening, `EveningSummaryService.eveningOf`), without the date on "party ended",
opened days after too — and a `tel:` one, and "Wiadomość trafia prosto do DJ-a — Scan2Play jej nie widzi."; not on the party page,
the kiosk (no link out) or the TV. The analysis "Wiadomość gościa do DJ-a", `docs/ideas.md` item 11), `tipUrl` (V27, ≤ 200: the
DJ's tip link — the DJ's page on Revolut, PayPal, buycoffee.to, Suppi, Tipply, Buy Me a Coffee or Ko-fi; `POST /dj/dashboard/tip-link`,
a field under the profiles; `util/TipLinks` keeps an https address on one of those hosts with a plain path — no query, login part or
port; anything else is a 400 and nothing is saved; the guests see "💸 Napiwek dla DJ-a" on the party page — with "revolut.me/djkoko · prosto do DJ-a, poza
Scan2Play" under it — and under an accepted request without that line (`components :: tip`, `withNote`), the QR poster under the profiles and a card
in its texts' column "Napiwek / Tip: revolut.me/djkoko"; the money never passes through Scan2Play — the privacy policy says so),
`hostBlocked` / `hostWanted` (V29, ≤ 16000: the hosts' lists, one song or artist per line — see "The hosts' lists" below),
`hostToken` (V29, ≤ 32, UNIQUE, null = no link: the secret of the hosts' link `/h/{token}`, `CodeGenerator.generateSecret` — 128
random bits, base64url), `staffToken` (V30, the same kind: the staff's invitation link `/join/{token}` — see `PartyStaffEntity`),
`hostTokenMadeAt` / `staffTokenMadeAt` (V37: when the link was made — each works `LINK_DAYS` = 30 days, as an invitation by
e-mail; `hostLinkWorks` / `staffLinkWorks`; an older one is a 404 / "Ten link zaproszenia już nie działa", and the settings and
"Obsługa" show it as no link; a working one says "Działa do 14.11."),
`staffLinkPermissions` (V33, ≤ 200, null = no link: what whoever joins by the link may do — the organiser's pick when making it,
stored as `party_staff.permissions`; a link of a version before V33 gives "Obsługa kolejki", `staffLinkGrants()`),
`ownerName` (V32, ≤ 100: the organiser's name from their Google account, written when they open the panel and it changed — the
staff and the invitation name a party by "Kto gra", else by it, else by the code: `PartyStaffService.nameOf`),
`ownerEmail` (V48, ≤ 320: the organiser's address from their Google account — only one Google verified, else null —, kept beside
`ownerName` by the same write when they open their panel, never the staff's: only to write to them about the account and help — the
automatic welcome (V64, through Brevo) and our replies; the privacy policy says so; gone with the row), `createdAt` (V48, the database's `now()`,
`insertable = false`: when the party was made; null for the parties of before), `welcomeDismissedAt` (V48: when the organiser closed the
panel's welcome card, null = shown; the parties of before closed by the migration), `welcomeSentAt` (V64, `insertable` /
`updatable = false`: when the welcome e-mail went — written only by the claim and its release; the parties of before count as welcomed),
`emailLanguage` (V64: "pl" / "en", the panel's language kept with the address — the e-mails' language), `morningSummary` (V64: the switch
"Poranne podsumowanie", on by default), `emailToken` (V64, made by the database for every row: the secret of "Nie wysyłaj mi tego", `gen_random_uuid()` — 122 random bits),
`nextOpenAt` / `nextCloseAt` (V55, timestamptz, null = none: when the party opens / closes by itself — "Godziny imprezy", 5.1; never one
that would change nothing; a weekly plan, later, would write its next moment into them after each one),
`kioskToken` (V56, ≤ 32, UNIQUE, null = no kiosk: the secret of the venue's kiosk link `/k/{token}` — 128 random bits, as the hosts'
link; it does not run out, a new one makes the old link dead — "Kiosk dla gości", 5.1),
`commentStyle` (V22, `CommentStyle`:
CLASSIC / FUNNY / SARCASTIC / SHORT (V26: "sarcastic (gentle)" gone, its parties sarcastic), NOT NULL, default CLASSIC: how the AI words its comment to the guest — a block
of `prompts/prompt-comment-style_{pl,en}.txt` closes the prompt's rules, CLASSIC adds none; every style keeps "mock the request, not
the person", no profanity and no song's name in an accepted one; the dashboard's list "💬 Komentarze AI",
`POST /dj/dashboard/comment-style`), `dedicationsOn` (V38, NOT NULL, default true — every party there too: whether the guests
may add a dedication; a select "Dedykacje gości" in the card "Impreza", `POST /dj/dashboard/dedications`, permission VIBE; off, the
party page has no field and a dedication sent anyway is left out),
`posterHeading` (V46, ≤ 40) / `posterLine` (≤ 60) / `posterLanguage` (`PosterLanguage`: AUTO — the panel's —, BOTH, PL, EN) / `posterShowLogo`, `posterShowLinks`, `posterShowTip`, `posterShowAddress` (NOT NULL, default true), `posterTheme` (V47, V52, `PosterTheme`: CLASSIC / ELEGANT / CLUB / PASTEL / RUSTIC / GALA / NEON / CONFETTI) / `posterAccent` (V47, "#rrggbb", null = the theme's) / `posterPaper` (V53, "#rrggbb", null = the theme's paper; printed as a light tint) / `posterFont` (V51, `PosterFont`: THEME — the theme's —, SANS, SERIF, SCRIPT) / `posterSize` (V54, `PosterSize`: SMALL, NORMAL — as the theme has it —, LARGE) / `posterTables` (V50, 1 … `POSTER_TABLES_MAX` = 50, null = no numbers): the QR print as the DJ sets it — see "The QR print" in 5.1;
`guestTheme` (V59, `GuestTheme`: CLASSIC / CHRISTMAS / SCARY / CONFETTI / WEDDING): the look of the guests' pages — "Motyw dla gości" in 5.1;
`requestLimit` / `cooldownMinutes`
(the guest's own limit, 1–100 / 1–1440), `duplicateCheckWindow` (0–50 recently played songs the AI must not repeat). The column
`request_counter` (V28, not mapped — written only by `SongRequestRepository.nextRequestNumber`, so a save of the settings never
overwrites it): how many song numbers the party has given.

**`SongRequestEntity` → `song_requests`** — a guest's request. `partyCode`, `songName` (255; the song as the AI named it, `SongNames.tidy`: a control character is a dash, every dash "-" — the AI once copied a
guest's "–" back as a backspace; the AI is given the guest's words with "-" too, `forPrompt`), `guestText`
(150, V14: what the guest typed, as typed — one line, what the AI is given; null for older requests; the queue and the history always show
it under the song — the DJ checks the AI, 2026-10-04; when the AI's song has none of the guest's words — `SongNames.sharesNoWord`: a
word of 3+ letters, its stem — the first max(3, length − 2) letters — looked for in the `comparable` name — the queue and the history mark it
"⚠ Sprawdź", `needsCheck()`, computed when shown; 2026-10-07 "orła cień" became another song by its mood), `checked` (V39, NOT NULL, default true: false when the AI could not be asked and the request went to the DJ with the guest's own words — the guests' list shows it only to the guest who sent it, the TV screen never, the DJ's row says "bez oceny AI"; an accepted request of the same name counted on it marks it checked, `SongRequestRepository.markChecked` — the review of 2026-10-21, S1), `addedByDj` (V44, NOT NULL, default false: the DJ or the staff added it in the panel — "Dodaj piosenkę", below), `viaKiosk` (V56, NOT NULL, default false: a guest sent it at the venue's kiosk — the queue marks it "🖥 kiosk"), `votes` (V15, ≥ 1: how many guests asked for it — see below), `dedications` (V38: how many guests' dedications it has —
`SongRequestDedicationEntity`),
`requestNumber` (V28: the song's number at its party, "#27" — given to a request that reaches the queue, from the party's
`request_counter` raised in the same statement under the party's advisory lock (`UPDATE … RETURNING`; `SongRequestVotesIT`: 16 new
songs at once get 1–16); "Wyczyść historię" sets the count back to the highest number still in use (`resetRequestCounter` — #1
again with the queue empty), so a number a song has is never given twice; unique per party (`uk_song_requests_party_number`); a vote
keeps the waiting song's number, a request the AI rejected has none), `tips` (V28, ≥ 0: the tips the DJ counted — see "Numbers and
tips" below),
`style` (the vibe it was judged against), `decision` (`accepted` / `rejected` / `played`), `djComment` (500), `requestedAt`, `trackUrl` (500; the "Podejrzyj" link — YouTube's search results for the song, `util/YouTubeSearchLinks`: a page the
DJ's browser opens, no API; a `lyrics` request, and a song with none of the guest's words, is searched by the guest's own words,
everything else by the AI's name — decided when saved), `playedAt`
(V6; set only when the DJ marks it played), `skippedAt` (V20, "Pomiń"), `clearedAt` (V25: cleared with the whole queue — the AI's
comment kept, the history says "WYCZYSZCZONE" — on a phone 🧹; unlike a skip, no song kept out and no "↩ Przywróć"), `rejectReason`
(V62, `RejectReason`: DUPLICATE — the song was on the recently played list the AI was given —, OFF_VIBE — any other refusal; set by
`SongEvaluationService.rejectReason` when an AI's rejection is saved, the prompt unchanged; null otherwise and before V62; the evening
summary counts them). Index `idx_party_decision_time (party_code, decision, requested_at DESC)`. `@PrePersist`
truncates the long fields. **Retention: 30 days from `requestedAt`** — `SongRequestRetentionService` deletes nightly at 04:45 Polish time (`@Scheduled(zone = Times.SCHEDULE_ZONE)` — without it the hours were the JVM's, UTC on Railway) in
batches of 1000, at most 200 batches a night; and with the account. **A request waits at most a day:** at 04:40 (Polish time) the same service clears from every queue what has waited
more than 24 h (`clearOldWaitingRequests` → `clearWaitingRequestedBefore`: rejected with `cleared_at`, as "Wyczyść kolejkę" — the history
shows it cleared). The queue is the party's 300 oldest waiting requests and a DJ keeps one code for every event, so last week's requests
stood above tonight's and, past 300, hid them (review 2026-10-14 W10, decided for the owner: "zrób jak uważasz"). Within a day the
party's own limit is 300, so no "more than 300" note is needed. Both also run after every start (`atStart`, `ApplicationReadyEvent`;
a failure is logged and never stops the start), so a night the app was down skips nothing. Each run writes its INFO line also when
it removed nothing ("Queue cleanup: 0 …", "Song request cleanup: deleted 0 …", "Staff invitation cleanup: deleted 0 …"): the log
shows that the 30-day purge of the privacy policy ran.

