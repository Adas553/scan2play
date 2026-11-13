> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.2 – 5.5. Keep it to what is true now.

### 5.2 Guest Flow

`/p/{partyCode}` (no login) → our logo, "🎧 Gra: …" and the vibe as one quiet line ("Klimat imprezy: …"), then the form — the question
"Co chcesz usłyszeć?" is the label of its one field for a song — "Wpisz wykonawcę i tytuł." (2026-10-16, the owner: no promise of a song found by a line
of its lyrics — the AI still tries, the field takes any text; song suggestions from
iTunes, asked by the browser; with the DJ's library (V58, Pro, "Podpowiedzi gości" on — the default) the songs of them the DJ has
come first, "✓ DJ ma": `GET /p/{partyCode}/library/has?song=…` for the suggestions shown, at most 10, "✓" only — never "?", never the
library listed —, at most 120 asks a minute of an address and a party (`LibraryHintService.ASKS_A_MINUTE`, a 429 past it), waited for at most 600 ms, else the list as iTunes gave it; nothing is refused by it, `LibraryHintService`) →
`POST /p/{partyCode}/request` (`songName`; an async `Callable`) → the result page (decision, the AI's
comment, the votes). A request the AI reads as a mood is not saved: the guest is back at the form with the text and
`guest.error.song_only` (the DJ sets the mood) — with the dedication too — and, while the DJ takes dedications, a second line
under it: "Chcesz coś przekazać DJ-owi? Wpisz piosenkę, a swoje słowa dopisz w „💌 Dodaj dedykację”."
(`guest.error.song_only.hint`, `ERROR_HINT`; the kiosk too): a wish reaches the DJ as a dedication, checked by the AI — there is no
message to the DJ of its own (`docs/ideas.md` item 11). Under the field "💌 Dodaj dedykację" (a `details`, no
script, V38): an optional line of ≤ 100 characters only the DJ sees; the result page says "💌 Dedykacja trafiła do DJ-a." or that
the AI kept it out (`DjResponse.dedicationOk`); the DJ's queue shows the newest 3 under the song ("💌 „…”", "niesprawdzona", "+N
więcej") — on a phone's folded card only "💌 3" beside the votes (the owner's screenshot, 2026-10-17: three filled half the
screen), a tap shows them, each on a line of its own. An empty request (only spaces) goes back at once with `guest.error.empty` — no
limit used, no AI asked. Under the form: "Twoja prośba „…” czeka u DJ-a" — or, with several, "Czekają u DJ-a Twoje prośby: 3" — and
**one list**, "Prośby gości" — under its heading, above the songs, "Daj 👍 piosenkom, które też chcesz usłyszeć — DJ widzi, czego
goście chcą najbardziej." (`#voteHint`, only while a song not the guest's own waits, `GuestQueue.votable`; under the list a phone
showed it below the screen, scenario `guest-vote-hint-above-the-list`): every waiting request once, the most votes first and the newest first among equal votes
(`GuestQueueService.byVotes`), unnumbered — the DJ picks the order —, the first 5 shown and the rest folded under "Pokaż pozostałe
prośby (N)" (a `<details>`; **its rows are not in the list's fragment** — `GET /p/{partyCode}/queue/more`, the fragment `moreList`,
fetched only when it is unfolded and again when the list is fetched while it is unfolded: with 300 waiting, every guest's refresh
carrying them all was ~200 KB), with **"Szukaj w prośbach"** — the list filtered as it is typed, by every word, no accents needed
("baska" finds "Baśka"), "Nie ma takiej prośby…" when nothing matches, cleared with folding; on the right of every song ONE pill of one size with its votes (the owner: badges of different sizes were
untidy) — a "👍 N" button (Section 4, "A guest's 👍"), outlined, yellow once given, or, on the guest's own request, a green one that
is not a button (it is their vote already) with "Twoja" beside the song's name; filled pills get a thin dark edge (a yellow 👍 on
yellow vanished on Windows). The guest's own songs are marked on the party page only (not on the result page, which is about its
one request; the guest's requests are remembered in the session). The DJ's profiles and "💸 Napiwek dla DJ-a" come **under the list**
(`#djSupport`; the design review, 2026-10-09: above the form they pushed the field to the lower half of a phone's screen). The result
page has the logo and "🎧 Gra: …" at the top (`djName`), the verdict sized to the screen (`.s2p-verdict`: "PRZEKAZANE" broke into
"PRZEKAZAN / E") and "Zaproponuj kolejną" as its one main button; under it, for a request that reached the DJ, the DJ's profiles
under "Zarezerwuj tego DJ-a na swoją imprezę" (`components :: djBooking`; also on the "party ended" page, under "↻ Sprawdź ponownie"),
with the DJ's e-mail and phone for bookings above them when given (V67, Section 4.1 `bookingEmail`).
Until 2026-10-08 there were "🔥 Najwięcej głosów" and "Ostatnio wysłane" as well — one song showed up to three times (the owner:
too much). Fetched again when the guest comes back to the page and on "↻ Odśwież", no timer; the keyboard's focus stays on its
place in the new list ("↻ Odśwież", the search, the same song's 👍 — in the unfolded rest once it is in). A 👍 or a "↻ Odśwież" that does not go
through (no network, the session ran out) shows the floating note "Nie udało się. Sprawdź połączenie albo odśwież stronę."
(`guest.queue.failed`, `guest-party.js`); the button works again. In the installed app (the dashboard on
the Home Screen) the guest page, the result page and the "party ended" page have "← Twój panel DJ-a" (`components.html`,
`back-to-dashboard`; shown by `@media (display-mode: standalone)` only): the app takes every address of the site (`scope: "/"`), so
a DJ testing their QR code landed on the guest page in it with no address bar and no "back". Only a DJ can have the app (the
manifest is on the dashboard alone); the link leads to whoever is logged in. `scope: "/dj/"` was not chosen: the Google login
passes through addresses outside it, and an iPhone's Home Screen app opens those apart, with its own cookies (an iPhone tried by the owner 2026-10-09: it works). An ended party shows
"DJ nie przyjmuje teraz próśb" with "↻ Sprawdź ponownie" (the party's link) — the landing page is for DJs. The party page, the result
and the "party ended" page wear the theme the DJ picked ("Motyw dla gości", V59, 5.1): the same page in the theme's colours, an
ornament above the card, the DJ's name in the theme's face; nothing else changes.

**What reaches the AI:** the guest's text as one line ≤ 150 characters, `"` made `'` (`SongEvaluationService.forPrompt`); the style
decided by the server (`GuestController.styleOf`): the DJ's vibe when set, else `ANY` — the guests pick no vibe.

**Limits** — each counted **before** the AI is asked:
1. the guest's own: up to the DJ's `requestLimit` requests, then a wait of `cooldownMinutes` **from the last of them**, after which the
   whole limit is back; counted by the session's id in memory (`GuestSessionService.tryAcquire`, one atomic `compute` — not in the
   session: with the sessions in the database every request works on its own copy, so parallel requests could not see each other's
   count there) — `guest.error.rate_limit`. A request that came to nothing gives its place back (`giveBack`: a mood sent back to
   the form, a rejected song — the owner, 2026-10-04 —, the guest's own waiting song asked for again). The guest reads the wait of this limit and of the next one as
   `GuestController.waitText` says it: whole minutes rounded up from a minute on ("3 min"), seconds below it ("45 s");
2. client IP + party: `guest.limit.per-ip-party` (60) per `guest.limit.per-ip-window-minutes` (10) — loose, a venue's Wi-Fi is one
   address — `guest.error.too_many_requests`;
3. party: `guest.limit.per-party-daily` (300) per 24 h — `guest.error.party_daily_limit`. Once spent, a request is refused before
   2 is counted: no address window is kept for it (an IPv6 flood, a new address each request, would fill the party's map).

A request that 2 or 3 refused gives the guest's own place back too (`giveBack`; review 2026-10-14 W2: the guest was then held by their
own cooldown for requests never sent). One guest with a script can spend 3 for the whole party (60 per 10 min from one address reach
300 in under an hour; without the cookie each request is a new session, so 1 does not hold them): the warning "Limit … wyczerpany"
above the queue has **"Odblokuj prośby gości"** for whom may set the limits (the organiser, the staff with `LIMITS`; not a plan's
feature) — `POST /dj/dashboard/guest-limit-reset`, `GuestRequestLimiter.resetParty`: the party's 24 h start again, the addresses keep
their windows (W4). A stricter limit per address was not added: a venue's Wi-Fi is one address for all its guests; a scripted flood is
for the rate-limit rule in Cloudflare for `/p/*` (the owner's setting, in place since 2026-10-16).

2 and 3 are `GuestRequestLimiter` (Caffeine, in memory; the address windows by party, so a dashboard's poll reads only its own
party's and a flood of made-up addresses at one party cannot push another's out — review 2026-10-14); 0 switches a limit off. The guests' 👍 have their own per-network limit
there (`guest.limit.votes-per-ip-party`, 300 per the same window, `tryAcquireVote`): no AI call, so a vote never uses up a request. The address is `getRemoteAddr()` or the header named
by `guest.client-ip-header` (`CF-Connecting-IP` — set on Railway). The DJ sees the use of 2 and 3 on "Ustawienia imprezy"
in one line under the limits form (badges: grey, yellow from 80 %, red; what they are unfolded); the panel says it only near a limit —
the row "Limity gości" in yellow from 80 % ("Impreza 250/300", "Sieć 26/30"), above the queue "Impreza: 250 z 300 próśb na dobę" from
80 % of the party's limit and "⚠ Limit 300 próśb na dobę wyczerpany — goście nie mogą teraz wysyłać." while it stops guest songs (both
follow every poll's headers).

**The Free plan's AI checks** (`FreeAiLimit`, the owner 2026-11-05: 150, soft): `guest.limit.free-ai-per-party` (env
`GUEST_LIMIT_FREE_AI_PER_PARTY`, 150; 0 = off) requests a party an evening (`EveningSummaryService.eveningOf`, 6:00 to 6:00 Polish time)
go to Gemini — only while the plans are enforced and for an organiser without Pro (Pro's bound: the 300 a day above). Counted in
`SongEvaluationService` just before the call — moods and a song asked again too (they cost a call), a call that failed given back (`giveBack`: the AI judged nothing); not 👍, not the refusals before the AI
(the limits, the hosts' list by the guest's words), not the demo; the kiosk as every guest. Past it: no call, the request reaches the DJ
unchecked — `KIND_UNCHECKED`, the queue's "bez oceny AI", the path of "the AI is down" but no outage (`AiHealthMonitor` untouched) —, except a song of the recently played
list named by its whole name or its title (`SongEvaluationService.playedLately`, on both unchecked paths): refused by the code,
"Ta piosenka leciała przed chwilą — wybierz inną." (`guest.played_lately`, reason DUPLICATE). A mood or an insult past the limit
reaches the DJ as a request "bez oceny AI" (the DJ alone sees it, as while the AI is down); the
guest reads "Twoja prośba trafiła do DJ-a, który sam ją oceni." (`ai.limit.to_dj`), nothing of a limit. The panel: from 80 % the line
"120 z 150 próśb ocenionych dziś przez AI. Kolejne przyjdą bez oceny." (`data-limit-near="ai"`), at it the bar "⚠ Oceny AI na ten wieczór
wykorzystane …" (`data-guest-limit="ai-limit"`), both from every poll (`X-Guest-Limits` "ai-limit", `X-Guest-Limits-Use`'s third
number — sent only where the limit applies, so while the plans are off the header keeps its two numbers, which a panel opened before
a deploy still reads; scenario `free-ai-limit-follows-the-poll`); the tooltip of "bez oceny AI" names the Free plan only there too
(`dashboard.request.unchecked.title.free`, `freeAi` of the page and of every poll); "Ustawienia imprezy" the badge "oceny AI 120/150 na wieczór". No offer of Pro during
the party: the next day the organiser alone sees above the queue "Wczoraj goście wysłali 212 próśb do oceny, AI oceniło 150 …" with
"Zobacz, co daje Pro" (`freeAiYesterday`, only when some came unchecked, and only until this evening's first request — never during the next party). The staff read the bar without the plan's name (`dashboard.warning.ai_limit.staff`). In memory (two
evenings kept, by the party's row: "Zmień kod imprezy" keeps the count): a restart starts the count
again, in the DJ's favour. The pricing page names it — change both together.

### 5.3 (Spotify — removed 2026-10-04, migration V18)

### 5.4 (The YouTube player, Auto-Pilot and the background playlist — removed 2026-10-04, migration V19)

The old migrations V2–V9 and the browser tests' history refer here: the description is in `docs/history/project-context-2026-10-01.md`,
Section 5.4, and the code in the tag `full-player-2026-10-04`.

### 5.5 The demo (`/demo`) — what it keeps and how it is cleared

The demo (`DemoController`, `DemoParty`, Section 6) lets anyone look at a party's panel without a login: `/demo` opens the Polish
party `DEMO` or, for any other language, the English `DEME`; their guest pages `/p/DEMO` and `/p/DEME` take requests without asking
the AI (`SongEvaluationService.demoAnswer`), and a visitor's request is saved unchecked, so only that visitor's session sees it.

- **The two parties stay for good:** their rows in `party_settings` (owners `scan2play-demo` / `scan2play-demo-en`, no Google
  accounts) are found by their code at each reset (`DemoParty.reset`, `findByPartyCode`) and only made when missing — never a new
  one per reset.
- **Their requests do not:** at every start of the app (`ApplicationReadyEvent`) and every night at 04:55 Polish time (after the
  queues' cleanup at 04:40 and the 30-day purge at 04:45, Section 4.1) every request of both parties — the examples and the visitors'
  ones, with their dedications (`ON DELETE CASCADE`) — is deleted and the ten examples are saved again, timed from now. A visitor's
  request lasts until the next night at most. The general nightly jobs apply to the demo as to any party.
- **What else stays:** only numbers in `site_counter` (V40, no personal data).
- **The daily limit:** the party's limit (300 per 24 h, `GuestRequestLimiter`, in memory) is shared by every visitor of a demo
  party, so the reset starts it again too (`GuestRequestLimiter.resetParty`, `DemoPartyTest`): a script that floods `/p/DEMO`
  blocks the demo's guest page until the next night at most. The addresses keep their windows (60 per 10 min).

---

