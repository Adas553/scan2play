> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**The heading** (the panel's clean-up, 2026-10-10, https://claude.ai/artifact/9ZzSG3LjggMreBg45UNvde — the owner picked variant B):
one row with our logo, "Zakończ imprezę" and "Konto" (`dj-nav` in `components.html`; the history, "Ustawienia imprezy" and "Obsługa"
have the same — one heading for every DJ's page, 2026-10-28), the tabs **"Panel DJ-a | Historia | Ustawienia"** (on the panel the first two swap
the queue and the history in place, `tabs.js`, the lit tab is the list that shows; "Ustawienia" is the page "Ustawienia imprezy", lit on "Obsługa"
too — the old "Kolejka" led where "Panel DJ-a" did; from another page a tab opens its page at the top — `#top` on the panel's and
the history's link, so `scroll-restore.js` keeps a page's scroll only for a reload after a form or "Wstecz"), then the
button "Impreza i kod QR" (named for what it holds, not "Ustawienia" — that is the tab; the staff without a settings card: "Kod QR"); the page's h1 "Panel DJ-a" is for screen readers — the first request
of a phone is at 316 px (was 382). **The settings fold** under that button on every screen (`.s2p-settings`; folded by `app.css` alone,
`settings-toggle.js` opens them and keeps the choice for the tab in `sessionStorage`) and hold only what the DJ uses during a party
(they were seven cards, 4900 px of a phone): the card **"Impreza"** (the vibe and the AI's comments saved as soon as picked, the vibe
note and "Kto gra" under one "Zapisz") and **"Kod QR"** (small, beside the guests' link, "Kopiuj", "Drukuj plakat i karty", "📺 Ekran na TV").
The rare settings are the tab "Ustawienia" — the rows of **"Więcej"** that led to the same page were taken off the panel (the owner,
2026-10-28: the settings were in two places); near a server limit the panel says it above the queue (from 80 %: "Impreza: 250 z 300
próśb na dobę", "Jedna sieć (np. Wi-Fi lokalu): 52 z 60 próśb w 10 minut" — the network's at its limit too, it passes by itself;
`polling.js` keeps them current from `X-Guest-Limits-Use`; `guest-limit-near-follows-the-poll`). **From 1400 px wide** the unfolded
cards stand beside the queue, one under another, held under the tab bar (`.s2p-panel-body`, a grid only then; narrower they stay above
it, side by side — `settings-beside-queue-wide`, `settings-above-queue-laptop`). Each card only for whom may change it (V32: hidden,
not greyed): a co-organiser sees "Impreza" by their permissions; the rest of the staff the QR code.
**The page "Ustawienia imprezy"** (`GET /dj/settings?party=`, `settings.html`, `DjDashboardController.settingsPage`; anyone with
access to the party — a 403 for another): the heading and the tabs of the panel ("Ustawienia" lit, the panel's width; the cards 800 px),
then the cards of 6.7 in four groups (`06-7-look.md`: Impreza / Dla gości / Ty jako DJ / Dostęp, a group only when a card of it
shows), **each folded to one line**
(2026-10-25; `details.s2p-fold`): its heading and on the right its state ("🚫 0 · ⭐ 1 · link",
"Ustawione: 3", "2 na gościa · 3 min" or a server limit near in yellow, "Utworów: 1 234", "Powiadomienia włączone"; nothing set greyed), none beside "PRO" —
and "▾"; a tap unfolds it, and unfolded its state goes (the fields say it). The folded page is one screen of a phone and the list of
what can be set (no row of jumps above it); "Obsługa", "Życzenia przed imprezą" and "Pytanie do gości" are lines of the same look
with "›", leading to their pages (`#staffRow`, `#wishesRow`, `#pollsRow` — each page checks the permission itself). The card
the address names unfolds by itself (`js/folds.js`: `#libraryCard`, "Nowy link" → `#hostListsCard`, "Zainstaluj" under an empty queue → `#deviceCard`, an element
inside a card too), the others stay folded; a form saved in the background leaves its card unfolded, and the library's card brought
again after an upload keeps it (`library.js`). The cards — **"Lista gospodarzy"** (`HOST_LISTS`; the lists, the hosts' link — "Nowy link" / "Wyłącz" are sent in the background and
the link's box comes back from the settings page the server redirects to, the lists as typed, the page not moved — `cards-in-place.js`,
`data-in-place="hostLinkBox"`; without the script page loads back to the card), **"Profile i napiwki"** (the owner's), **"Limity gości"** (`LIMITS`; the three numbers and the server's use in
one line with their windows, "Limity serwera: sieć 4/60 na 10 min · impreza 12/300 na 24 h" — unfolded (a `<details>`), what a
network is: "Jedna sieć to np. Wi-Fi lokalu — wszyscy goście w nim liczą się razem."), **"To urządzenie"** (everyone's:
the notifications, "Zainstaluj aplikację", on the person's own party "Zaproszenie do obsługi? Wklej link"), and last **"Kod imprezy"**
(V49, the owner's; folded to the code): against a guest who keeps the code and spams every party — "Zmień kod imprezy" (an outline in
the danger colour) asks first, "Zmienić kod imprezy? Stare plakaty i karty przestaną działać.", and is a page load
(`POST /dj/dashboard/party-code`, `forms.js` leaves it alone) that comes back to the new code's page with this card unfolded, "Nowy kod
imprezy: …" and "Drukuj plakat i karty" (the flash `codeChanged`). `PartyCodeService.changeCode` takes the locks as "Usuń konto" (the
requests' lock, then the row), moves the row — its logo, staff, invitations and library follow (ON UPDATE CASCADE) —, its requests and
feedback by their own UPDATE, retires the old code (`party_code_retired`: no party gets it again) and evicts both codes from the caches
after the commit. The old code is then "Ten kod imprezy nie istnieje" (404) on the guest page, the TV and the print; the organiser's
other devices and the staff's open panels get a 403 at their next poll and load their panel again, the new code's (`DjSessionHelper.panel`
finds the party by the owner or the staff row). Browser scenarios `party-code-*`; `PartyCodeChangeIT`. Its forms are sent in the
background as the panel's (`settings-page.js` loads `forms.js`, `push.js`, `install.js`; the page links the manifest, so a browser
offers the app there). **On a phone**
(narrower than 768 px) the queue comes first, every waiting request is a card with big buttons — "▶ Zagrane" (filled, two thirds of the row) and "⏭ Pomiń" (outlined, amber under the pointer); "Podejrzyj" a grey outlined button as in the history (a link in the guest's line was tried
on 2026-10-10 — the owner: the button reads better); the list scrolls with the page. **The card is folded** (the review of 2026-10-10,
https://claude.ai/artifact/5Znq2bBpZQZB2rzHYkX9v1 — the owner picked "B"; `js/dashboard/cards.js`, `app.css`): the number, the song,
"⚠ Sprawdź" / "⭐ Życzenie gospodarzy" / "📋 z planu" (a song of tonight's party plan, V77), the votes, "▾" and the row "▶ Zagrane / ⏭ Pomiń" — 106 px (was 204), four in the first screen
(was two), "Zagrane" still one tap. With "⚠" the guest's words stay (they are why); "💸 2" stays once a tip is counted, the first one
is counted unfolded. A tap on the card (or "▾", `.s2p-card-toggle`, a real button with `aria-expanded`) unfolds the guest's words,
the AI's whole comment, "Podejrzyj" and 💸 / "−1"; a tap on a button, link or form of the card is that control's alone — the fold never
sends anything. Unfolded cards are remembered by the request's id and opened again after each poll (`reopenCards`; an id gone from the
list is forgotten — a request put back comes back folded). The staff's "Podgląd" (no buttons) sees one line per request. Folded A
(one line, actions unfolded: two taps for "Zagrane"), a swipe (unseen, accidental, against the poll and the phone's own back gesture)
and icon buttons ▶ ⏭ ("▶" reads as "play", and a played song has no undo) were measured and turned down. A wide screen is the table as
before (no "▾", a click on a row does nothing); its "Kopiuj" and "Podejrzyj" are quiet — no edge, muted words — until the row is under
the pointer or chosen (↑ ↓) or the button has the keyboard's focus: "▶ Zagrane" and "⏭ Pomiń" are what a row shows (2026-10-28,
`queue-row-quiet-wide`).

**The active queue** has five columns — ID, the time, the song (under it the guest's words, "AI: …" the AI's comment in one line, ⭐ / ⚠),
the votes, the buttons (2026-10-09: the vibe, "ZAAKCEPTOWANE" and the AI's energy rating said the same in every row and pushed "Pomiń" and 💸 off a
1280 px screen). With nothing waiting, the table and its search give way to the next step (`#queueEmpty`, shown by `:has`): "Kolejka
jest pusta", the QR code, "Drukuj plakat i karty", "Kopiuj link" and, for the owner, "Ustaw klimat" (`data-open-settings`:
`settings-toggle.js` opens the settings at the "Impreza" card), and "Pierwszy raz? Zobacz, jak to działa" (the guide, 2026-10-12). Each request has its place before the title on a wide screen — a CSS counter in `app.css`, so it follows the
polled list, the sort and the search by itself — and its ID in the first column ("#27", V28 — see "Numbers and tips"). "Wyczyść kolejkę" beside the heading (shown only while a request waits — `:has`) asks first (`data-confirm`) and
sends `POST /dj/dashboard/clear-queue`: the waiting requests go to the history's rejected ones, with `cleared_at` and the AI's
comment (V25) — the history says "WYCZYSZCZONE" in place of the verdict ("🧹 Wyczyszczona przez DJ-a …" in its title), amber as a skip. Ending the party does not clear the
queue (the DJ may pause it for a break or a limit).

Every dashboard window follows the party's state within one poll: the party open or closed (`X-Party-Active` — the "party closed"
banner and the "end party" button) and the guest limits (`X-Guest-Limits` — the warning `party-full` —, `X-Guest-Limits-Use`).
The same header carries `ai-down` (the review of 2026-10-21, O2): while the AI does not judge the requests (`AiHealthMonitor`,
Section 7.1) every panel but the demo's shows a red bar above the queue, "⚠ AI chwilowo nie ocenia próśb: nowe trafiają na listę z
plakietką „bez oceny AI” — przejrzyj je przed zagraniem.", gone with the next poll after the AI answers again.

**"Zagrane samo" — the DJ's program marks the queue played** (`docs/ideas.md` items 18, 19; V78; Pro — `PlanFeature.DECK`: without
Pro neither the button nor the card, no offer in the queue, `/pro` names it). One way: a program on the DJ's computer (below) reads
VirtualDJ's or Serato's play history and sends the songs; the panel only pairs it. "🎧 Z programu DJ-a" beside "+ Dodaj piosenkę"
(QUEUE, not in the demo; shown by `play-history.js` on a screen 768 px wide or more, any browser; green — `btn-outline-success` —
whenever a computer is paired: the list is asked when the page loads and when it is shown again) folds out a card framed as "Impreza"
(`card bg-dark border-secondary`): one line of what the program does — it reads in the background, the panel need not be open; with
VirtualDJ a song is marked about 45 s after it started (VirtualDJ writes it to its history after its `historyDelay`, ~45 s, + up to 3 s
of the program). **No computer paired:** three numbered steps (`.s2p-deck-steps`) — 1. "Pobierz program": buttons Windows, "Mac (M1 i
nowsze)", "Mac (Intel)" (the GitHub release `zagrane` of `deck-agent.yml`, no account needed); 2. "Rozpakuj plik zip i uruchom" with
"Pierwsze uruchomienie" folded (what SmartScreen — "System Windows ochronił ten komputer" — and Gatekeeper ask; a Windows that gives
no "Uruchom mimo to", Smart App Control, runs no unsigned program: "napisz do nas przez „Pomoc i kontakt”"); 3. "Połącz go z tą
imprezą:" and "Połącz komputer" (`POST /dj/devices/code`, QUEUE): a 6-digit code valid 10 minutes ("123 456", large, with a "Kopiuj"
of the digits — dj-nav.js's `data-copy-text`, as the party link's), gone once the program took it. **One or more paired:** their rows only ("✓ DESKTOP-1AB2 — Windows · ostatnio 21:43 · Odłącz"), the steps behind "Połącz
kolejny komputer" (and shown while a code waits). Last, small: what reaches Scan2Play (artist, title, time; nothing kept). The
party's computers: `GET /dj/devices` (name, last seen; 403 without Pro); "Odłącz"
(`POST /dj/devices/remove`, any plan; a program without Pro is taken away by its own "Odłącz ten komputer", `POST /api/device/unpair`).
The program swaps the code for its own token (`POST /api/device/pair`) and sends the songs to `POST /api/device/plays` with
`Authorization: Bearer` (JSON `{plays: [{at, artist, title}], sentAt}`, ≤ 20, a field ≤ 500 characters). The server
(`DeckHistoryService`) marks played each waiting request of that song by the library's keys (`LibraryKey`, V42): the title and an
artist in common ("✓"); a title alone, a request without an artist, a song that started more than 2 minutes before the request (5 when
the sender did not say its clock; the computer's clock — moved by the difference between `sentAt` and the server's time, so a computer
whose clock is off still matches) mark nothing; a song with no artist (a file without tags: Serato writes its name as the title) is
read as "Artist - Title". Each mark is `DjService.markSongAsPlayed` (the conditional `UPDATE`). The privacy policy (PL / EN, section
2) says what is sent and what is kept (the night's set, V83, below). `DjDeviceService`: only the token's SHA-256 in `dj_device`; at most 3 computers a party
(pairing a 4th forgets the one seen longest ago); a code used once, one at a time per party (a new one kills the earlier); 10 tries of
a code per address and 1000 wrong codes in all per 10 minutes (reaching it: one WARN in the log), 30 calls a minute per computer (in
memory); `last_seen_at` written once a minute at most. Browser scenarios `deck-reader-program-only`, `deck-connected-on-load`, `deck-computers*`;
`DeckHistoryServiceTest`, `DjDeviceIT` (PostgreSQL), `DeviceApiControllerTest`, `DeviceApiWebTest` (the real security: no login, no
CSRF, no session for `/api/device/*`; `/dj/devices` needs the login). The other programs (rekordbox, Traktor, djay — not read):
`docs/ideas.md` item 18 and `tools/spike/dj_history_spike.py`. The guide (`guide*.html`) has its own section, `#deck` ("Zagrane samo": the button, the three steps, once for
good, the speed by program, when a request marks, the window, the privacy), linked from its row of "Zagrane"; `GuidePageRenderTest`.

**"Zagrane samo" watched (V81, V82, `docs/ideas.md` item 21).** Each computer's row says whether its program sees songs
(`DeckWatchService`, the states of `GET /dj/devices` — now `{computers: [{…, lastPlayAt, program, state, warn}], silenceMinutes}`):
`OK` "✓ widzi piosenki · ostatnia 21:43 (VirtualDJ)"; `WAITING` "… czeka na pierwszą piosenkę — puść dowolny utwór na minutę"
(nothing in 12 h: the DJ's test before the party); `SILENT` (the last song older than the minutes chosen) and `OFFLINE` (no call for 3
min — the program calls once a minute). **One rule for the card and the push — "silent during the party"**, about the party's *lead*
computer (the one that sent the newest song of the last 12 h): the party open, the minutes chosen (not "nigdy"), a set played on it
(its run of songs — `dj_device.play_run_from`, V82 — was a set: 30 min of songs, or 10 min with a guest's request in it,
`DjDeviceService.setSql`, one rule for the run and the watch; a song played to test the program, or a soundcheck before the guests came,
is no set; a run ends after 30 min without a song, `RUN_GAP`, but a set's run goes on after a break of up to 4 h — a dinner without
music, a band's set —, `SET_BREAK_MAX`), its newest song older than the minutes, and guests still asking (two requests more than 8 min
after that song's start, in the last 60 min: when the program stops seeing songs the music goes on and so do the guests; the requests
of the set's last song and the "one more!" just after it are no fault). A request is a guest's: the DJ's own "+ Dodaj piosenkę"
(`added_by_dj`) counts for neither. Then the lead's row is amber (`warn`) — "brak nowych piosenek od 21:43" — and the queue's button amber
(`btn-outline-warning`, the warning in its `aria-label` and `title` too) instead of green; "program nie łączy się od 21:40" once the lead
has not called for 3 min, amber while guests ask even without a set (the program not started tonight after the test; no push for it),
the button then named "program nie łączy się". Every other row, and everything out of the party, in grey ("ostatnia piosenka 23:58",
"ostatnio 0:10"): a spare laptop tested and left aside never turns amber. "Nigdy" turns the amber and the push off, not the truth of the
line (silent after the default 20 min, in grey). The list is asked again every minute while the page is shown and a computer is
paired (an older answer dropped when a newer was asked; two asks in a row that fail say "Nie udało się sprawdzić programu — stan z 21:43" under the rows, `#deviceStale`, and the button neither green nor amber). Under the rows "Ostrzeż mnie, gdy program nie widzi piosenek: po 10 / 20
(default) / 30 min / nigdy" (`POST /dj/devices/silence`, QUEUE, Pro; `party_settings.deck_silence_minutes`, not mapped in the entity —
read and written by `DeckWatchService`; a refusal said beside it in a status region, the select back at the server's value). The look (`@Scheduled` every
minute, one query, ≤ 100 parties, the newest silences first) sends a push to the organiser's devices and those of the staff with
QUEUE ("⚠ Program DJ-a nie widzi piosenek" / "… się nie łączy" when the lead stopped calling too, "Od 47 min…" — the silence as it is;
tag `s2p-deck-<party>`, a tap opens the party's panel) — once a silence (in memory: the next after a new song and another silence, or
when the minutes are raised above the silence warned about; a restart may repeat one). The program sends each song's source (`program` of a play: "VirtualDJ",
"Serato", "Spotify", "Tidal"); the server keeps only the newest song's start (`dj_device.last_play_at`, never moved back), that name
(`last_play_program`) and the start of its run, never the song. The program's window shows "OSTATNIA PIOSENKA" (the newest song read
since its start, any song, with its program — kept on the computer only). `DeckWatchServiceTest`, `DeckWatchIT` (PostgreSQL), browser
scenarios `deck-computer-lines(-en)`, `deck-silence-choice`, `deck-silence-not-saved(-en)`, `deck-older-answer-dropped`,
`deck-list-not-loaded`, `deck-computer-row-800`.

