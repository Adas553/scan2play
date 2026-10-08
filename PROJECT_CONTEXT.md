# Scan2Play — Project Context

> The application as it is on branch `dev` (2026-10-04). Read it before an architectural change; `AGENTS.md` says how to work,
> `SESSION_HANDOFF.md` what is going on now; the review of 2026-09-30 and what was fixed: `docs/history/review-2026-09-30.md`.
> **History** — the decisions, the owner's reports, what was tried and measured, how every stage was verified — is in
> `docs/history/project-context-2026-10-01.md` (this file word for word before it was cut down, review 7.1; it still describes the
> YouTube player, Auto-Pilot and the background playlist) and `docs/history/session-handoff-2026-09.md`. The full application with
> YouTube and Spotify is the tag `full-player-2026-10-04` (branch `archive/full-player`). Keep this file to what is true now.
> Section numbers are referred to from the code and from applied migrations (5.4, 10, 13, 14) — keep them.

---

## 1. Product Overview

**Scan2Play** is an AI-powered music request platform for events. Guests scan a QR code and send song requests from a phone;
Google Gemini decides whether each one fits the party's vibe. The DJ plays from their own software (VirtualDJ, Serato, rekordbox…) —
**Scan2Play plays nothing**: it collects and filters the requests, counts requests for the same song as votes, and the DJ marks each
one played or skips it. One kind of party ("Twój program DJ-a"). YouTube and Spotify were removed on 2026-10-04 (Spotify: development
mode only, and its policy forbids this use; YouTube: 100 API searches a day shared by every party, and its terms limit playing to
personal use) — migrations V18 and V19.

**Production URL:** `https://www.scan2play.com.pl` (Railway, behind Cloudflare; `main` is live since 2026-10-06, Section 10).

---

## 2. Technology Stack

| Layer            | Technology |
|------------------|------------|
| Language         | Java 21 |
| Framework        | Spring Boot 4.0.3 (Spring MVC, Thymeleaf, Spring Security + OAuth2 Client, Spring Data JPA) |
| Front end        | Thymeleaf pages, Bootstrap 5 (webjar `org.webjars:bootstrap`, served by the app at `/webjars/bootstrap/…`, the version only in `pom.xml` — `webjars-locator-lite`), plain JavaScript — the dashboard's scripts are ES modules, no bundler, no framework |
| Database         | PostgreSQL 18 (Railway `postgres-ssl:18`), schema by **Flyway** (Section 10) |
| AI               | Google Gemini (`google-genai` 1.38.0), model `gemini-3.5-flash` (thinking level low) |
| Other            | ZXing 3.5.3 (QR codes), Caffeine (caches), Maven; the guests' song suggestions come from Apple's iTunes Search API, asked by the browser |
| i18n             | `messages.properties` (EN), `messages_pl.properties` (PL) — non-ASCII as `\uXXXX` escapes |

---

## 3. Architecture

A monolithic Spring Boot application, layered:

```
controller/   HTTP: Thymeleaf views, HTML fragments for AJAX, a few JSON endpoints
service/      business logic
repository/   Spring Data JPA
entity/       JPA entities        model/   enums, records        config/  Spring beans
util/         CodeGenerator, SocialLinks, SongNames, Texts, Times, TipLinks, YouTubeSearchLinks
```

Server-rendered pages with AJAX: the DJ dashboard polls the guest queue every 3 s (ETag / 304) and sends its forms by `fetch` (the
DJ keeps their place in the list; on a phone the settings stay as they were). A hidden window does not poll (review 3.4) and asks at
once when it is shown again. **Single instance by design** (Section 13): the caches and the guest limits are in memory. The HTTP
sessions are in PostgreSQL (Spring Session JDBC, `V11`; review 2.3): a deploy or a restart does not log the DJs out.

---

## 4. Domain Model

### 4.1 Entities (database tables)

