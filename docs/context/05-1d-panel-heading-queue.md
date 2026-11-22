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
2) says what is sent and that nothing is kept. `DjDeviceService`: only the token's SHA-256 in `dj_device`; at most 3 computers a party
(pairing a 4th forgets the one seen longest ago); a code used once, one at a time per party (a new one kills the earlier); 10 tries of
a code per address and 1000 wrong codes in all per 10 minutes (reaching it: one WARN in the log), 30 calls a minute per computer (in
memory); `last_seen_at` written once a minute at most. Browser scenarios `deck-reader-program-only`, `deck-connected-on-load`, `deck-computers*`;
`DeckHistoryServiceTest`, `DjDeviceIT` (PostgreSQL), `DeviceApiControllerTest`, `DeviceApiWebTest` (the real security: no login, no
CSRF, no session for `/api/device/*`; `/dj/devices` needs the login). The other programs (rekordbox, Traktor, djay — not read):
`docs/ideas.md` item 18 and `tools/spike/dj_history_spike.py`.

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
with a dot ("Działa — połączono z imprezą" green; amber/red: no server, too many calls, no Pro, no history seen) and "sprawdzone
23:53:52"; "CZYTA" — each history read ("✓ VirtualDJ (2026-11-21.m3u)", red "nie da się odczytać"), or "Brak historii VirtualDJ i Serato…" with the
button "Wskaż folder historii…" (a quiet link "Inny folder historii…" when something is read): a folder the DJ picks is kept in the
settings and read beside the usual places (`Platform.sourcesIn`: VirtualDJ's `.m3u`, Serato's `.session`, `master.sqlite`, in it or
right under it; "Nie czytaj go" forgets it); "OZNACZONE OD URUCHOMIENIA · 12" — the last 5 songs marked with their times, newest
first, never a night's whole list (empty: when a song marks itself; a song or a folder longer than 60 characters cut with "…", whole
in its tooltip, so the window never grows past the screen; the window never narrows when the status line changes); "Odłącz ten komputer"; a tray icon ("Pokaż", "Zakończ"). One
copy per user: a second start finds `~/.scan2play/zagrane.lock` locked (`SingleInstance`, `FileChannel.tryLock`; the system drops the
lock when the program ends) and says "Program już działa…" (a window, or a line and exit 1 in the console). `~/.scan2play/zagrane.properties` keeps the server, the token and the folder picked. Without a screen: `--pair <code> [--server …]`, then
`--console`. Tests: `sh mvnw -f deck-agent/pom.xml -B -ntp verify` (`HistoryFormatsTest`, `AgentTest`, `SingleInstanceTest`, `WindowTest`). Packages:
`.github/workflows/deck-agent.yml`, by hand ("Run workflow"): a jpackage app folder with its own Java for Windows and macOS, zipped,
**not signed** (SmartScreen's "Uruchom mimo to"; macOS's "Open Anyway" once; the Mac bundle only signed ad hoc, which Apple's chips need
to run it at all); the three builds have a read-only token, the job `release` (from `main` only) uploads all three together once each
was built, and a "Run workflow" is never cancelled by a later push.