**"🎶 Teraz: …" on the TV and the kiosk (`docs/ideas.md` item 21, "Teraz leci"; no migration).** When the program marks a guest's
request played (`DeckHistoryService.markPlayed`), the TV screen and the kiosk show above their list "🎶 Teraz: <song>" / "Now playing:"
(`#tvNowPlaying`, `#kioskNowPlaying`) — the request's name only, never its dedication (the guest's form promises "Zobaczy ją tylko DJ";
the owner, 2026-11-27) or the guest's words; only a request the AI judged, never the DJ's own "+ Dodaj piosenkę". Only the program sets
it: the DJ's "Zagrane" is often tapped minutes later, when "now" would be untrue. `NowPlayingService` keeps the song and its start in
memory (Caffeine, ≤ 10 000 parties): the TV and the kiosk already fetch their lists every 10 s, and the bar costs them a map's look-up,
no query and no new fetch; a restart forgets at most one song's bar. It shows from the song's start for 6 min (`SHOWN`) and goes once a
song that started more than 30 s later reaches the server (`SAME_SONG`: a song sent again is not the next one) — with VirtualDJ that is
~45 s into the next song; Serato 2/3 writes a song when it is loaded on a deck, so there the bar may go a little early. A request whose
song is not the newest of its call (a history read again) is over already. With the bar the TV's ten rows close up (`.tv-list-now`, a
class, not `:has()`: a TV's browser may be old) so a window of 1280 × 720 still shows them all. Not on the guest's page (it would need a
fetch of its own). The DJ may keep it off the room's screens: "Pokazuj na TV i kiosku, co teraz leci" in the program's card (V84,
`party_settings.now_playing_shown`, on by default, mapped in the entity so the TV and the kiosk read it from the cached settings — no
query of their own; `POST /dj/devices/now-playing`, QUEUE, Pro; `GET /dj/devices` says `nowPlayingShown`; scenario
`deck-now-playing-switch`). `NowPlayingServiceTest`, `DeckHistoryServiceTest`, `TvScreenWebTest`, `KioskWebTest`; browser scenarios
`tv-now-playing-*`, `kiosk-now-playing-fits-1180x820`.

