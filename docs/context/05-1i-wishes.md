> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**"Życzenia przed imprezą"** (V68, Pro, the permission WISHES; for any event, not only weddings): before an event the guests open its own link — on the invitation, in an
e-mail from HR, in a chat — and write the songs they want to hear; the DJ sees them counted, marked by the library, and moves the
ones to play to the hosts' "⭐ Koniecznie zagrać" (V29). Nothing of the guest is kept: the song as typed and how many wrote it.

**An event** (`wish_event`, `WishService.Event`): a party has one code for every event, so the wishes belong to an event of their own —
a name the guests read (≤ 80, `WishService.tidyName`) and the party's date (today … `MAX_DAYS_AHEAD` = 2 × 366 days ahead), its guests'
link `/w/{token}` (128 bits, as the hosts' — "Nowy link" makes the old one dead, the wishes stay), `closed` ("Zakończ zbieranie" /
"Wznów zbieranie", the latter Pro), `wish_count`, `guest_theme` (V69: the look of its guests' page — its own, picked when it is
made, Klasyczny first, and changed on its card, Pro; never the party's "Motyw dla gości", which a DJ changes during a party). A party has at most `MAX_OPEN_EVENTS` = 5 open at once (not closed, the date today or
later; counted under the party row's lock, by "Utwórz" and by "Wznów zbieranie" alike — `WishService.reopen`) and keeps at most
`MAX_EVENTS` = 20, open, closed or past — every one on its page, where it can be closed or deleted. An event takes wishes while it is open (its day ends at midnight Polish time) and below
`MAX_WISHES` = 2 000 — raised in the `UPDATE … WHERE … wish_count < ? RETURNING` that takes the wish, so guests writing at once never
pass it (`WishServiceIT`). Deleted `KEPT_DAYS_AFTER` = 7 days after its date, or two years after it was made (`WishService.purge`, daily
04:50 Polish time), with the party, or by "Usuń wydarzenie" (any plan); its code follows "Zmień kod imprezy" (ON UPDATE CASCADE).

**A wish** (`guest_wish`): one row per song of the event — `wish_key` "title|artists" by `LibraryKey` when it was written "Artist -
Title" (the same song typed two ways, "Wilki – Baśka" and "wilki - baska (Radio Edit)", is one row), else `?` and its words
(`SongNames.comparable`): such a line is listed apart, "Do sprawdzenia" — a title alone or a line of the lyrics, the DJ reads it;
`song_name` as the first guest wrote it (≤ 150, `SongNames.tidy`), `wishes` how many wrote it (`INSERT … ON CONFLICT DO UPDATE`).

**The guests' page** (`WishController`, `wish.html`; public, `noindex`, `no-referrer`; an unknown token, or a party without Pro, a 404):
the DJ's logo or ours, "🎧 Gra: …", the event's name and day ("14 czerwca 2027", `WishController.dateText`), "Jaką piosenkę chcesz
usłyszeć na imprezie?" with the song suggestions of the party page (iTunes, `song-autocomplete.js`; no "✓ DJ ma"), "Wyślij życzenie",
and after a wish "✓ Zapisane: …" and how many are left. No list of the others' wishes: they are not checked by the AI, the DJ alone
reads them (the hosts see what the DJ moves to "⭐"). Limits: `WishController.PER_GUEST` = 5 a session, `GuestRequestLimiter
.WISHES_PER_ADDRESS` = 30 an address and an event in the address window (10 min; an office's Wi-Fi is one address), 2 000 an event;
an empty line goes back unsent. A form left open past the session comes back to the page with "Strona była otwarta za długo…"
(`GuestFormExpiredHandler`). Closed, past or full: no field, a farewell. The log has the event's id, never the song.

**The DJ's page** (`DjWishesController`, `wishes.html`, `/dj/wishes?party=&event=`; the row "Życzenia przed imprezą" on "Ustawienia
imprezy" leads to it, its state the events collecting now; without Pro the offer, and an event made before can still be deleted):
"Nowe wydarzenie" (name, date, "Utwórz link dla gości"; "Otwarte: 2 z 5"; at 20 kept no form, "usuń któreś"), "Twoje wydarzenia" ("Zobacz życzenia" goes to the event it opens, `#wishEvent` — drawn under the whole list, off a phone's screen; the opened one's button "Zwiń życzenia", as "Zwiń" in its heading — the page without it; the ones ahead first, the nearest first,
then the past ones still kept; each its date, "życzeń: N" and "zbiera" / "zakończone" / "po imprezie"), and the event opened: its link
to copy and open with "Nowy link" (asks first) beside it, "Drukuj zaproszenia", "Zakończ zbieranie", the look of its guests' page on a
line of its own ("Wygląd strony dla gości", the select and "Zmień wygląd"), "życzeń: N · piosenek: M · nie masz w
bibliotece: K", the songs the most wished first (at most `LIST_MAX` = 500; the CSV has every one; the first `DjWishesController.SHOWN` = 10
shown, "Pokaż więcej (N)" 10 more each time, "Pokaż mniej" back to 10 — `js/wishes-list.js`, how many shown remembered across a page
load, each list by its name; the page grows, no box scrolls on its own (a scroll in a scroll on a phone, and a "⭐" loads the page again); the same for
"Do sprawdzenia") — each numbered, its votes (how many wrote it) in a badge as the queue's, warm from two, in a column under "Nr · Głosy ·
Piosenka", the library's mark and
tempo as the queue shows them (Pro, a library uploaded), "⭐ Na liście" when the hosts' wish list names it already (`SongList.matches`:
"Akcent" names every song of Akcent), else "⭐ Do „Koniecznie zagrać”" — and "⭐ Dodaj 10 najczęstszych" (asks first, naming the songs it adds — the guests wrote them and the list lets them
past the AI —, and sends those songs, not "the ten" counted again at the click; gone when the list has them all), "Do sprawdzenia", "Pobierz
CSV" (`Piosenka;Ile osób;W bibliotece`), "Usuń wydarzenie" (asks first). "⭐" adds only the event's named songs, never another text, to
`party_settings.host_wanted` as the hosts' list keeps it (`SongList.tidy`, 100 lines): what did not fit is said ("Nie zmieściło się:
N"). The wish list is one for every event of the party: the page says to clear it before the next one. Plain forms, a page load each,
the page back where the DJ was (`scroll-restore.js`, no anchor: a button never makes it jump; remembered on `pagehide` too, which is
all Safari on an iPhone says), what was done said in a bar at the bottom of the screen (`.s2p-done-toast`, ✕ closes it, a success goes
by itself after 4 s — `data-toast-hide`, `dj-nav.js` —, a refusal stays). "← Ustawienia imprezy" goes back to the settings as they were
left: the scroll (`scroll-restore.js`) and the cards unfolded then (`folds.js`, the tab's session storage).

**The invitation** (`/dj/qr-print?party=&wish=&layout=cards`, `DjDashboardController.qrPrint`): the QR print of the event's link —
the cards by default (eight on a sheet, the size of an invitation's insert), the poster and the tent too; its own heading "Jaką
piosenkę chcesz usłyszeć?" / "Which song do you want to hear?", the event's name and date in the line (≤ 60 as the print's line: a long name cut, `DjDashboardController.invitationLine`), the look of the occasion
"Zaproszenie" (Elegancki, the script; the first of "Wzór na okazję", offered only here), the address `…/w/{token}` printed, no party
code, no tables. The editor shows every change and saves none (`data-preview-only`, `qr-print.js`: no "Zapisz", no language, no "not
saved" note, no question on leaving; scenario `qr-print-invitation-saves-nothing`) — the party's own print stays as the DJ saved it;
the pictures for social media work as on the party's print. Its line "Strona gościa: …" names the event's look and "Zmień" leads to
the event (`QrPrintPageTest`), never the party's "Motyw dla gości", which acts on the party's page tonight.

**"Plan imprezy"** (V77, Pro, the permission WISHES; `docs/ideas.md` item 17): the plan of one event's evening, shared by the DJ and the
hosts — its moments in their order (`event_plan_item`: a name ≤ 60, a time or none, a song ≤ 150, a note ≤ 300; at most
`EventPlanService.MAX_MOMENTS` = 30) and the event's own "🚫 Nie grać" / "⭐ Koniecznie zagrać" (`wish_event.plan_blocked` /
`plan_wanted`, tidied as the party's, `SongList`). The DJ opens it from the event ("📋 Plan imprezy", `/dj/wishes/{id}/plan`,
`DjEventPlanController`, `event-plan.html`); while it has no moments both sides may start from a template (wesele, osiemnastka,
urodziny, impreza firmowa — the names only, `eventplan.template.*` in the page's language). **The hosts' link** of the event
(`wish_event.host_token`, 128 bits; "Utwórz link", "Nowy link" kills the old one, "Wyłącz link"; it works while the event is kept and
the party has Pro): `/h/e/{token}` (`HostController`, `host-plan.html`) — the same form, no account. Its forms carry no CSRF token
(`th:attr` for the action, not `th:action`) and `SecurityConfig` asks none of `/h/e/*`: the link's secret is what lets it write, a plan
is written slowly (a form open past the session must not be refused), and a page that makes no session cannot make one too late
(`EventPlanHostWebTest`). A save is the whole plan, under the event row's lock (two at once leave one plan, `EventPlanIT`); last save
wins, the page says who saved last and when ("Ostatni zapis: gospodarze, …"). The form's rows are the moments and three spare empty
ones; `plan-form.js` adds, moves and removes rows (the order on the page is the order saved; scenarios `event-plan-*`); an emptied row is not saved, a row with a
song or a note and no name is named "Moment"; the columns are read with `getParameterValues` (a `List<String>` of one value is split at
its commas by Spring). **On the night:** the event's lists are not the party's — two weddings planned at once must not overwrite each
other, and the party's lists are what the AI and the queue use tonight —; "Użyj list na imprezie" (HOST_LISTS too) puts them over
the party's (asks first). "🖨 Drukuj plan" (`/dj/wishes/{id}/plan/print`, `event-plan-print.html`): one A4, black on white, the times
big. Without Pro the DJ's page shows the plan read only (a disabled fieldset) and the hosts' link is a 404. Nothing of the plan goes
to the guests, the TV or the AI; it is deleted with the event (7 days after its date) — the privacy policy says so.

**"Plan wieczoru" in the panel** (variant B, Pro): on the evening of an event with moments (`EventPlanService.tonight`: the event dated
`EveningSummaryService.eveningOf(now)` — until 6:00 the night is the day before's —, the first made if two share the day) a card above
the queue (`dashboard.html` `#planCard`, `js/dashboard/plan-card.js`): the event's name, "Cały plan →", the next moment — the first
not ticked off — with its time and "za 12 min" / "za 1 h 05 min" / "teraz" by this device's clock (every 30 s; a time before 6:00 is
on the night after the evening's day), the whole evening folded under "Cały wieczór (N)", a ✓ for each moment. The ticks are this
device's (localStorage `s2p-plan-done-<event>`): nothing is sent. A request for a song of tonight's plan is marked "📋 z planu" in the
queue (`EventPlanService.tonightSongs`, matched as the hosts' lists, `SongList`); the poll's ETag carries the songs' hash, so a plan
saved during the party marks the queue as it was (`DjDashboardControllerQueueCacheTest`). Scenario `plan-card-next-and-ticks`.
