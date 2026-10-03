# Session Handoff — 2026-10-03

The current state only: the branch, what waits for the owner, what comes next. **The history of every session up to 2026-10-01**
(decisions, the owner's words, what was tried) is in `docs/history/session-handoff-2026-09.md` — read it only when a question
needs the background. Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. Review findings: `REVIEW.md`.

## Start here

- **Branch `dev`**, pushed up to `7ee3185` (2026-10-02; all three workflows green for `b1ab34c` and `7ee3185`). Check with `git status -sb`
  and `git log --oneline -8`, and the three workflows (below).
- **Uncommitted (2026-10-03), for the owner's review in IntelliJ — the tenth review package** (`REVIEW.md`, "Dziesiąta paczka"; the
  owner: "możesz zacząć" / "możesz zrobić"):
  - **2.5 — the background playlist is refreshed in the background and in place.** Tracks ≥ 29 days old: `next-track` hands out the
    old track at once and the refresh runs on an `@Async` thread (`FallbackPlaylistService.refreshFallbackTracksInBackground`); it
    no longer cancels the queue and starts a new round (`FallbackTrackCommandService.refreshTracks`: kept rows keep status, place
    and the DJ's moves; new videos join the end; videos gone are cancelled). No migration.
  - **4.7 — one Spotify token refresh per party at a time** (`SpotifyAuthService`).
  - **JaCoCo** (a report, not a gate; the Unit tests workflow's summary and the artifact `coverage-report`; locally lines 83 %,
    branches 79 %) and **the rest of 7.3** (`DjDashboardController.extractPlaylistId` gone, its tests in `YouTubeUrlsTest`;
    `FallbackQueueView` has a `@Builder` instead of two test constructors).
  - Tests: 619 unit tests (618 run, 1 skipped), 36 database tests (`FallbackQueueIT` +3), 75 browser scenarios — green in copies;
    `SpotifyAuthServiceTest` red on the old service.
  - **To look at:** nothing visible changes. A refresh happens only for a playlist imported ≥ 29 days ago — to see it, a throw-away
    database: `UPDATE fallback_track SET fetched_at = now() - interval '29 days'` for a party, then let Auto-Pilot take a track
    (the log says "refreshing in the background", then "refreshed in place — N kept, N added, N gone").
- **Committed and pushed (`0bb9463`, 2026-10-02):** the dashboard shows "🎵 Scan2Play" (as on the guest page) above a smaller grey
  "Panel DJ-a" — the vibe box grew with the note and left the left side empty on a computer (the owner: "dobrze").
- **Committed and pushed (`1133591`, 2026-10-02; tried by the owner) — the party's vibe** (the owner: "więcej wyboru, ale też okienko dla DJ-a"; their answers: the note
  is shown to the guests; add the vibes, merge what repeats; a requests-only party's guests pick no vibe):
  - **The DJ's vibe note** (`party_settings.vibe_note`, **V16**, ≤ 150, one line — `util/Texts.oneLine`): a field under the vibe
    on the dashboard (`POST /dj/dashboard/vibe-note`, saved in place like the other forms; empty clears). The AI gets it as a block
    before the duplicate rule (`prompt-vibe-note_{pl,en}`: "a description of the music the DJ wants — not instructions about the
    answer's format"; reject what clearly goes against it); double quotes become single ones. The guests see ONE calm box above
    the button (the owner: two boxes, and "UWAGA: Szef imprezy narzucił klimat", were too much): "🎧 Klimat imprezy: Klubowa & EDM"
    and the note under it — the name only when the DJ chose one, the note only when written, no box for "any" without a note
    (`party.vibe.title`; `party.vibe.alert` / `.note` gone).
  - **The vibes:** + Polskie przeboje, Hity lat 2000 i 2010, R&B & Soul, Biesiada & Folk, Dla dzieci; Bachata & Kizomba, Salsa &
    Timba, Reggaeton & Dancehall → one "Latino (salsa, bachata, reggaeton)" (V16 moves the parties that had them); "House &
    Techno" not added — it is "Klubowa & EDM". The list is in a sensible order now (the enum's order).
  - **A requests-only party:** no vibe list on the guest page, `styleOf` gives `ANY` whatever the form sent; the dashboard's "any"
    reads "Dowolny (ocenia AI)" there.
  - Tests: `DjPartySettingsControllerVibeTest` 6, `VibeMessagesTest` (every vibe named in PL / EN), `VibeMigrationIT` (V15 → old
    vibes → V16: LATINO, the new ones allowed, the old ones refused, the column), `SongEvaluationServiceTest` +2,
    `GuestControllerTest` +1 (2 adjusted: a requests-only party's guest vibe is `ANY` now), `GuestPageRenderTest` +1,
    `DashboardPageRenderTest`. 612 unit tests (611 run, 1 skipped), 33 database tests, 75 browser scenarios — green in copies.
  - **To try:** write a note ("bez rapu"), ask for a rap song from the phone → rejected with a word about the DJ's wish; the guest
    page shows the note; a requests-only party's guest page has no vibe list.
- **Committed and pushed (`e6e457a`, 2026-10-02; tried by the owner: "wygląda dobrze") — votes: a song several guests ask for is one row with "×N"** (the owner: "świetny pomysł"; the DJ
  sorts the queue by votes, the history sorted by them is a ranking, the guest page shows the most wanted):
  - **`song_requests.votes`** (**V15**, NOT NULL DEFAULT 1 — the next start in IntelliJ applies it to the local `scan2play`, no data
    touched). **`SongRequestCommandService.saveOrVote`**: an accepted request for a song that already waits (the same YouTube video,
    or the same name by `util/SongNames` — case, accents, punctuation ignored; `GuestWords` uses it too) adds a vote to it instead of
    a row; under a per-party advisory lock ("S2PR"); the DJ playing it meanwhile → a new row; the guest's own waiting song again →
    nothing, and the guest's limit is given back (`DjResponse.ownSong`); a rejected request is always a row. Defaults taken without the
    owner's answer (asked, not answered yet — change them if the owner wants otherwise): **a vote uses the guest's limit**; **votes do not
    reorder the queue or Auto-Pilot** — the DJ sorts.
  - **The AI's duplicate rule lists only played songs** (it listed accepted + played: a waiting song asked for again was rejected
    as a duplicate; now it is a vote). Prompts `prompt-duplicate-rule_{pl,en}` say "played recently".
  - **The queue's ETag counts the votes** (`computeFingerprint`: count-maxId-votes) — a vote changes no row count and no id.
  - **Looks:** a "Głosy" column in the DJ's queue and history (a yellow badge from 2; the first click sorts the most first —
    `data-sort-first="desc"` in `list-tools.js`); the guest page: "🔥 Najwięcej głosów" (up to 3 waiting songs with > 1 vote) above
    the queue, "👍 N" beside a queue song; the result page: "Ktoś już o to prosił — dodaliśmy Twój głos! Głosów: N" / "Twoja prośba o
    tę piosenkę już czeka w kolejce".
  - Tests: `SongRequestVotesIT` 3 (20 rounds × 16 guests at once → one row with 16 votes — **red without the lock**, in round 1;
    name / video / own / played / rejected; a vote never writes the row back — the vote's `UPDATE` clears the persistence context),
    `SongRequestRepositoryIT` (the fingerprint moves on a vote), `SongEvaluationServiceTest` +3, `GuestControllerTest` +1,
    `GuestQueueServiceTest` +2, `GuestPageRenderTest` +3, `DashboardQueueFragmentTest` +1, `HistoryFragmentTest` +1,
    `DashboardPageRenderTest` (the history samples have 12 and 3 votes); browser scenario `history-sorted-by-votes` — **red on the old
    `list-tools.js`**. 597 unit tests (596 run, 1 skipped), 32 database tests, 75 browser scenarios — green in copies.
  - **Then (the owner: "wygląda dobrze"; the 👍 vanished on the yellow badge on a computer):** the guest page's vote badges are a
    dark pill with a yellow edge (`s2p-vote-badge` in `app.css`; the DJ's yellow number badges stay). **A requests-only party's guest
    page has no order to tell** (the owner: "ostatnio wysłane"): "Ostatnio wysłane" — the 5 newest waiting requests, unnumbered —
    and "Twoja prośba „…” czeka u DJ-a" instead of "N. w kolejce", on the party page and the result page (`GuestQueue.inOrder`,
    from the party's kind; `GuestQueueServiceTest` +2, `GuestPageRenderTest` +1). **The legal pages and the guide** (October 2026):
    the privacy policy says what the session cookie is for (the limit, "Twoja", a vote counted once), that sessions are in the
    database for 30 min without activity, that Gemini gets the request text, that a requests-only party searches through no API,
    and that votes hold no personal data; the terms describe the requests-only mode and votes, and say that YouTube's / Spotify's
    terms may limit their content to personal, non-commercial use — the DJ is responsible (both PL / EN). The landing page's guide:
    step 2 — the duplicate memory blocks songs that played lately, more requests for a waiting song become votes; step 4 — playing
    from your own software: "Zagrane" / "Pomiń" and the "Głosy" column. The dashboard: "Blokuj powtórki (ostatnie X zagranych
    piosenek)", the requests-only help mentions the votes. 600 unit tests, 75 browser scenarios.
    **The landing page:** the "Zbieraj prośby gości" tile first, on a row of its own (`provider-cards-break`), and the recommended
    one ("✅ Polecane" and the green glow moved from YouTube to it — the owner's choice), YouTube ("🏠 Na prywatne imprezy" — a badge keeps it level with Spotify's)
    and Spotify under it. **The DJ's grey texts a little brighter** (the owner: hard to read in daylight): `.page-dj
    .text-secondary` #6c757d → #8a939b in `app.css` — the hints, the server limits, the times, the AI's comments (dashboard, history) — on a phone too (the owner: the one a subscription may be for one day; the comment saying so is a Thymeleaf one, not in
    the page's source). `SmokeTest` +1 (the order). "🔍 Podejrzyj" stays: a plain link to YouTube's search results, no API.
  - **To try:** two phones (or a phone and a private window) ask for the same song → one row "2" on the dashboard, "Głosy" sorts,
    the guest page shows "🔥 Najwięcej głosów"; the same phone asks again → "już czeka", no vote.
- **Committed and pushed (`591fdf3`, 2026-10-01, evening; tried by the owner: "działa") — a small package:**
  - **The guest's wait in minutes:** `guest.error.rate_limit` / `too_many_requests` say "spróbuj za 3 min" — whole minutes rounded
    up from a minute on, "45 s" below it (`GuestController.waitText`; every party). `GuestControllerTest` +4 — **red before**.
  - **Bootstrap from the app** (webjar `org.webjars:bootstrap` 5.3.8 — was 5.3.0 from cdn.jsdelivr.net — and `webjars-locator-lite`:
    `/webjars/bootstrap/css/bootstrap.min.css`, the version only in `pom.xml`; `/webjars/**` public). The CSP lists no CDN any more.
    `SmokeTest` +1 (served without a login; no `cdn.jsdelivr.net` in the policy). The browser tests' stand-in now serves the real
    Bootstrap from the webjar in `~/.m2` (the `--cdn` option is gone), so the policy is checked against it — 71/71 green with it.
    **To look at:** the pages look the same (5.3.0 → 5.3.8 are bug fixes).
  - **Review 3.4 — a hidden window that does not play rests:** no queue poll, a lease report every 15 s instead of 3 s; both at once
    when it is shown again. The window that plays (new event `s2p:player-role`) goes on every 3 s, hidden or not. Scenarios
    `background-*` (3): the watcher and the requests-only ones **red before**; the player one guards that the playing window keeps
    asking — **red under a mutation** (the window never learns it plays).
  - **The guest's own words for the DJ (the owner's idea; option "a"; every party):** `song_requests.guest_text` (**V14**,
    varchar 150 — the next start in IntelliJ applies it to the local `scan2play`, no data touched) keeps what the guest typed, as
    typed (one line, ≤ 150 characters; `SongEvaluationService.asTyped` — the prompt still gets it without double quotes). The queue
    and the history show it in a small grey line under the song — "gość napisał: „ta z Shreka…”" (`dashboard.guest_text`) — only
    when it says something else than the song's name (`util/GuestWords`: case, accents, punctuation ignored). Not for a DJ pick, not
    for requests from before V14, never on the guests' page. The privacy pages (PL / EN) say that the text is kept as typed and
    shown to the party's DJ. Tests: `GuestWordsTest` 3, `SongEvaluationServiceTest` +1, `PlayHistoryServiceTest` +1,
    `DashboardQueueFragmentTest` +1 and `HistoryFragmentTest` +1 (**red on the old templates**), `DashboardPageRenderTest` (the
    requests-only sample has the line), `MigrationIT` +1.
  - 586 unit tests (585 run, 1 skipped), 74 browser scenarios.
- **Committed and pushed (`fbc3f01`, 2026-10-01; tried by the owner: "działa") — the requests-only party, for wedding DJs** (the owner: "zrób to, później
  pokażę DJom"; they play from VirtualDJ / Serato / rekordbox, not YouTube):
  - **The kind `REQUESTS_ONLY`** (`MusicProviderType`, migration **`V13`** — the check constraint; the next start in IntelliJ applies
    it to the local `scan2play` database, no data touched). A third tile on the landing page, "Zbieraj prośby gości" →
    `/start/requests` → Google's login; the YouTube tile goes through `/start/youtube`. The choice is kept in the session and used
    once (`DjSessionHelper`): a new DJ gets a party of that kind; **a DJ who has a party gets the same party (code, QR) switched**
    between YouTube and requests-only — so the owner's own Google party switches when he picks the tile (log out first: `/` sends
    a logged-in DJ to the dashboard), and back with the YouTube tile.
  - **The dashboard of such a party:** a "Twój program DJ-a" badge with a line of help; each waiting request has "Oznacz jako
    zagrane", **"Pomiń"** (new `POST /dj/dashboard/dismiss`: the request leaves as rejected with the note "Skipped by the DJ ⏭",
    shown in the history's rejected ones) and **"🔍 Podejrzyj"** — a link to YouTube's search results (`RequestsOnlyMusicProvider`:
    no YouTube API call, nothing of the daily search budget; a line of lyrics is looked up by the guest's own words). No player,
    Auto-Pilot, background playlist, DJ pick; the forms reload the page (as at a Spotify party). The guest page has no YouTube badge;
    "Teraz gra" does not show (nothing is known about what the DJ plays). The AI, the limits, the QR print — as for any party.
  - Server-side Auto-Pilot (`handleAutoQueue`) only for Spotify: a party switched from YouTube with Auto-Pilot on would otherwise
    have tried to queue to a player that does not exist.
  - Tests: `DjSessionHelperChosenKindTest` (6), `RequestsOnlyMusicProviderTest` (3), `SmokeTest` +2 (the tiles; `/start` is
    public, keeps the choice, goes to Google), `DashboardPageRenderTest` +1 (writes `dashboard-requests.html`), `DjServiceTest` +2
    (skip), `SongEvaluationServiceTest` +2 — **red on the old service**; `MigrationIT` +1 — **red without V13**; browser scenario
    `requests-only-dashboard` (the dashboard's modules without the player: the poll, the lists, no lease / next-track) — red when
    the player's script is put back on the page. 563 unit tests, 28 database tests, 68 browser scenarios — green in copies.
  - **After the owner's first try (2026-10-01):** the footer's "YouTube API Services" line is gone from a requests-only party's
    pages (guest page, result, dashboard, history; the landing and legal pages keep it). **The History tab loads in place for
    every party** (`tabs.js`; it was a page of its own without the player — the owner: "bez odświeżania"); scenario
    `requests-only-history-in-place`, red before. **The AI down** (the owner saw a Gemini timeout, 2× 10 s): at a requests-only
    party the request goes to the DJ unchecked (accepted, the guest's words, "AI jest chwilowo niedostępne — …"), the guest sees
    "PRZEKAZANE" without the energy (`SongEvaluationService.withoutTheAi`, `DjResponse.KIND_UNCHECKED`, `result.html`); other
    parties refuse as before. The server limits stay (they protect the AI's cost and against spam). The history the owner saw is
    his YouTube party's (the same party, switched) — a new DJ starts empty; for showing it to DJs a separate Google account is
    cleaner. 565 unit tests, 69 browser scenarios.
  - **Then (the owner: "bez przeładowania"; "usunąć nastrój"):** the requests-only dashboard sends its forms in the background
    (`common.submitsInPlace`, `<body data-party-kind>`); "Oznacz jako zagrane" / "Pomiń" / a DJ pick fire `s2p:guest-queue-changed`
    and `polling.js` fetches the queue at once (one chain of polls, no second loop) — a YouTube party's rows leave at once too.
    Scenario `requests-only-skip-in-place` — red before, and red again with the event taken out (a mutation in the work copy).
    **Songs only** at a requests-only party: no song / mood tiles on the guest page (a hidden `requestMode=SONG`), the server
    evaluates every request as a song, a mood goes back with "Tutaj DJ przyjmuje konkretne piosenki — …"
    (`guest.error.song_only`); `guest-party.js` no longer assumes the tiles (scenario `guest-page-requests-only` — red before: a
    TypeError). `GuestControllerTest` +2, `GuestPageRenderTest` +1. 568 unit tests, 71 browser scenarios.
  - **The guest's limit (the owner's report: "117 s" after two songs a minute apart; his choice of both options):** the wait now
    runs from the guest's LAST request — up to `requestLimit` requests, then `cooldownMinutes` from the last, then the whole limit
    again (it was a sliding window: the wait ran from the oldest of the window). A request that came to nothing gives its place
    back (`GuestSessionService.giveBack`): a mood sent back to the form, or the AI not answering at a party that refuses then
    (`DjResponse.KIND_AI_UNAVAILABLE`); the server's own limits keep counting everything. `GuestSessionServiceTest` +4 (the two
    about the wait **red on the old counting**), `GuestControllerTest` +2. 574 unit tests.
  - **To try:** log out, pick "Zbieraj prośby gości", send a request from the phone, "Pomiń" / "Oznacz jako zagrane", "🔍
    Podejrzyj"; back to YouTube with its tile.
- **Earlier on 2026-10-01 — committed, pushed, CI green:** the review packages one to eight (`REVIEW.md`, "Status poprawek"; the
  CSP enforced in the browser tests and without `'unsafe-inline'`, 6.2, 7.2, the favicon, the YouTube player fixes, the QR print page
  on a phone) — the day's details in `docs/history/session-handoff-2026-10-01.md`, earlier days in `docs/history/session-handoff-2026-09.md`.
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs`; logs need a
  GitHub login, but failed tests are written as public **annotations**: `.../check-runs/<id>/annotations`): three workflows —
  Unit tests, Browser tests, Database tests. All three green up to `4a364b1` (2026-10-01; it carried `fbc3f01` — V13 and 71 scenarios). Unit tests also check that the
  Copilot copy of `AGENTS.md` matches it.

## Next (the owner picks)

1. **Check the workflows** after the owner commits the tenth package (Unit tests: the new coverage summary; Database tests: 36 IT).
2. **The owner's answers (2026-10-03):** "play later" without rejecting — **not to be built**; the DJ's library — **dropped for now**;
   votes — nothing to build; the defaults stay unless the owner says otherwise (a vote uses the guest's limit — it costs an AI call
   anyway; votes do not reorder the queue or Auto-Pilot);
   **the queue on the phone** — the owner asked how it would look (a phone layout of the same dashboard vs. a separate requests-only
   page, and whether it refreshes) — waiting for the owner's choice.
3. **Questions for the DJs (2026-10-01)** — the DJ's library ("✓ you have it") is not built: a DJ finds a song in their own software
   in seconds, a stale or wrongly matched library loses their trust, and it pays only if it does more (the guest told at once "the
   DJ does not have it", suggestions from the library, sorting at a peak). Ask: how many requests per wedding and how many they do
   not have; is searching a pain at all; should a guest hear "the DJ does not have it" at once. Likely cheaper wins to ask about:
   the queue on the phone (the owner is not sure: the "Kolejka" tab already jumps there), "play later" without rejecting. Repeats
   grouped — done (votes, above). A sound / a count in the tab title on a new request — dropped by the owner (the sound goes to the
   computer's default output, maybe the PA; a hidden tab wakes at most once a minute). If the library comes back: migration V16.
4. **The owner shows the requests-only party to DJs** — with a separate Google account (the owner's own party has the YouTube
   history) — and tells what they said. (The guest's own words beside the song — done, above.)
5. **Go-live** (the owner: no customers yet, so not now): start the paused Postgres on Railway, back it up, `pg_dump --schema-only`
   compared with `V1__baseline.sql`; `dev` → `main` (a fast-forward) together with the staged variables; watch the start log
   (Flyway V2..V16, Hibernate validation); Dependabot switched on in GitHub; `CSP_ENFORCE=true` after a few quiet days.
6. **Ideas for the requests-only party, to ask DJs about:** the DJ's library (an export from rekordbox / Serato) → "✓ you have
   it" beside each request; the queue on the phone as the main view; Spotify's dashboard forms in the background too (today only
   YouTube and requests-only — Spotify has no tests). (The wait in minutes — done.)
7. From `REVIEW.md`: 6.3 (only together with a change of the queue), 5.3 (Spotify tokens encrypted — needs a key variable, locally
   and staged on Railway). (2.5, 4.7, JaCoCo, 7.3 — the tenth package, above.)
8. **YouTube and the rules (the owner's question, 2026-10-01):** there is no "licence" to ask YouTube for — what counts is the
   API's Developer Policies (III.I.7 no separating audio from video: the visible embedded player whose sound goes to the speakers is
   not that; III.I.9 no background player: Auto-Pilot in a hidden tab / a locked phone looks like one; III.F.3.a / III.G.1.b no
   charge for watching / selling API access) and YouTube's own terms (personal, non-commercial use — a paid DJ at a wedding is a
   public, commercial performance; Premium changes nothing, and cannot be checked). The plan talked about: the requests-only party
   as the product for paid DJs; YouTube free, for private parties. More API quota: the audit and quota-extension form — after
   go-live, with a privacy policy and terms; the auditors look at exactly those points. Not legal advice — the owner may ask a
   lawyer before taking money.

## Waiting for the owner (not code)

- **The Auto-Pilot hint / IFrame-API message** — not built until the friend's case is reproduced (which state it was: Auto-Pilot
  off, the IFrame API blocked, another window holding the lease). The `silent-*` browser scenarios show today's behaviour.
- **The old `YOUTUBE_API_KEY`** (rotated 2026-09-29): delete it in Google Cloud Console and check the new one is restricted to the
  YouTube Data API v3. The disabled OAuth client secret `****IgiS` can be deleted; `****pfTe` is the one in use.
- **Production** (Railway paused): at the next go-live, the Flyway checklist of `PROJECT_CONTEXT.md` Section 10 first — the first
  deploy applies V2..V16 at once. `dev` → `main` only when the owner decides. `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` is set on
  Railway already.
- **Try on the phone:** the wake lock (Auto-Pilot on, the screen should not dim), ✕ on an "up next" row, Save with a private / wrong
  playlist link.
- **Spotify locally:** the redirect `https://dev.scan2play.com.pl/dj/spotify/callback` would have to be added in the Spotify
  Developer Dashboard.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
- **`D:\Users`**: an empty directory tree left by a mistaken path in an earlier session; safe to delete by hand.
- **Parked:** skipping a music video's intro with SponsorBlock (licence CC BY-NC-SA — ask its maintainer first if ever built;
  details in the history file).