**The night's set kept (V83, `docs/ideas.md` item 21, 4; Pro — `DECK`).** Every song the program sends is kept for the party
(`DeckSetService`, table `deck_play`: the song's start on the server's clock, the artist and title as written, their `LibraryKey`s, the
program), not only those that mark a request — while "Zapisuj set wieczoru" is on (`#deviceSetKept`, a switch under "Ostrzeż po",
`party_settings.deck_set_kept`, on by default — the owner, 2026-11-27; `POST /dj/devices/set`, QUEUE, Pro; `GET /dj/devices` says
`setKept`; off — asked first in the card, since it deletes the sets of every evening — deletes the party's kept set at once). A call's songs are kept in one `INSERT … SELECT FROM unnest(…)` under the party's
advisory lock, a song sent again (the same keys within 2 min) once, at most 600 a day per computer (in memory; one WARN when reached), a
song older than 12 h, or without a title, never; a failure is logged and never fails the call. Kept 30 days (the nightly purge of
`SongRequestRetentionService`, as the requests), gone with the party. Never in "Trendy próśb". Its uses: **"Już leciało o 21:30"**
(`SongEvaluationService`, `DeckSetService.playedLately`) — a guest's song among the party's last N of tonight's set, N being the DJ's
"Bez powtórek z ostatnich N" (0: repeats allowed, nothing checked; the owner, 2026-11-27: one rule the DJ knows), by its title and an
artist in common (a title alone matches nothing), only songs since the evening's first guest's request (a soundcheck before the
guests came is none; the first request checks nothing; after "Wyczyść historię" only the songs played later — V86
`party_settings.history_cleared_at`): "Ta piosenka już dziś leciała — o 21:30. Poproś o coś innego!" under an amber "JUŻ GRAŁO", not a red
"NIE" (`DjResponse.KIND_REPEAT`, the same for the unchecked "leciała przed chwilą"), nothing saved, counted as
`ALREADY_PLAYED` in the summary's "Zatrzymane, zanim zapytano AI"; checked on the guest's own words before the AI (no Gemini call) and
again on the AI's song name when it differs ("baśka wilki"); the kiosk the same. **"Co to było?"** on the guest's page
(`#playedTonight`, folded under the list, only while the party keeps its set and a song came in the last 12 h): the last 10 songs, the
newest first, with their times (`GET /p/{code}/played`, the fragment `guest-played :: playedList`), fetched by `guest-party.js` at each
unfolding, never with the page nor by a timer; the list kept 30 s in memory per party, so a room of phones asks the database once
(and the page's own check of "a song tonight" reads the same memory). **"Cały set"** in the evening summary (`EveningSummaryService.set`,
`#wholeSet`, both versions, folded, unfolded to print): every song of the evening (≤ 1000), the requests that played marked 🙋; the
morning e-mail says its count ("🎧 Cały set z programu DJ-a: 87 piosenek"). An evening with a set and no request is not in the summary's
list (the evenings come from the requests). The cost at 1000 parties at once: a song every ~3.5 min each → ~300 inserts a minute
(one statement a call); a guest's request reads one party's last N rows by `idx_deck_play_party_started` (twice at most: the words and the
AI's name; 0.1 ms on 1.8 M rows) after the evening's first guest's request, kept in memory per party once found (an hour, while it is
still tonight's; asked by `idx_song_requests_party_guest_time`, V85 — without it the planner went through every party's requests of
12 h for a busy party, 59 ms), "Co to było?" one query per party per 30 s at most, the TV nothing. `DeckSetIT` (PostgreSQL: once,
two computers and a call with "off" taking turns under the lock, the starts exact in the hour the clocks go back, off, 600 a day, "Już leciało", the evening, the purge, the plans), `DeviceApiControllerTest`,
`SongEvaluationServiceTest`, `GuestPageRenderTest`, `SummaryPageTest`, `AutomaticEmailsTest`, `SongRequestRetentionServiceTest`;
browser scenarios `deck-set-kept-switch`, `deck-set-kept-not-saved`, `guest-played-*`.

**Parked: the panel reading the history folder itself** (variant E of `docs/ideas.md` item 18 — "Wskaż folder historii" in Chrome and
Edge, `showDirectoryPicker`, the tab kept open, `POST /dj/dashboard/plays`). Removed 2026-11-21 after the program worked on the owner's
Windows 11: two ways in one card were too much for a DJ to choose from. Back only if the program fails. Its code: commit `6c25e0a`
(the merge of #297 on `main`) — `js/dashboard/play-history.js`, `DjSongController.deckPlays`, the browser scenarios `deck-reader-*`.

**The program, `deck-agent/`** — a Maven project of its own beside the app (not a module, never deployed; Java 21, Swing, `sqlite-jdbc`):
every 3 s it reads VirtualDJ's newest `.m3u` of all its folders (the registry's `HomeFolder`, `%LOCALAPPDATA%\VirtualDJ\History`, the
Documents', OneDrive's `Documents` — on macOS `~/Library/Application Support/VirtualDJ` and the Documents'; an old install's folder
does not hide tonight's file), Serato 2/3's newest `.session` (`Music/_Serato_/History/Sessions`, on Windows also under OneDrive)
and Serato 4 / 5's `master.sqlite` (read-only; `%LOCALAPPDATA%\Serato\Library` — Serato DJ Lite 5.1 on the owner's Windows —
or `%APPDATA%\Serato\Library`, the one written last; `~/Library/Application Support/Serato/Library`) when they changed; the query made
from the columns the tables have (the newest session's songs, `played = 1` where the column is). Serato 5.1 writes a row when a song
is *loaded* on a deck, already `played = 1`: there a row counts only once its song's library count (`asset.dj_play_count`, raised a few
seconds after Play, never by a load) has risen since the session was first read (the library's counts read once a session) — each
rise given to the song's newest row not given one (the load just played; an earlier load is a preview, its time "before the request"),
remembered for the session (a row that falls out of the 2000 read keeps its play); a count that fell (reset by the DJ) counts on from
there; a song only loaded marks nothing, a song played marks its request within seconds; songs played before the program's first read
of the session are not sent (no "10 minutes before" for Serato 5); a new session (Serato restarted, its songs renumbered) takes the counts again; a
history there that cannot be read is said in the window ("Serato (master.sqlite — nie da się odczytać)"); and, in the background after the window is up (the usual places are read at once), a short
look through the user's folders (Music, Documents, OneDrive, AppData, the profile last; 4 levels, at most 30 000 folders in all, no
links followed, a root not looked through twice, hidden folders, `node_modules`, `Packages`, `Caches`, `Containers` skipped) for a "Serato" with `Library/master.sqlite`, a "_Serato_" with `History/Sessions`, a "VirtualDJ" with `History` in places
no reader names; sends each new song once (≤ 20 a call, the set before the program started minus 10 minutes not), calls once a minute with
nothing (alive); an artist or title cut at 500 characters (the server refuses a whole call with a longer one) and at once after a new pairing (a party without Pro said at once); a 401 = "Odłącz" in the panel: the token
forgotten, the window asks for a new code; "Odłącz ten komputer" in the window tells the server (`/api/device/unpair`: the computer
leaves the panel's list) and forgets the token here even offline. On Windows it asks PowerShell once at the start where the Music and
Documents are and where VirtualDJ's `HomeFolder` points (UTF-8: a user name with a Polish letter whole; OneDrive, Polish folder names)
— extra folders beside the usual ones, nothing when PowerShell is missing. On macOS the Dock icon shows the window closed into the menu bar. The window (`Window`, FlatLaf's dark theme in the app's colours, the Scan2Play mark drawn from `logo-small.svg` — the window's,
the taskbar's and the tray's icon): unpaired, "Połącz ten komputer z imprezą", the code's large box and "Połącz" (an empty code:
"Wpisz kod z panelu."), the server behind "Zmień serwer" (`https://www.scan2play.com.pl`; `dev.` for a try); paired, a status line
with a dot ("Działa — połączono z imprezą" green; amber/red: no server, too many calls, no Pro, nothing to read — "Połączono — nic do
odczytu", short: the status line sets the window's width for good) and "sprawdzone
23:53:52"; "CZYTA" — each history read ("✓ VirtualDJ (2026-11-21.m3u)", red "nie da się odczytać", amber "!" for Spotify heard not — below), or
"Brak historii VirtualDJ i Serato, Spotify i Tidal zamknięte…" (on a Mac "Spotify zamknięty": only this system's apps named, also in
"Oznaczone"'s "ze Spotify i Tidala po 30 s" — `NowPlaying.appsFor`) with the button "Wskaż folder historii…" (a quiet link "VirtualDJ albo Serato nie
na liście? Wskaż ich folder…" when something is read — naming the two, so a rekordbox DJ with Spotify open is not invited to pick a
folder that holds nothing read; its tooltip: a history in an unusual place, which folder): a folder the DJ picks is kept in the
settings and read beside the usual places (`Platform.sourcesIn`: VirtualDJ's `.m3u`, Serato's `.session`, `master.sqlite`, in it or
right under it; "Nie czytaj go" forgets it); "OZNACZONE OD URUCHOMIENIA · 12" — the last 5 songs marked with their times, newest
first, never a night's whole list (empty: when a song marks itself; a song, a folder or a line of "CZYTA" — Spotify's song — longer
than 60 characters cut with "…", whole in its tooltip, so the window never grows past the screen; the window never narrows when the status line changes); "Odłącz ten komputer"; a tray icon ("Pokaż", "Zakończ"). One
copy per user: a second start finds `~/.scan2play/zagrane.lock` locked (`SingleInstance`, `FileChannel.tryLock`; the system drops the
lock when the program ends) and says "Program już działa…" (a window, or a line and exit 1 in the console). `~/.scan2play/zagrane.properties` keeps the server, the token and the folder picked. Without a screen: `--pair <code> [--server …]`, then
`--console`. Tests: `sh mvnw -f deck-agent/pom.xml -B -ntp verify` (`HistoryFormatsTest`, `AgentTest`, `SingleInstanceTest`, `WindowTest`, `NowPlayingTest`, `WindowsMediaTest`). Packages:
`.github/workflows/deck-agent.yml`, by hand ("Run workflow"): a jpackage app folder with its own Java for Windows and macOS, zipped,
**not signed** (SmartScreen's "Uruchom mimo to"; macOS's "Open Anyway" once; the Mac bundle only signed ad hoc, which Apple's chips need
to run it at all); the three builds have a read-only token, the job `release` (from `main` only) uploads all three together once each
was built, and a "Run workflow" is never cancelled by a later push.

**Spotify and TIDAL in the program** (`NowPlaying`, `WindowsMedia`, `docs/ideas.md` item 20; tried on the owner's Windows — Spotify, and
TIDAL read by the media sessions; a Mac not yet): the music apps write no history and their Web APIs are closed to an app
like this one, so the song playing now is asked of the desktop app at each look, one source per app ("Spotify", "Tidal" in the window —
Polish writes it so, "ze Spotify i Tidala"; the English pages keep "TIDAL").
On Windows by Windows' own media sessions (the system's media controls: `GlobalSystemMediaTransportControlsSessionManager`, WinRT,
Windows 10 1809 and later) — each app's session, found by its id containing "spotify" or "tidal" (the Store's Spotify too), with its
state and the artist and title apart: playing, its song; paused, nothing; an ad ("Advertisement", the artist "Spotify") nothing.
Spotify with no session (media sessions not reachable, a Spotify that reports none) by the title of its window ("Artist - Title";
"Spotify Premium" / "Spotify Free" paused). One Windows PowerShell 5.1 (`-EncodedCommand`) for both apps, started when a `Spotify.exe`
or `TIDAL.exe` runs (checked every 10 s by `ProcessHandle`; a PowerShell that dies within 30 s is started again after 1, 2, 4 … at most
10 minutes), writing every 2 s the apps running, the apps' sessions (only theirs are asked for a song: a browser's video, a call stay
unread — the privacy policy) and Spotify's window title in UTF-8 and ending by itself with the apps or the program (it checks the
program's PID: ~2 s after the program is killed); an answer older than 8 s is none once the PowerShell has ended, older than 30 s
while it lives (a slow round is not the app closed nor a pause of the song counted). The package job on Windows runs the real script
in Windows PowerShell 5.1 first (`WindowsMediaTest.theScript_runsInWindowsPowerShell`, skipped on Linux). On macOS only
Spotify (TIDAL has no AppleScript and macOS' own "Now Playing" is closed to other programs — TIDAL inside VirtualDJ or Serato is in
their history), by AppleScript (`osascript` at each look while `Spotify.app` runs,
looked for every 10 s; macOS asks the DJ once whether the program may control Spotify — the bundle's `NSAppleEventsUsageDescription`,
added by the workflow; the ad-hoc signature changes with each build, so macOS may ask again after an update — to see on a Mac; an
ad, `spotify:ad`, none). A song heard 30 s (`PLAYED_AFTER`; a pause stops the count, another song starts it anew, a gap over 10 s
between looks adds 10 s) is a played one from the time it was first heard. A pause over 2 minutes (`LONG_PAUSE`, the server's slack
for a request after a song's start) makes the same song a new one from when it plays again — a song paused for another program's set
and played on keeps no old start, and played again later is a second play; another song clicked for a few seconds and back is one
play. It goes as any history's song (the server unchanged). The window's "CZYTA": "Spotify (gra: …)" while a song plays, "Spotify
(nic nie gra)" paused; amber "!" when Spotify may play but is not heard, what to do in the tooltip — "okno schowane — nie widać, co
gra" (Windows: no media session and no window title — Spotify closed to the tray; minimised is fine) and "brak zgody macOS — nie widać, co gra" (`osascript`'s
-1743, the DJ's "Nie pozwalaj"); the panel's card ("Pierwsze uruchomienie") and the guide say only the Mac's question: on Windows
the apps just have to play (the media sessions do not need the window; Spotify by default quits when its window is closed). Named for the DJs only where the
program is explained — the panel's card, the guide's `#deck`, the privacy policy (PL / EN; "bez logowania do Spotify") — never in
"Nowości", the news e-mail or the landing pages: Spotify's terms forbid playing it in public, so it is a quiet possibility, not a
selling point (`docs/ideas.md` item 20); TIDAL named beside it the same way. A window's title is the app's own, unofficial way and may
change: on Windows it is only the fallback of the media sessions.