**Every moment is a `timestamptz` column and an `Instant` in Java** (`V12`, review 1.8) — the same on a machine in Poland and on a
server in UTC; the pages show them in Polish time (`util/Times`: the lists show `clock` — "20:04" — with the day under it, "dziś" /
"wczoraj" / `day` "29.09" (`daysAgo`; fragment `components :: moment`), and `display` — "29.09.2026 20:04:59" — as the cell's title;
a fixed-width UTC `sortKey` for the lists' `data-val`).

**`PartySettingsEntity` → `party_settings`** — one party, owned by one DJ (`owner_id` UNIQUE: one party per DJ).
`partyCode` (5 characters of `[A-Z0-9]`, unique — in the QR code), `ownerId` (the OAuth2 subject), `active` (accepting requests),
`globalVibe` (`VibeType`; `ANY` = no genre: the AI judges by the DJ's note alone), `vibeNote` (V16, ≤ 150: the DJ's own words about
the vibe — the AI gets them as a block of the prompt, `prompt-vibe-note_{pl,en}`, the guests see them above the form;
`POST /dj/dashboard/vibe-note`, one line, empty clears), `djName` (V17, ≤ 60: who plays, e.g. "DJ Koko" — the guests see
"🎧 Gra: DJ Koko" under the page's title; `POST /dj/dashboard/dj-name`, one line, empty clears), `instagramUrl` / `facebookUrl` /
`tiktokUrl` (V24, ≤ 200: the DJ's profiles, set in a card under the dashboard's QR code — the guests see buttons with the sites' icons (Bootstrap Icons' paths inline, MIT) under "🎧 Gra: …" (new tab, `rel="noopener noreferrer
nofollow"`), the QR print "Instagram @djkoko"; `POST /dj/dashboard/dj-links` takes "@name", a name or a link copied from the app and
keeps an https address on that site, `util/SocialLinks` — anything else, a look-alike host too, is a 400 and nothing is saved; the
form shows why, `[data-form-error]` in `forms.js`; no result-page nudge, by the owner's choice), `tipUrl` (V27, ≤ 200: the
DJ's tip link — the DJ's page on Revolut, PayPal, buycoffee.to, Suppi, Tipply, Buy Me a Coffee or Ko-fi; `POST /dj/dashboard/tip-link`,
a field under the profiles; `util/TipLinks` keeps an https address on one of those hosts with a plain path — no query, login part or
port; anything else is a 400 and nothing is saved; the guests see "💸 Napiwek dla DJ-a" on the party page — with "revolut.me/djkoko · prosto do DJ-a, poza
Scan2Play" under it — and under an accepted request without that line (`components :: tip`, `withNote`), the QR poster under the profiles and a card
in its texts' column "Napiwek / Tip: revolut.me/djkoko"; the money never passes through Scan2Play — the privacy policy says so),
`commentStyle` (V22, `CommentStyle`:
CLASSIC / FUNNY / SARCASTIC / SHORT (V26: "sarcastic (gentle)" gone, its parties sarcastic), NOT NULL, default CLASSIC: how the AI words its comment to the guest — a block
of `prompts/prompt-comment-style_{pl,en}.txt` closes the prompt's rules, CLASSIC adds none; every style keeps "mock the request, not
the person", no profanity and no song's name in an accepted one; the dashboard's list "💬 Komentarze AI",
`POST /dj/dashboard/comment-style`),
`requestLimit` / `cooldownMinutes`
(the guest's own limit, 1–100 / 1–1440), `duplicateCheckWindow` (0–50 recently played songs the AI must not repeat). The column
`request_counter` (V28, not mapped — written only by `SongRequestRepository.nextRequestNumber`, so a save of the settings never
overwrites it): how many song numbers the party has given.

**`SongRequestEntity` → `song_requests`** — a guest's request. `partyCode`, `songName` (255; the song as the AI named it, `SongNames.tidy`: a control character is a dash, every dash "-" — the AI once copied a
guest's "–" back as a backspace; the AI is given the guest's words with "-" too, `forPrompt`), `guestText`
(150, V14: what the guest typed, as typed — one line, what the AI is given; null for older requests; the queue and the history always show
it under the song — the DJ checks the AI, 2026-10-04; when the AI's song has none of the guest's words — `SongNames.sharesNoWord`: a
word of 3+ letters, its stem — the first max(3, length − 2) letters — looked for in the `comparable` name — the queue and the history mark it
"⚠ Sprawdź", `needsCheck()`, computed when shown; 2026-10-07 "orła cień" became another song by its mood), `votes` (V15, ≥ 1: how many guests asked for it — see below),
`requestNumber` (V28: the song's number at its party, "#27" — given to a request that reaches the queue, from the party's
`request_counter` raised in the same statement under the party's advisory lock (`UPDATE … RETURNING`; `SongRequestVotesIT`: 16 new
songs at once get 1–16); "Wyczyść historię" sets the count back to the highest number still in use (`resetRequestCounter` — #1
again with the queue empty), so a number a song has is never given twice; unique per party (`uk_song_requests_party_number`); a vote
keeps the waiting song's number, a request the AI rejected has none), `tips` (V28, ≥ 0: the tips the DJ counted — see "Numbers and
tips" below),
`style` (the vibe it was judged against), `decision` (`accepted` / `rejected` / `played`), `djComment` (500), `energyLevel`,
`requestedAt`, `trackUrl` (500; the "🔍 Podejrzyj" link — YouTube's search results for the song, `util/YouTubeSearchLinks`: a page the
DJ's browser opens, no API; a `lyrics` request, and a song with none of the guest's words, is searched by the guest's own words,
everything else by the AI's name — decided when saved), `playedAt`
(V6; set only when the DJ marks it played), `skippedAt` (V20, "Pomiń"), `clearedAt` (V25: cleared with the whole queue — the AI's
comment kept, the history says "WYCZYSZCZONE" — on a phone 🧹; unlike a skip, no song kept out and no "↩ Przywróć"). Index `idx_party_decision_time (party_code, decision, requested_at DESC)`. `@PrePersist`
truncates the long fields. **Retention: 30 days from `requestedAt`** — `SongRequestRetentionService` deletes nightly at 04:45 in
batches of 1000, at most 200 batches a night; and with the account.

**Votes** (`SongRequestCommandService.saveOrVote`, V15): an accepted request for a song that already waits in the party's queue (the
same name by `util/SongNames.comparable` — case, accents, punctuation ignored) is not a row of its own: the waiting song gets
`votes + 1` (a conditional `UPDATE … WHERE decision = 'accepted'`; when the DJ played it meanwhile, the request is a new row) and
keeps its name, link and first guest's words. The guest's own waiting song asked for again changes nothing and gives the guest's limit
back (`DjResponse.ownSong`). Under a per-party advisory lock ("S2PR"), so guests asking at once make one row (`SongRequestVotesIT`,
20 rounds × 16 guests — red without the lock). A request the AI rejected this time for a song that waits is a vote too, and the
guest hears the verdict the song was taken with (the AI does not judge a song the same way every time — 2026-10-04, a song accepted
once, then rejected four times while it waited); any other rejected request is its own row. The AI's duplicate rule lists only
PLAYED songs, the latest to play first (`findRecentlyPlayed`; a waiting one is a vote). `SongRequestEntity` is `@DynamicUpdate`: the
DJ's "played" / "skip" writes only the columns it changed, so a vote that commits meanwhile is kept (`SongRequestVotesIT`). **A song
the DJ skipped** ("Pomiń" — usually "I do not have it") is not saved again for 2 hours from the skip (`skipped_at`, V20; the owner:
12 h was too long): the next guest who asks for it hears `guest.skipped_by_dj` ("Tej piosenki DJ teraz nie zagra — wybierz inną"),
nothing reaches the queue (`Outcome.SKIPPED_BY_DJ`, `findSkippedByTheDj`). A song only cleared with the whole queue may be asked for
again. **A skip by mistake is undone** ("Cofnij" in a bar for 8 s after the skip, "↩ Przywróć" at the request in the history;
`POST /dj/dashboard/restore`, `DjService.restoreSkippedSong`, under the same lock): the request waits again in its old place with its
votes and the guest's words, `skipped_at` cleared — unless the same song waits already. The queue's ETag counts the votes too (`computeFingerprint`: count-maxId-votes). The DJ's queue and
history have a "Głosy" column (sorted most-first on the first click, `data-sort-first="desc"`; the history sorted by it is the
party's ranking); the guest page's list is sorted by them (below) and the result page says "Ktoś już o to prosił — dodaliśmy
Twój głos! Głosów: N".

**Numbers and tips** (V28, the owner 2026-10-08): every song in the queue has a stable number. The guests see "#27" before the song
in "🔥 Prośby gości" and "Numer Twojej piosenki: #27" under an accepted request; with the DJ's tip link (V27) the button says what to
write in the payment's title, gently ("Jeśli chcesz, wpisz #27 w tytule wpłaty…" under a request, "…numer piosenki z listy" on the
party page — the owner: a plain order sounded off-putting). Scan2Play never sees the payment: the DJ sees it in their bank and taps
"💸" at the song in the queue — not in the history, by the owner's choice (`POST /dj/dashboard/tip-count`, `id`,
`add=false` takes one back; `DjService.countTip` → one atomic `UPDATE`, only the DJ's own song with a number, never below 0; the queue's
cache goes, the fingerprint counts the tips). The DJ finds the song by typing "27" or "#27" in the search (`list-tools.js`: a number
matches `data-song-number` exactly, words match names). The number is the song's ID, the first column of the DJ's queue and history
("ID", sorted as a number; on a phone the queue card starts with it, the history card has it on the line under the song). The queue's
own place numbers ("4.", a CSS counter before the title) stay on a wide screen only — on a phone the card shows the ID alone (the
owner, 2026-10-08). A tip only marks the song ("💸 2"); it does not move it up (the owner's choice for now).
The evening summary counts them ("Napiwki", "💸 N" at the songs) and the CSV has "Numer" and "Napiwki".

**A guest's 👍** (`GuestVoteService`, the owner 2026-10-08, "A": one vote per song, as many songs as the guest likes): every waiting
song on the guest page but the guest's own has a "👍 N" button — outlined, filled once given; a second tap takes it back →
`POST /p/{partyCode}/vote` (`id`, `on`), sent by `guest-party.js` in the background, answered with the list again (a note when it did
not count: the song played or skipped meanwhile, the network's limit, the party ended). Of that answer the page takes only each song's
votes (`applyVote`; the answer is the fragment `voteAnswer` — that song's row, by `data-song-id`, or only the note): nothing moves under the guest's finger (the owner: "ekran nie może skakać") — the new order
comes with the next fetch of the list; a song gone meanwhile stays in its place, dimmed, its 👍 off; the note floats over the page
(`.s2p-vote-note`, fixed) for 4 s; the button has one width whatever the count. `SongRequestRepository.addGuestVote` /
`removeGuestVote`: one atomic `UPDATE` each, only a song of that party that still waits (the id comes from the guest), never below
1 (the first guest's own vote) — `SongRequestRepositoryIT`, 40 parallel votes on PostgreSQL. Which songs the guest gave their 👍 is
kept in memory by session id (the check and the `UPDATE` in one Caffeine `compute`: a double tap counts once) and in the session
(`myVotes_<code>`, ≤ 100; it outlives a deploy). The guest's own request is their vote already (no 👍 on it), and a song with their 👍
asked for again is theirs (`ALREADY_YOURS`, "Ta piosenka już czeka w kolejce i ma Twój głos"). A 👍 refreshes the queue's cache, so the
DJ's "Głosy" and the guest's count change at once. A guest who drops the cookie is a new guest — a vote is a hint for the DJ, not an
election; `guest.limit.votes-per-ip-party` (300 per the per-network window) bounds one network.

**`FeedbackEntity` → `feedback`** — the DJ's bug reports and ideas (`message` ≤ 2000).

**`PushSubscriptionEntity` → `push_subscription`** (V21) — one of the DJ's browsers that takes notifications of new requests:
`ownerId`, `endpoint` (≤ 1000, UNIQUE: the push service's address of that browser — only Google FCM, Apple, Mozilla or Microsoft
WNS hosts are taken, `PushSubscriptionService.isPushServiceEndpoint`), `p256dh` / `auth` (its encryption keys), `locale` (the
notification's language), `createdAt`. At most 10 per DJ (the oldest goes); gone when switched off, on a 404 / 410 of the push
service, or with the account.
**`spring_session`, `spring_session_attributes`** (V11, Spring Session's own schema, no entity) — the HTTP sessions; expired ones are
deleted every minute.

### 4.2 Enums

`HistoryFilter` all / played / rejected (an old link's "guest" / "background" reads as all) · `VibeType` ANY and 16 genres (V16:
Polish hits, 2000s/2010s, R&B & soul, folk / biesiada, kids added; bachata, salsa and reggaeton merged into LATINO — the rows moved
by the migration).

### 4.3 Records

`DjResponse` (the AI's answer: `decision`, `comment`, `songName`, `energyLevel`, `requestKind` title / artist / lyrics / mood /
unchecked, `requestId`, votes, `ownSong`), `HistoryEntry` (one line of the history), `GuestQueueService.GuestQueue` (what the guest
sees under the form), `PlayHistoryService.Page`.

---

## 5. User Flows

### 5.1 DJ Flow

Landing page `/` → one tile, "Zbieraj prośby gości" → `/start` → Google's login → `/dj/dashboard` (`DjSessionHelper.getPartySettings`
creates the DJ's party on the first visit; the old tile links `/start/{kind}` lead to the login too). The dashboard: the guest queue
(polled every 3 s; sort, search, a number per request) with "Oznacz jako zagrane", "Pomiń" (`POST /dj/dashboard/dismiss`: the request
leaves as rejected with `skipped_at`, keeping the AI's comment — the history says "POMINIĘTE" in place of the verdict (amber, not the red "ODRZUCONE"; "⏭ Pominięta przez DJ-a …" in its title; on a phone only ⏭, beside the votes; still
under the "Rejected" filter), V23; "Cofnij" for 8 s, "↩ Przywróć" in the history — Section 4.1) and "🔍 Podejrzyj" on every waiting request; the vibe, the vibe note, "Kto gra", the guest
limits and the use of the server limits; the QR code (`/dj/qr-print`: an A4 poster or eight table cards, Polish and English); the
history; feedback; end / resume the party, delete the account, log out. The forms are sent in the background (`forms.js`; not logout
and account deletion); a song played or skipped leaves the list at once (`s2p:guest-queue-changed` → the queue is fetched again).

**Notifications on the DJ's devices** (Web Push; only when the server has the VAPID keys — Section 10): the switch "🔔 Powiadomienia
na tym urządzeniu" in the card "Twój program DJ-a" (`js/dashboard/push.js`), per browser — the permission, the service worker
`/sw.js` (scope `/dj/`), the subscription with the server's key → `POST /dj/push/subscribe`; off → `/dj/push/unsubscribe`. A **new**
song on the list (accepted, or unchecked with the AI down; not a vote on a waiting one) → `PushNotificationService` sends
"🎵 Nowa prośba — <song>" to each of the DJ's devices, off the guest's thread (a pool of 2, queue 200, TTL 1 h, urgency high); the
service worker folds the notifications of one party into one ("🎵 Nowe prośby: 3"), a tap opens the dashboard. On an iPhone only
the dashboard added to the Home Screen (the web app manifest `/manifest.webmanifest`, iOS 16.4+) can take them — the switch says
so in Safari. Delivery is best effort: the dashboard stays the truth.
**"📲 Zainstaluj aplikację"** in the same card (`js/dashboard/install.js`): the browser's own offer to install comes when it decides
and, once dismissed or the app removed, not again for months — the button asks when the DJ wants (Chrome / Edge: the kept
`beforeinstallprompt` opens the install window — once per offer: closed without installing, the next click says where the browser's
menu has it; an iPhone's Safari: the steps "Udostępnij → Do ekranu początkowego"; nothing in the installed app or a browser that
never offered).

**When the AI cannot be asked** (an error, a timeout), the request goes on to the DJ unchecked — accepted, the guest's words, the note
`ai.unavailable.to_dj`, `requestKind` `unchecked`; the guest sees "PRZEKAZANE".

**On a phone** (narrower than 768 px): the vibe, the limits and the QR code fold under one button "⚙️ Ustawienia, klimat i kod QR"
(`.s2p-phone-settings`; folded by `app.css` alone, `settings-toggle.js` opens them and keeps the choice for the tab in
`sessionStorage`); the queue comes first, every waiting request is a card with big buttons — "▶ Zagrane" (filled, two thirds of the row) and "⏭ Pomiń" (outlined, amber under the pointer); the list scrolls with the page.

**The active queue**: each request has its place before the title on a wide screen — a CSS counter in `app.css`, so it follows the
polled list, the sort and the search by itself — and its ID in the first column ("#27", V28 — see "Numbers and tips"). "🧹 Wyczyść kolejkę" beside the heading (shown only while a request waits — `:has`) asks first (`data-confirm`) and
sends `POST /dj/dashboard/clear-queue`: the waiting requests go to the history's rejected ones, with `cleared_at` and the AI's
comment (V25) — the history says "WYCZYSZCZONE" in place of the verdict ("🧹 Wyczyszczona przez DJ-a …" in its title), amber as a skip. Ending the party does not clear the
queue (the DJ may pause it for a break or a limit).

Every dashboard window follows the party's state within one poll: the party open or closed (`X-Party-Active` — the "party closed"
banner and the "end party" button) and the guest limits (`X-Guest-Limits` — the warning `party-full` —, `X-Guest-Limits-Use`).

**The history** (`PlayHistoryService`): the party's requests that played or were rejected, by `COALESCE(played_at, requested_at)`,
newest first, one bounded query (`limit + 1`). The History tab (in place of the queue) and the standalone page `/dj/history-view`:
filters All / Played / Rejected applied by the server, "Show more" (50 at a time, ≤ 300); both keep the search text and the sort by a
column (`captureListState` / `restoreListState` in the tab; on the standalone page, which loads itself again, through
`sessionStorage` once). A heading row where a new day starts, Polish time (`tr[data-day-heading]`: "dziś — sobota, 03.10",
"wczoraj — …", "wtorek, 29.09"; `Times.dayKey` / `weekday`) — the history keeps 30 days, so the parties of a month share it.
On a phone (narrower than 768 px) every entry is a card, as in the queue: the song across it, then the votes, the decision, the skip
and "↩ Przywróć", and the AI's comment — one line with "…", a tap opens it (`.s2p-history-table`, `app.css`, `list-tools.js`); the column sort stays as a row of buttons.
`list-tools.js`: the search hides a heading with no row left under it; a sort by a column hides them all (`.s2p-sorted`), undoing it
puts the server's order back. **"🗑 Wyczyść historię"** (shown while the history is not empty; asks first — `data-confirm`) sends
`POST /dj/dashboard/clear-history` (`DjService.clearHistory`, under the party's lock "S2PR"): the party's played and rejected requests
are **deleted**, the guests' words with them — never a waiting one, and never one the DJ skipped within the last 2 hours (it keeps its
song out of the queue and stays in the history until that ends). The AI's duplicate rule reads the played songs, so after it the AI no
longer knows what played. The History tab sends it in the background and loads itself again (`s2p:history-changed`).

**"📊 Podsumowanie wieczoru"** (beside "🗑 Wyczyść historię", a new tab; its question points to it): `/dj/summary`
(`DjSummaryController`, `EveningSummaryService`, `summary.html`) — one evening of the DJ's own party (no party code asked for) on a
white page to print or save as PDF ("🖨 Drukuj / PDF", `js/summary.js`, `css/summary.css`): an **evening runs from 6:00 to 6:00 Polish
time** (`EVENING_STARTS`; a wedding past midnight is one evening); the evenings with requests to pick (≤ 31, latest first, with their
counts — `SongRequestRepository.findEvenings`, native SQL with the same 6 hours); the counts — requests (rows: a song asked for again
is a vote), played, the guests' votes (of what the AI let through), rejected by the AI, skipped, cleared, still waiting —, a line of how long the played requests waited (from the request to "played": the
average and the longest), the 10 most wanted (votes, then the earlier), "🙋 Chcieli, a nie usłyszeli" (the wanted ones that did not
play — waiting, skipped, cleared — with their status), "🎤 Najczęściej proszeni wykonawcy" (the artist of the AI's "Artist - Title",
grouped by `SongNames.comparable`, the most votes first, then the most songs), the requests every half-hour (`<meter>` bars, the quiet
half-hours between too, the busiest named), what played in the order it played, the DJ's name and our logo. All from one bounded read of the evening's requests (`findRequestedBetween`,
≤ 2000) — so it lasts as `song_requests` do (30 days) and goes with "Wyczyść historię". `/dj/summary/csv`: the same evening as a CSV
file (`scan2play-<day>.csv`; BOM and ";" for Excel, the DJ's language, every value quoted, and one that starts with `= + - @` or a tab
gets an apostrophe — a guest's text is never a formula).

### 5.2 Guest Flow

`/p/{partyCode}` (no login) → the form: one field for a song — a title, an artist or a line of the lyrics (song suggestions from
iTunes, asked by the browser) → `POST /p/{partyCode}/request` (`songName`; an async `Callable`) → the result page (decision, the AI's
comment, the votes). A request the AI reads as a mood is not saved: the guest is back at the form with the text and
`guest.error.song_only` (the DJ sets the mood). An empty request (only spaces) goes back at once with `guest.error.empty` — no
limit used, no AI asked. Under the form: "Twoja prośba „…” czeka u DJ-a" — or, with several, "Czekają u DJ-a Twoje prośby: 3" — and
**one list**, "🔥 Prośby gości": every waiting request once, the most votes first and the newest first among equal votes
(`GuestQueueService.byVotes`), unnumbered — the DJ picks the order —, the first 5 shown and the rest folded under "Pokaż pozostałe
prośby (N)" (a `<details>`; **its rows are not in the list's fragment** — `GET /p/{partyCode}/queue/more`, the fragment `moreList`,
fetched only when it is unfolded and again when the list is fetched while it is unfolded: with 300 waiting, every guest's refresh
carrying them all was ~200 KB), with **"Szukaj w prośbach"** — the list filtered as it is typed, by every word, no accents needed
("baska" finds "Baśka"), "Nie ma takiej prośby…" when nothing matches, cleared with folding; on the right of every song ONE pill of one size with its votes (the owner: badges of different sizes were
untidy) — a "👍 N" button (Section 4, "A guest's 👍"), outlined, yellow once given, or, on the guest's own request, a green one that
is not a button (it is their vote already) with "Twoja" beside the song's name; filled pills get a thin dark edge (a yellow 👍 on
yellow vanished on Windows). The guest's own songs are marked on the party page only (not on the result page, which is about its
one request; the guest's requests are remembered in the session).
Until 2026-10-08 there were "🔥 Najwięcej głosów" and "Ostatnio wysłane" as well — one song showed up to three times (the owner:
too much). Fetched again when the guest comes back to the page and on "↻ Odśwież", no timer. In the installed app (the dashboard on
the Home Screen) the guest page, the result page and the "party ended" page have "← Twój panel DJ-a" (`components.html`,
`back-to-dashboard`; shown by `@media (display-mode: standalone)` only): the app takes every address of the site (`scope: "/"`), so
a DJ testing their QR code landed on the guest page in it with no address bar and no "back". Only a DJ can have the app (the
manifest is on the dashboard alone); the link leads to whoever is logged in. `scope: "/dj/"` was not chosen: the Google login
passes through addresses outside it, and an iPhone's Home Screen app opens those apart, with its own cookies (not tried yet). An ended party shows
"DJ nie przyjmuje teraz próśb" with "↻ Sprawdź ponownie" (the party's link) — the landing page is for DJs.

**What reaches the AI:** the guest's text as one line ≤ 150 characters, `"` made `'` (`SongEvaluationService.forPrompt`); the style
decided by the server (`GuestController.styleOf`): the DJ's vibe when set, else `ANY` — the guests pick no vibe.

**Limits** — each counted **before** the AI is asked:
1. the guest's own: up to the DJ's `requestLimit` requests, then a wait of `cooldownMinutes` **from the last of them**, after which the
   whole limit is back; counted by the session's id in memory (`GuestSessionService.tryAcquire`, one atomic `compute` — not in the
   session: with the sessions in the database every request works on its own copy, so parallel requests could not see each other's
   count there) — `guest.error.rate_limit`. A request that came to nothing gives its place back (`giveBack`: a mood sent back to
   the form, a rejected song — the owner, 2026-10-04 —, the guest's own waiting song asked for again). The guest reads the wait of this limit and of the next one as
   `GuestController.waitText` says it: whole minutes rounded up from a minute on ("3 min"), seconds below it ("45 s");
2. client IP + party: `guest.limit.per-ip-party` (30) per `guest.limit.per-ip-window-minutes` (10) — loose, a venue's Wi-Fi is one
   address — `guest.error.too_many_requests`;
3. party: `guest.limit.per-party-daily` (300) per 24 h — `guest.error.party_daily_limit`.

2 and 3 are `GuestRequestLimiter` (Caffeine, in memory); 0 switches a limit off. The guests' 👍 have their own per-network limit
there (`guest.limit.votes-per-ip-party`, 300 per the same window, `tryAcquireVote`): no AI call, so a vote never uses up a request. The address is `getRemoteAddr()` or the header named
by `guest.client-ip-header` (`CF-Connecting-IP` — set on Railway). The DJ sees the use of 2 and 3 under the limits form (badges:
grey, yellow from 80 %, red) and a warning above the queue while the party's limit stops guest songs.

### 5.3 (Spotify — removed 2026-10-04, migration V18)

### 5.4 (The YouTube player, Auto-Pilot and the background playlist — removed 2026-10-04, migration V19)

The old migrations V2–V9 and the browser tests' history refer here: the description is in `docs/history/project-context-2026-10-01.md`,
Section 5.4, and the code in the tag `full-player-2026-10-04`.

---

## 6. File Inventory

### 6.1 Controllers

| Class | Purpose |
|-------|---------|
| `HomeController` | `/`: the landing page, or the dashboard for a logged-in DJ; `/start` → Google's login |
| `DjDashboardController` | the dashboard, the queue poll (`/dj/dashboard/updates`), the history page and fragment, the QR print page |
| `DjPartySettingsController` | start / end party, vibe, vibe note, "Kto gra", limits (bounded), account deletion |
| `DjSongController` | mark played, skip, clear the queue, count a tip ("💸", V28) |
| `DjSummaryController` | the evening summary page and its CSV (`/dj/summary`, `/dj/summary/csv`) |
| `DjSessionHelper` | the party of the logged-in DJ (cached in the session; made on the first login — tabs that make it at once look again, `FirstLoginIT`) and **`validateOwnership`** (IDOR) |
| `GuestController` | the guest's page, its list, the request (limits, style, evaluation), the guest's 👍 |
| `FeedbackController` | `POST /dj/feedback` (JSON) |
| `PushController` | `POST /dj/push/subscribe`, `/unsubscribe` (JSON: the browser's `PushSubscription.toJSON()`) |
| `CspReportController` | `POST /csp-report` — the browsers' CSP reports, logged once an hour per violation |
| `LegalController` | `/privacy`, `/terms` (a file per language) |
| `ViewAttributes` | names of the model attributes |

### 6.2 Services

| Class | Purpose |
|-------|---------|
| `SongEvaluationService` | the guest's request: Gemini (`askAi`, the prompt per language) → the "🔍 Podejrzyj" link → save or vote |
| `DjService` | the guest queue (`dashboardQueue` cache), its fingerprint (ETag), mark played, skip (`dismissSong`), clear the queue |
| `PlayHistoryService` | the history (Section 5.1) |
| `EveningSummaryService` | "📊 Podsumowanie wieczoru": the party's evenings, one evening's counts, waits, top songs, missed songs, artists, half-hours and setlist, the CSV (Section 5.1) |
| `GuestQueueService` | what the guest sees under the form (with the most wanted songs, every waiting one by votes) |
| `GuestVoteService` | a guest's 👍 on a waiting song: once per song, taken back with a second tap (memory + session) |
| `SongRequestCommandService` | saves a guest's request, or counts it as a vote on the same waiting song (advisory lock) |
| `GuestSessionService` / `GuestRequestLimiter` | the guest limits (Section 5.2) |
| `PartySettingsQueryService` / `PartySettingsCommandService` | read (cached copy) / write (evicts after commit) of the party |
| `QrCodeService` | QR codes (ZXing, cached) |
| `AccountDeletionService` | deletes all of a DJ's data and evicts the caches after the commit |
| `SongRequestRetentionService` | the nightly purge of song requests (Section 4.1) |
| `PushSubscriptionService` / `PushNotificationService` | the DJ's devices of the notifications (checked addresses, ≤ 10 per DJ) / sending a new request to them (Web Push, `zerodep-web-push-java`, off the request thread) |
| `AiHealthMonitor` | counts the requests the AI answered and the ones that went to the DJ unchecked; every 5 min with any unchecked, one ERROR line "AI check: N of M guest requests …" |

### 6.3 Configuration

`SecurityConfig` (routes, OAuth2 login, logout, CSRF, the CSP — Section 8), `AppConfig` (caching, scheduling, the Caffeine caches,
the `webmanifest` MIME type), **versioned static addresses** (`spring.web.resources.chain.strategy.fixed`: the templates link
`th:src` / `th:href="@{/js/...}"`, served as `/<RAILWAY_GIT_COMMIT_SHA>/js/...` — locally `/dev/...` —, so a deploy changes every
script's and style's address and no cache on the way keeps an old one; the dashboard's modules import each other relatively and stay
in the same version; 2026-10-06 Cloudflare kept serving an old `main.js` after a deploy). `VersionedAssetCacheFilter` lets the
browsers and Cloudflare keep them for a year (`Cache-Control: public, max-age=31536000, immutable`): an address under the running
deploy's version always gives the same file (an old version is a 404). Only for a commit's version, never `dev` (devtools changes the
files under it); everything else keeps Spring Security's `no-store`,
`GeminiConfig` (the Gemini client, 15 s timeout; the `ObjectMapper` bean).

### 6.4 Templates

`landing.html`, `dashboard.html`, `history.html` (its `historyTableContent` fragment is also the dashboard's History tab),
`qr-print.html`, `summary.html` (the evening summary), `index.html` (the guest's page), `result.html`, `party_ended.html`, `error.html`, `privacy[_pl].html`,
`terms[_pl].html` — every page declares its colour scheme (`<meta name="color-scheme">`: `dark`, the print pages `only light`;
`HtmlLangDeclarationTest`): Samsung Internet's own dark theme darkened a page without it — the logo's tile went grey, the yellow
tip button brown (2026-10-08); `fragments/`: `components.html` (`dj-nav`: the account buttons, the sticky tabs Panel / Kolejka / Historia, the
feedback modal; `scroll-restore-script`; `moment`; `logo` — the mark and "Scan2Play" with a cyan "2", the heading of the
dashboard and the guest page; `footer`), `guest-queue.html`. Texts the scripts need travel in `data-*`
attributes. **No inline script, no `on…=` handler and no `style="…"`** on any page (`NoInlineCodeInTemplatesTest`).

### 6.5 Static assets

| File | Purpose |
|------|---------|
| `js/dashboard/*.js` | the dashboard as ES modules: `main.js` imports `list-tools.js` (sort, search, filters, "Show more" — the history page loads it alone), `forms.js` (AJAX forms — not logout and account deletion —, `data-auto-submit`; played / skipped / cleared / restored → `s2p:guest-queue-changed`; the "Cofnij" bar after a skip; restored, the history cleared → `s2p:history-changed`), `tabs.js` (the history in place; fetched again on `s2p:history-changed`), `settings-toggle.js` (on a phone: the settings folded), `push.js` (the switch of notifications on this device), `install.js` ("📲 Zainstaluj aplikację"), `polling.js` (the queue every 3 s and its headers; at once on `s2p:guest-queue-changed` and when the window is shown again; none while hidden), `common.js`; they talk only through the `s2p:*` events of `events.js`, never through `window` |
| `js/dj-nav.js` | `form[data-confirm]` (capture phase, before `forms.js`) and the feedback form |
| `js/scroll-restore.js` | the scroll memory of the DJ pages (in `<head>`) |
| `js/guest-party.js` | the guest's page: the list refresh, "sending…" |
| `js/song-autocomplete.js` | song suggestions from the iTunes Search API (debounced, client side) |
| `js/qr-print.js`, `css/qr-print.css`, `css/app.css` | the print page; the shared styles |
| `js/summary.js`, `css/summary.css` | the evening summary: "Print / PDF", another evening picked shows at once; white, for A4 |
| `sw.js`, `manifest.webmanifest`, `images/icon-192.png`, `icon-512.png`, `apple-touch-icon.png`, `badge-96.png` | the service worker of the notifications (shows and folds them, a tap opens the dashboard; no cache, no fetch handler); the web app manifest (the dashboard on the Home Screen, `start_url` `/dj/dashboard`) and its icons (the mark on a full dark square; `badge-96.png`: the mark alone in white on transparent — the status bar's small icon, Android draws only its transparency) |
| `images/logo.svg`, `favicon.ico` | our mark: three QR finder corners and a cyan play triangle on the dark tile (2026-10-04); on the landing page, the dashboard, the guest page, the QR poster and cards; the favicon is the same mark at 16 / 32 / 48 px |

### 6.6 Resources

`application.properties` (all configuration, env overrides — Section 10), the message bundles, `prompts/` (`prompt-template_{en,pl}`,
`prompt-duplicate-rule_{en,pl}`, `prompt-vibe-note_{en,pl}`, `prompt-comment-style_{en,pl}`), `db/migration/V1..V28`.

---

## 7. External API Integrations

### 7.1 Google Gemini

- Evaluates guests' requests. Model `gemini-3.5-flash` (pinned; env `GOOGLE_AI_MODEL`), thinking level `low`
  (`google.ai.thinking-level`, env `GOOGLE_AI_THINKING_LEVEL`; a `gemini-2.x` model takes `google.ai.thinking-budget` tokens
  instead — `SongEvaluationService.thinkingConfig`). Timeout 15 s per call. Chosen 2026-10-07 by `GeminiComparison` (`src/test`,
  run from IntelliJ with the key; writes `target/gemini-comparison.md`): 30 hard requests × 2 per variant — 3.5 Flash had every
  checkable song right in 2.7–3.1 s on average (≤ 8.3 s), ~$2.5 per 1000 requests; 2.5 Flash named another song for a line of the
  lyrics on each try; 3.5 Flash-Lite made up artists.
- **The prompt** (rewritten 2026-10-07, measured by the same comparison): three steps — work out the song (`songName`,
  `requestKind`), judge it (`decision`, `energyLevel`), write the comment —, and `ANSWER_SCHEMA` orders the answer the same way
  (the verdict first let the model judge a song it had not named yet). A plain tone (no "ruthless DJ", no capitals); the DJ's note
  comes before the genre ("Salsa" with the genre ANY: Macarena rejected — the owner: salsa, not latino); in doubt, accept.
- The prompt per language (PL / EN by the guest's locale, else EN). The answer is JSON (`DjResponse`) of a given shape
  (`SongEvaluationService.ANSWER_SCHEMA`: every field required, `decision` only `accepted` / `rejected`, `requestKind` only title /
  artist / lyrics / mood); read defensively anyway — a field the app does not know is ignored, and a decision other than
  `accepted` in any case is `rejected` (the queue matches `'accepted'` exactly). A request may be a title, an
  artist or a line of lyrics; never replace it by another song; not knowing a song (a new one) is no reason to reject it; a mood or
  an occasion is `requestKind` `mood` (sent back to the guest); on a rejection `songName` is what the guest asked for (the code also
  falls back to the guest's text).
- AI down → the request goes to the DJ unchecked (Section 5.1; no retry), and the log says so every 5 minutes while it lasts
  (`AiHealthMonitor`: search the log for "AI check"). Duplicates: the last `duplicateCheckWindow` played songs go
  into the prompt (a waiting song asked for again is a vote, not a duplicate).

### 7.2 Web Push (the DJ's notifications)

The browsers' push services (Google FCM, Apple, Mozilla, Microsoft WNS) — RFC 8030 / 8291 (aes128gcm) / 8292 (VAPID), by
`zerodep-web-push-java` 2.1.5 (no dependencies of its own) over the JDK's `HttpClient` (connect 5 s, send 10 s). The payload is
encrypted for the device; the push service delivers it but cannot read it. 404 / 410 → the device is removed; other errors are
logged. No keys → off (`PushNotificationService.isEnabled`), keys that do not parse → off with an ERROR line, the app starts anyway.

### 7.2a (Spotify — removed 2026-10-04)

### 7.3 YouTube — no API

Scan2Play uses no YouTube API (removed 2026-10-04, V19). "🔍 Podejrzyj" is a plain link to YouTube's search results
(`https://www.youtube.com/results?search_query=…`, `util/YouTubeSearchLinks`), opened by the DJ's browser.

---

## 8. Security Model

Public: `/`, `/start/**`, `/p/**`, `/privacy`, `/terms`, `/oauth2/**`, `/login/**`, `/css/**`, `/js/**` (and `/*/css/**`, `/*/js/**`:
under the deploy's version), `/images/**`, `/webjars/**`,
`/error`, `POST /csp-report`, `/manifest.webmanifest`, `/sw.js`. Everything else needs the DJ's login; `/dj/**` validates the party's ownership
(`DjSessionHelper.validateOwnership` — IDOR). CSRF on (tokens in `<meta>` for AJAX; `/csp-report` is exempt). Logout `POST
/dj/logout`. `th:utext` only for texts of our own bundles; song names and the guests' words are escaped.

**Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`): `script-src 'self'`, styles and fonts from the app only —
**no `'unsafe-inline'` at all**: no inline script, no `on…=` handler, no `style="…"` (the templates use `s2p-…` classes of `app.css`;
scripts change styles through `element.style`, which the policy allows), checked over every template by
`NoInlineCodeInTemplatesTest`; images from the app and `data:`; `connect-src` the app and `itunes.apple.com`; no frames;
`object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; `report-uri /csp-report`. **Report-only**
while `security.csp.enforce=false` (env `CSP_ENFORCE`): the log says `CSP violation: …` for what it would block. Switch it on once
real use leaves the log quiet. The browser tests run the dashboard, the guest page and the QR print page under this policy, enforced
(Section 13); the standalone history page is not covered by them.

---

## 9. Caching Strategy

| Cache | Key | TTL / size | Usage |
|-------|-----|------------|-------|
| `partySettings` | partyCode | 24 h / 500 | `PartySettingsQueryService.getSettings` — every caller gets a **copy**; `updateSettings` evicts after its commit |
| `qr-codes` | text + size | 24 h / 1000 | `QrCodeService` |
| `dashboardQueue` | partyCode | 3 s / 200 | `DjService.getDashboardQueue`; evicted when a song is played, skipped or the queue cleared |

The DJ's party code is cached in the `HttpSession`. Account deletion evicts the party from the caches after its commit.

---

## 10. Production Configuration

### Environment variables

`GOOGLE_CLIENT_ID` / `_SECRET`, `GOOGLE_AI_API_KEY`, the database (`PGHOST`, `PGPORT`, `PGDATABASE`, and **`PGUSER` / `PGPASSWORD`
required** — no defaults since review 5.5; Railway sets all five), `SCAN2PLAY_GUEST_URL` (the base URL in the QR code; locally the LAN
address, so a phone on the same Wi-Fi can open it), `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` (Railway), **`VAPID_PUBLIC_KEY` /
`VAPID_PRIVATE_KEY`** (the notifications' key pair, made once by `VapidKeyGenerator` in `src/test/java` — secrets; without them the
notifications are off; a new pair makes every device switch them on again; `VAPID_SUBJECT` optional), and the optional overrides
below. The app no longer reads `YOUTUBE_API_KEY`, `SPOTIFY_*` or `YOUTUBE_SEARCH_DAILY_BUDGET`. The DJ's login works only on
`localhost` or a public HTTPS address (Google refuses a LAN IP as a redirect URI); the guest side works through the LAN IP.

### Key application properties

| Property | Value |
|----------|-------|
| `google.ai.model-name` / `google.ai.thinking-level` (`thinking-budget` for gemini-2.x) | `gemini-3.5-flash` / low (1024) |
| `spring.jpa.hibernate.ddl-auto` | `validate` (Flyway owns the schema) |
| `server.forward-headers-strategy` | `FRAMEWORK` |
| `server.compression.*` | gzip for HTML / CSS / JS / JSON from 2 KB |
| `server.tomcat.max-http-form-post-size` | 10 KB |
| `server.shutdown` | graceful, 30 s |
| `spring.task.execution.pool.*` | 16 core / 32 max threads, queue 50 (env `ASYNC_POOL_*`) — the guests' requests (`Callable`) |
| `spring.datasource.hikari.*` | 15 max, 5 idle, 5 s connect timeout |
| `guest.limit.*` | 30 per 10 min per address + party, 300 per 24 h per party (env `GUEST_LIMIT_*`) |
| `guest.client-ip-header` | empty (env `GUEST_CLIENT_IP_HEADER`) |
| `security.csp.enforce` | `false` = report only (env `CSP_ENFORCE`) |
| `spring.session.jdbc.initialize-schema` | `never` (the tables are Flyway's, `V11`); a session lives 30 min without a request |

**The profile `local`** (`application-local.properties`): the developer's defaults `PGUSER=postgres`, `PGPASSWORD=1111`. IntelliJ's run
configuration has *Active profiles: local* (or `SPRING_PROFILES_ACTIVE=local`); without it and without the variables the application
does not start (`Could not resolve placeholder 'PGUSER'`).

### Database migrations (Flyway)

The schema is a sequence of files `src/main/resources/db/migration/V<n>__<what>.sql`, applied in order at startup before Hibernate
validates; **never edit an applied one** — not even a comment: Flyway checksums the whole file; add the next. An entity change that
touches the schema needs its migration in the same change, or the application does not start.
`spring.flyway.baseline-on-migrate=true`: a database that had tables before Flyway (production) is recorded as V1 without running it.
**`SPRING_JPA_HIBERNATE_DDL_AUTO`** is not set on Railway (removed 2026-10-06); never set it to `update`: Hibernate would change the
schema behind Flyway's back.

| Version | What |
|---------|------|
| V1 | baseline (the schema of 2026-09-28) |
| V2, V4, V5, V7, V8, V9 | the background playlist's `fallback_track`, its order, moves, play log `fallback_play`, status `SKIPPED`, the queue indexes (all dropped by V19) |
| V3 | data fix: request limits 0 → 2 / 3 |
| V6 | `song_requests.played_at` (no back-fill) |
| V10 | `youtube_search_budget` (dropped by V19) |
| V11 | `spring_session`, `spring_session_attributes` (Spring Session JDBC's schema, word for word) |
| V12 | every `timestamp` → `timestamptz`; the old values read in the session's zone = the JVM's that wrote them (Polish time locally, UTC on Railway) |
| V13 | `party_settings.active_provider` may be `REQUESTS_ONLY` (the check constraint) |
| V14 | `song_requests.guest_text` varchar(150), nullable (no back-fill: the words of older requests were never kept) |
| V15 | `song_requests.votes` integer NOT NULL DEFAULT 1 |
| V16 | `party_settings.global_vibe`: BACHATA_AND_KIZOMBA / SALSA_AND_TIMBA / REGGAETON_AND_DANCEHALL → LATINO, the check constraint with the new list; `party_settings.vibe_note` varchar(150) |
| V17 | `party_settings.dj_name` varchar(60), nullable |
| V18 | Spotify removed: Spotify parties deleted with their requests, background tracks, plays and their DJ's feedback; `spotify_*` columns dropped; the kind's check without SPOTIFY |
| V19 | YouTube removed: every party that is not `REQUESTS_ONLY` (and one with no kind) deleted with its requests and its DJ's feedback; `fallback_play`, `fallback_track`, `youtube_cache`, `youtube_search_budget` dropped; the columns `active_provider`, `playback_mode`, `fallback_playlist_url`, `fallback_shuffle` dropped |
| V20 | `song_requests.skipped_at` timestamptz, nullable: when the DJ skipped the request; the requests skipped before get their request time |
| V21 | `push_subscription`: the DJ's devices of the notifications (`endpoint` UNIQUE, index on `owner_id`) |
| V22 | `party_settings.comment_style` varchar(20) NOT NULL DEFAULT 'CLASSIC', a check of the five styles |
| V23 | data: the notes "Skipped by the DJ ⏭" / "Restored by the DJ ↩" a skip and a restore wrote in place of the AI's comment → NULL (a skip keeps the AI's comment since; `SkipCommentMigrationIT`) |
| V24 | `party_settings.instagram_url`, `facebook_url`, `tiktok_url` varchar(200), nullable: the DJ's profiles |
| V25 | `song_requests.cleared_at` timestamptz, nullable: when the DJ cleared the request with the queue; the old note "Cleared by the DJ 🧹" → NULL, its request time as `cleared_at` (`SkipCommentMigrationIT`) |
| V26 | data + constraint: `comment_style` 'SARCASTIC_LIGHT' → 'SARCASTIC', the check without it (`CommentStyleMigrationIT`) |
| V27 | `party_settings.tip_url` varchar(200), nullable: the DJ's tip link |
| V28 | `party_settings.request_counter` int NOT NULL DEFAULT 0; `song_requests.request_number` int, `tips` int NOT NULL DEFAULT 0 (≥ 0); the songs so far that reached the queue numbered in request order per party, each count set to its last number; `uk_song_requests_party_number` (party, number) unique where numbered (`RequestNumberMigrationIT`) |

Checked by `MigrationIT` (`mvnw verify -Pit`, Section 13) on an empty PostgreSQL 18, locally and on GitHub; V16, V18 and V19 also on
rows of the old kind (`VibeMigrationIT`, `SpotifyRemovalMigrationIT`, `YouTubeRemovalMigrationIT`).

**Production (first deploy of the requests-only app: 2026-10-06).** Railway project `celebrated-enjoyment`, environment
`production`: the service `scan2play` builds `main` on every push (Railpack, Java 21, custom domain `www.scan2play.com.pl` behind
Cloudflare) and the service `Postgres` (`postgres-ssl:18`, PostgreSQL 18.6, a 500 MB volume). `main` was fast-forwarded to `dev`
(`b61f4fd`); the start log showed Flyway baselining the old schema as V1 and applying V2..V20 (0.2 s), Hibernate's validation
passing, the app up in ~10 s. V18 / V19 deleted every party of April (only the owner's and friends' tests; no backup was taken, by
the owner's choice). Variables now: `GOOGLE_AI_API_KEY`, `GOOGLE_CLIENT_ID` / `_SECRET`, `GUEST_CLIENT_IP_HEADER`, the five `PG*`,
`DATABASE_URL` (unused); `SCAN2PLAY_GUEST_URL` is not set (the default is the production URL); `YOUTUBE_API_KEY`, `SPOTIFY_*` and
`SPRING_JPA_HIBERNATE_DDL_AUTO` removed. No `TZ`: the JVM is UTC. **Both services sleep** after ~10 minutes without traffic
(Railway's serverless, kept on purpose while only friends use it): the first request wakes the app (~6 s), an app that wakes
before its database fails once and Railway restarts it, and a database that falls asleep under a running app breaks the request
that holds the dropped connection (seen once, 2026-10-06 16:04). Switch the database's sleep off before real customers. Later deploys: 2026-10-06 the notifications (PR #9, V21) and the
versioned script addresses (PR #10); 2026-10-07 PR #11 via #12 (`a06e4be`: clear the history, the comment style, V22 and V23
applied in 0.05 s, "Push notifications on"). **Still
open:** `CSP_ENFORCE` stays off until a few days of real use leave the log quiet (Section 13), then `true`; Dependabot alerts and
security updates switched on in GitHub.

---

## 11. Main Dependencies

```
GuestController            → SongEvaluationService, GuestQueueService, GuestSessionService, GuestVoteService, GuestRequestLimiter, PartySettingsQueryService
GuestVoteService           → SongRequestRepository, DjService
DjDashboardController      → DjService, PlayHistoryService, QrCodeService, GuestRequestLimiter, PartySettingsQueryService, DjSessionHelper, PushNotificationService
DjPartySettingsController  → PartySettingsCommandService, AccountDeletionService, DjSessionHelper
DjSongController           → DjService, DjSessionHelper
DjSummaryController        → EveningSummaryService, DjSessionHelper, MessageSource
SongEvaluationService      → Gemini Client, SongRequestRepository, SongRequestCommandService, PartySettingsQueryService, PushNotificationService
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
| GET | `/p/{partyCode}` | the guest's page (`party_ended` when closed) |
| GET | `/p/{partyCode}/queue` | the guest's list alone: the first five (empty when closed, 404 for an unknown party) |
| GET | `/p/{partyCode}/queue/more` | the rest of the guest's list, fetched when "Pokaż pozostałe prośby" is unfolded |
| POST | `/p/{partyCode}/request` | a request: `songName` |
| POST | `/p/{partyCode}/vote` | a guest's 👍: `id`, `on` (false = take it back); with `X-Requested-With: fetch` that song's row (or a note), else a redirect to the party page |
| GET | `/privacy`, `/terms` | legal pages |
| POST | `/csp-report` | a browser's CSP report (no CSRF; 204) |
| GET | `/manifest.webmanifest`, `/sw.js` | the web app manifest (`application/manifest+json`) and the notifications' service worker |

### DJ (logged in; every endpoint with a `partyCode` checks ownership)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/dj/dashboard` | the dashboard |
| GET | `/dj/dashboard/updates` | the guest queue `<tbody>` (ETag / 304); every answer, 304 too, carries `X-Guest-Limits`, `X-Guest-Limits-Use`, `X-Party-Active` |
| POST | `/dj/dashboard/play` | `id`: a request marked played |
| POST | `/dj/dashboard/dismiss` | `id`: a waiting request skipped by the DJ → rejected, `skipped_at`, the AI's comment kept (the song stays out for 2 h) |
| POST | `/dj/dashboard/restore` | `id`: a request the DJ skipped → waiting again ("Cofnij", "↩ Przywróć"; only the DJ's own skip, not when the same song waits) |
| POST | `/dj/dashboard/clear-queue` | "🧹 Wyczyść kolejkę": every waiting request of the DJ's own party → rejected, `cleared_at`, the AI's comment kept (one `UPDATE`, `SongRequestRepository.rejectWaiting`) |
| POST | `/dj/dashboard/clear-history` | "🗑 Wyczyść historię": the DJ's own party's played and rejected requests deleted, except a skip of the last 2 hours (`SongRequestRepository.deleteHistory`); → `/dj/history-view` |
| POST | `/dj/dashboard/vibe`, `/vibe-note`, `/dj-name`, `/comment-style`, `/limits` | settings |
| POST | `/dj/dashboard/dj-links` | `instagram`, `facebook`, `tiktok`: the DJ's profiles (`SocialLinks`; 400 and nothing saved when one is not a profile on its site) |
| POST | `/dj/dashboard/tip-count` | `id`, `add` (default true; false takes one back): the DJ's tip for their own numbered song (V28) |
| POST | `/dj/dashboard/tip-link` | `tip`: the DJ's tip link (`TipLinks`; 400 and nothing saved when it is not a page on one of the tipping services; empty clears it) |
| GET | `/dj/history-view`, `/dj/history-view/fragment` | `limit` (50..300), `filter` |
| GET | `/dj/qr-print` | `layout` = poster / cards |
| GET | `/dj/summary`, `/dj/summary/csv` | `evening` ("2026-10-03"; none or not a date = the latest with requests): the evening summary, its CSV (404 with no evening) |
| POST | `/dj/push/subscribe`, `/dj/push/unsubscribe` | JSON: the browser's push subscription (204; 400 when not a push service's address or malformed keys); unsubscribe removes only the DJ's own |
| POST | `/dj/start-party`, `/dj/end-party`, `/dj/delete-account`, `/dj/logout`, `/dj/feedback` | |

---

## 13. Known Limitations & Technical Debt

### Architecture
- No user table: the DJ is the `owner_id` of the party; one party per DJ. Old parties are never cleaned up (their requests are,
  after 30 days).
- **Single instance:** the caches and the guest limits are in memory — a second instance would need them shared. The HTTP sessions
  are in the database (review 2.3); each request with a session reads it and writes its last access time (the dashboard: one request
  per shown window every 3 s).

### Security
- The CSP is report-only on Railway until switched on (`CSP_ENFORCE=true`; locally it is on). Reported so far (2026-10-06): only
  Cloudflare's Web Analytics beacon (`static.cloudflareinsights.com`, injected by Cloudflare into the HTML) — switch that off in
  Cloudflare, or allow it in the CSP, before enforcing.
- The review of 2026-09-30 (`docs/history/review-2026-09-30.md`) is done; the one point left open is 2.3 (single instance, above).

### Front end
- Polling every 3 s (ETag / 304), no WebSockets; a hidden window rests until it is shown (review 3.4).
- `<html lang>` follows the bundle that wrote the texts (`th:lang="#{html.lang}"`; `HtmlLangDeclarationTest`). The English texts are
  `messages.properties` itself: a browser in any language without a bundle (English, German…) gets them, whatever the server's own
  language (`spring.messages.fallback-to-system-locale=false`; before it, Polish on a Polish machine — `SmokeTest`).

### Testing
- **Unit tests** (`mvnw test "-Dtest=!Scan2playApplicationTests,!*IT"`, no database): 363. Pure Mockito, plus template rendering with
  the real bundles (`DashboardPageRenderTest`, `GuestPageRenderTest`, fragment tests) and `SmokeTest` (`@WebMvcTest` with the real
  security chain). **Coverage** (JaCoCo, a report, not a gate): `target/site/jacoco/index.html` after `mvnw test`; the Unit tests
  workflow writes the totals per package to its summary and keeps the report as the artifact `coverage-report`.
- **Database tests** (`mvnw verify -Pit`): 30 `*IT` on a real PostgreSQL — `PostgresIntegrationTest` creates and drops its own
  `s2p_it_*` database and starts the whole application on it: `MigrationIT`, `SongRequestRepositoryIT`, `ApplicationSetupIT`,
  `SessionStoreIT` (what the app keeps in a session survives the database and another repository; the cleanup of expired sessions),
  `SongRequestVotesIT` (votes under the lock; a vote while the DJ marks the song played; a song the DJ skipped, and put back), `FirstLoginIT` (a
  first login in eight tabs at once — one party); with a database of their own, migrated to the version before and given rows the old
  way: `TimestampMigrationIT` (V12 — the same moments; red when the old values are read as UTC), `VibeMigrationIT` (V16),
  `SpotifyRemovalMigrationIT` (V18), `YouTubeRemovalMigrationIT` (V19: the YouTube parties go with their data, a requests-only party
  stays). New SQL that locks or counts gets a test there.
- **Browser tests** (`python src/test/browser/run.py`; guide: its `README.md`): the real scripts on the real rendered dashboard
  (`DashboardPageRenderTest` writes it), the guest page and the QR print page (`GuestPageRenderTest`, `QrPrintPageTest`) in a headless
  Chrome, with a Python stand-in server that the scenarios configure; 41 scenarios. The stand-in sends the real CSP **enforced** and
  every scenario fails on a violation. They do not cover two real devices, how a page looks, and the guest's behaviour beyond the CSP.
- **CI** (GitHub Actions, every push to `dev` / `main` and every PR): `unit-tests.yml` (also checks that
  `.github/copilot-instructions.md` is `AGENTS.md`), `db-tests.yml` (`postgres:18`), `browser-tests.yml`. `gh` is not installed
  locally; the public API shows the runs, and failed tests are written as public annotations. **Dependabot**
  (`.github/dependabot.yml`): security fixes only (version updates off by `open-pull-requests-limit: 0`); it works from the default
  branch `main` and needs "Dependabot alerts" and "security updates" switched on in the repository's settings.

### AI
- No retry when Gemini is down (the request goes to the DJ unchecked; `AiHealthMonitor` writes it to the log); no check that a song
  exists.

---

## 14. (Roadmap V2.0 "Master Queue" — the server-driven YouTube queue; removed with the player on 2026-10-04)

The old migrations V2–V6 refer here. The record of the phases, their decisions and how each was verified:
`docs/history/project-context-2026-10-01.md`, Section 14. One rule from it still holds: SQL that two requests can run at once for
the same party takes a per-party `pg_advisory_xact_lock` and gets a test on a real database (today: the votes, "S2PR").
