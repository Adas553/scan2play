# Session Handoff — 2026-10-10

The current state only: the branch, what waits for the owner, what comes next. **The history** (decisions, the owner's words, what
was tried) is in `docs/history/`: `session-handoff-2026-09.md`, `session-handoff-2026-10-01.md`, `session-handoff-2026-10-04.md`
(2026-10-02 … 04: the phone dashboard, votes, the vibe note, "Kto gra", the removal of Spotify and YouTube, package by package).
Working agreements: `CLAUDE.md`. Architecture and rules: `PROJECT_CONTEXT.md`. The review of 2026-09-30: `docs/history/review-2026-09-30.md` (done; 2.3, one instance, left open).

## Start here

- **2026-10-10, afternoon — the invitation's role and invitations by e-mail, on the branch `claude/inspiring-einstein-sgh2tz` (on top
  of `dev` = `0156658`, PR #38 / #39 merged, CI green), not committed yet.** The owner's picks: the link's role changes only with a
  new link ("A"), an invitation by e-mail waits 30 days, its address is shown only while it waits (gone when answered), the staff and
  the invitations share the 10 places, "Nie, dziękuję" deletes it. Done, PROJECT_CONTEXT 4.1 / 5.1: **V33**
  `party_settings.staff_link_permissions` (the link made with a role — "Ten link: Podgląd"; old links give "Obsługa kolejki");
  **V34** `staff_invitation` (the organiser types the address of a Google account and the role on "Obsługa"; nothing is sent — whoever
  logs in with that verified address is sent to `/dj/invitation` before any panel: "Ola Kowalska zaprasza Cię do obsługi imprezy: …
  [Dołącz] [Nie, dziękuję]"; Gmail's dots and "+…" ignored, `util/EmailAddresses`); the join by a link reads the party by the token
  with its row locked (a link renewed meanwhile joins nobody); the privacy policy PL / EN (the address, nothing sent, 30 days).
  Then (the owner): "Wklej link" of an invitation only on the person's own panel and "no-panel", not on another's party
  (`staff-panel` seen red with the old template; `owner-pastes-an-invitation`).
  Tests: 585 unit, 60 database (`StaffInvitationIT` — two "Dołącz" at once, invitations and joins at once never pass 10, seen red
  without the lock; `PartyStaffRepositoryIT` — a link renewed while someone joins, seen red with the old read; `StaffLinkMigrationIT`),
  65 browser (`staff-link-role`, `staff-invite-by-email`, seen red with the forms' `data-staff-form` / the question taken out of the
  rendered page). **Next:** the owner tries it locally (a second Google account in a private window, on `localhost`), then a PR to
  `dev` and `dev` → `main` (V33 + V34 on that deploy).
- **2026-10-10 — the staff reviewed and rebuilt, on the branch `claude/inspiring-einstein-sgh2tz` (on top of `dev` = `4a6d2de`), not
  committed yet.** The review (every path of the organiser and the invited person on the real app, phone and computer):
  https://claude.ai/artifact/XMPNxcn9e4NswLfmLdcMyx — the owner picked **variant B** (roles + "Własne"), roles by what a person does,
  not by their job. Done, PROJECT_CONTEXT 5.1 "The party's staff": **V32** `party_staff.permissions` (the people on a staff keep what
  they could do: "Obsługa kolejki") and `party_settings.owner_name`; `StaffPermission` (9) and `StaffRole` ("Podgląd", "Obsługa
  kolejki", "Współorganizator", "Własne"); the owner's page **"Obsługa"** `/dj/staff` (a role or ticks per person, the link, removing;
  "Konto" → "Obsługa imprezy"); **every form names its party** and the server checks the permission there (`require`, `requireOwner`)
  — fixes the review's critical bug (a tab still showing another party cleared the person's own queue / ended their party);
  **joining asks** ("Dołącz", a POST — a foreign site could join a logged-in DJ); the poll's 403 / `X-Panel-Access` reload the page
  (an access taken away or changed is said, not a frozen queue); no DJ's party made behind anyone's back ("no-panel"); the joins
  counted under the party's lock (20 at once made 11); one line "Klub Ola · Obsługa kolejki ▾" in place of the switcher, the banner
  and "Mój panel"; texts ("Link dla gości", an old link's own message, a guests' link told apart); the privacy policy PL / EN.
  Tests: 553 unit (`StaffPermissionEndpointsTest`: every endpoint refused without its permission), 50 database
  (`StaffPermissionMigrationIT`, `PartyStaffRepositoryIT` 20 joins at once — seen red without the lock), 62 browser
  (`forms-name-the-party`, `staff-access-taken-away`, `staff-access-changed` seen red on the old scripts; `staff-page-roles`,
  `staff-access-same`). Released (PR #38 → `dev`, PR #39 → `main`, CI green); both next steps done the same day (above).
- **2026-10-09, night — the design review and all of its fixes, on the branch `claude/inspiring-einstein-sgh2tz` (on top of `dev` =
  `66c0007`), not committed yet.** The review (every screen on a phone 390×844 and a computer 1280×900, the contrast and the touch
  targets measured): https://claude.ai/artifact/NXTvqH6d2TauoH1RSVVJaJ — the owner: "zrób wszystko". Done: one action colour (the
  cyan of the "2", `.btn-action`: the guest's "Wyślij prośbę" was green, the invitation and the hosts' page yellow, the prints blue),
  the grey text at 7 : 1 (was 3.3–4.2 : 1), 44 px targets on a phone, dark messages in place of pale boxes, the guest page in the
  order a guest uses it (the field first, the profiles and the tip under the list), the result page (logo and "Gra: …" at the top,
  "PRZEKAZANE" no longer "PRZEKAZAN / E", no energy, "Zaproponuj kolejną" the main button), the panel's heading in one row, the
  account under a "Konto" menu ("Zakończ imprezę" in words), the queue in five columns (no vibe / verdict / energy, "AI: …" under the
  song) and an empty queue that offers the QR code and "Ustaw klimat", the settings as cards of one pattern ("Źródło odtwarzania"
  gone), one message at a time for the staff, emoji only as markers, the words "organizator" / "obsługa", "Zgłoś uwagę", "Polityka
  prywatności". PROJECT_CONTEXT 6.7 "The look" is the small design system for new screens. Tests: 492 unit, 58 browser (`design.js`:
  `guest-touch-targets`, `dashboard-touch-targets`, `account-menu`, `queue-empty-next-step` seen red without the `app.css` /
  `settings-toggle.js` change; `guest-form-first`; `tabs` now unfolds the settings first — folded, the queue is in view already).
  Then (2026-10-10, the owner: "dużo lepiej"): "To urządzenie" moved to the right column, under the profiles (the staff: the right
  column alone, in the middle), and **the AI's energy rating gone from the whole project** (the owner: not needed) — the prompt and
  `ANSWER_SCHEMA` no longer ask for it, `DjResponse` / `HistoryEntry` / the entity without it, the history's column and the CSV's
  last column gone, **V31** drops `song_requests.energy_level` (`MigrationIT`). Tests: 492 unit, 46 database, 58 browser.
  Next: the owner looks at it on a phone, then a PR to `dev` (V31 on the deploy to `main`).
- **2026-10-09, evening — released (PR #35 → `dev`, on `main` = `432d563` with PR #34, CI green):** the staff's
  login on the landing page ("👥 Jestem z obsługi": paste the link → Google's login → joined; a bad link is told before any
  login) and "🚪 Opuść obsługę" in place of "Usuń konto" on another party's panel (the owner: it read as deleting the party).
  Tests: 492 unit, 45 database, 53 browser (`staff-panel` seen red without the `forms.js` change).
- **2026-10-09, afternoon — released before (PR #32, `main` = `f57047d`, v30 applied, CI green, two people joined the staff of
  14FMF); then released with PR #33 → `dev` and PR #34 → `main`:** "🎧 Mój panel" always offered (the owner joined with a second account from
  the installed app and had no way to make a party of their own — the button showed only to those who had one), and an
  invitation link pasted in the app (`POST /dj/join`; the Home Screen app opens e-mail links in the browser, with its own
  login). Tests: 488 unit, 45 database, 52 browser (`staff-panel` seen red without the `forms.js` change). Then the guests'
  list: the song's number a column of its own, a long name wraps under the name, not under "#18" (`guest-list-number-column`,
  seen red with the old row) — 53 browser.
- **2026-10-09, evening — released (PR #31 → `dev`, PR #32 → `main`):** the panel's settings fold under "⚙️ Ustawienia, klimat i kod QR" **on a computer
  too** (the owner: there are many of them now); `.s2p-phone-settings` is `.s2p-settings`; folded, the heading's column takes the
  whole width, unfolded the vibe's box goes under the logo. Scenarios `requests-only-dashboard` (folded on a wide screen) and
  `host-lists` (measures the layout unfolded — before, it measured a hidden card and passed on zeros).
- **2026-10-09, later — released (PR #31 / #32, V30 applied), on top of the V29 work below:** **the party's staff** (V30, PROJECT_CONTEXT 5.1 "The
  party's staff"): a bartender joins by the owner's invitation link `/join/{token}` with their own Google account and sees the queue
  and the history only; the owner's card "👥 Obsługa" lists them ("Usuń dostęp"); the panel switcher "🎧 Mój panel / 👥 …"; their
  devices get the notifications too; the privacy policy says what is kept (their Google id and name). Owner's decisions: a
  bartender may have a party of their own (the switcher), the staff may clear the queue (not the history) and close / reopen
  the requests. Tests: 487 unit, 45 database (`PartyStaffRepositoryIT`), 52 browser (`staff-panel`, `staff-card` — seen red with
  the old `forms.js`; `staff-panel` also found the staff's queue never polled: `#partyCode` was inside the owner's settings).
  Next: the owner tries it with a second Google account (an invitation on a phone), then one PR to `dev` with V29 + V30 and
  `c41c7c8`.
- **2026-10-09 — released (PR #31 / #32, V29 applied):** **the hosts' lists** (V29,
  PROJECT_CONTEXT 4.1 "The hosts' lists": "🚫 Nie grać" refused before the AI or after it, "⭐ Koniecznie zagrać" accepted against
  the AI and marked in the queue, the hosts' own page `/h/{token}` without an account) — "🎶 Teraz leci Twoja piosenka" on the guest page was built
  and taken out again (the owner: a DJ marks several songs played at once, so it would lie); the card moved under the limits, the lists side by side (in the right column it left a gap on a
  computer). Tests: 467 unit, 40 database, 50 browser (`host-lists` — seen red with the old `forms.js`: the queue not
  fetched at once, the link sent in the background, "Kopiuj" copying nothing). The iPhone: tried by the owner, it works (no
  iPhone request in the log since the release — tried before it). Next: the owner looks at the card and `/h/…` on a phone, then a
  PR to `dev` with `c41c7c8`.
- **2026-10-08, the end of the day:** **released** — PR #30 (`dev` → `main`, `48a20b5`, CI green) is on Railway since 20:20 UTC:
  "Successfully applied 2 migrations … now at version v28", started in 5.5 s, no CSP violations, no AI errors (one "OAuth2 Login
  Failed: [authorization_request_not_found]" three minutes after the restart — a login begun before it; the next one worked).
  **Next PR to `dev`:** the result page no longer shows "revolut.me/… · prosto do DJ-a, poza Scan2Play" under the tip button (only
  the party page does; `components :: tip(tipUrl, number, withNote)`). Still to do: the phone test of V27/V28.
  **Samsung Internet:** its own dark theme still greys the logo and turns the tip button brown, `color-scheme` or not — Samsung
  gives sites no way out (developer.samsung.com forum); the owner: leave it. A Samsung user can switch it in Settings → Labs →
  "Use website dark theme".
- **2026-10-08, night — released (PR #29 → #30, `48a20b5`):** **songs' numbers and the
  DJ's tips** (V28, PROJECT_CONTEXT 4.1 "Numbers and tips"): "#27" for the guests (list, result, the tip's title — "Jeśli chcesz,
  wpisz…") and the DJ (an "ID" column in the queue and the history, search "27"; the place numbers "4." on a wide screen only),
  "💸" in the queue only counts a tip (`POST /dj/dashboard/tip-count`), the evening summary and the CSV count them; "Wyczyść
  historię" starts the IDs again (#1 with the queue empty). Every page declares `color-scheme` (Samsung Internet's dark theme greyed
  the logo). A tip only marks the song —
  moving it up was left for when DJs ask. Tests: 447 unit, 39 database, 49 browser (`queue-search-by-number`, `tip-count-in-place` —
  seen red without the scripts' change).
- **2026-10-08, late evening — released (PR #29 → #30, `48a20b5`):** **the DJ's tip link**
  (V27 `party_settings.tip_url`, `util/TipLinks`, `POST /dj/dashboard/tip-link`): "💸 Napiwek dla DJ-a" on the party page and under an
  accepted request, "Napiwek / Tip: revolut.me/…" on the QR print (a card: in the texts' column — the code's column was full,
  `qr-print-cards-layout` caught it). Only links to Revolut, PayPal, buycoffee.to, Suppi, Tipply, Buy Me a Coffee, Ko-fi; the money
  goes straight to the DJ; the privacy policy (PL / EN) says so. Tests: 442 unit, 36 database, 47 browser.
- **Live 2026-10-08 (PR #27 / #28, `main` = `af6363c`, CI green, V26 applied at start):** **"📊 Podsumowanie wieczoru"** (`/dj/summary`, PROJECT_CONTEXT 5.1): one evening (6:00–6:00 Polish time) to print / save as PDF,
  and as CSV — the counts, how long the played requests waited, the 10 most wanted, "Chcieli, a nie usłyszeli", the most wanted
  artists, requests every half-hour (the owner wants to see how it looks), what played in order; a button beside "🗑 Wyczyść historię",
  whose question now points to it. **V26**: "Sarkastyczne (łagodne)" gone (the owner: "sarkastyczny wystarczy"), its parties now
  "Sarkastyczne". The prompt's "not knowing a song is no reason to make one up" (`8b638ef`; all of it one PR to `dev`). Tests: 414 unit,
  35 database, 47 browser.
- **Branch `dev`. The product is the requests-only party** ("Twój program DJ-a"; the owner's decision 2026-10-04: YouTube — 100 API
  searches a day shared by every party, its terms; Spotify — development mode, its policy). The full app is archived: tag
  `full-player-2026-10-04`, branch `archive/full-player` (both pushed), the clone `D:\Coding\scan2play-full` with its own database
  `scan2play_full` — leave them alone.
- **Stage 3 (YouTube goes) — done, all three packages committed and pushed, CI green:** `5a1bf79` (the player and Auto-Pilot,
  browser side), `e44d00f` (the server side, one kind of party, **V19** — deletes every party that is not requests-only with its
  data, drops the player's tables and the kind's columns; the owner tried it locally: "działa"), `1b461b8` (texts and docs: the
  landing page's guide, privacy and terms PL / EN without the YouTube API, `AGENTS.md` + the Copilot copy, `PROJECT_CONTEXT.md`
  cut to the current state — Sections 5.4 and 14 are pointers to the history, old migrations refer to them —, `REVIEW.md`).
- **After it:** `1fa2dff`, `c08ab71` (our logo on the pages, the QR prints and the favicon), `c83642d` (a request the AI rejects for
  a song that waits is a vote on it, with the song's verdict; a rejected request gives the guest's limit back — the server's limits
  still count it), `b2e9620` (the guest's own words under every song in the queue and the history; `util/GuestWords` gone).
- **Review of the removal (2026-10-05):** complete; leftovers tidied — the dead `vibe.ANY` text ("guests choose": `vibe.ANY` is now
  "the AI judges", `vibe.ANY.requests` gone), `DjResponse.withRequestId`, stale comments about the player, the DJ pick and the
  "guest" / "background" filters. The privacy policy (PL / EN) now names the guest's IP address: `GuestRequestLimiter` keeps
  it in memory with the party's code for the per-network limit, forgotten 10 minutes after the last request from that network
  (`guest.limit.per-ip-window-minutes`; `SmokeTest` ties the pages to it); not in the database, not in the application's logs.
- **Code review, class by class (2026-10-05), fixed:** the AI's answer read defensively (an unknown field is ignored; a decision
  other than `accepted` in any case is `rejected` — "Accepted" was saved and then shown nowhere); an empty request goes back to
  the form before any limit or the AI (with the AI down it was an empty row in the queue); `@DynamicUpdate` on
  `SongRequestEntity` (a vote that committed while the DJ marked the song played was lost — `SongRequestVotesIT`, seen red); the
  duplicate rule's songs by when they played; the logout deletes Spring Session's cookie `SESSION`; no `@Data` on
  `PartySettingsEntity`; the limits form rounds all three numbers alike (Infinity became 1); `Texts.oneLine` never splits an
  emoji; the cache names as constants; dead `authentication == null` branches and `REDIRECT_LOGIN` gone. Kept on purpose: the
  guest's own requests stay in the session (they outlive a deploy; two requests at the same moment may forget one — the form
  sends one at a time). Merged into `dev` (PR #5).
- **After the review (2026-10-05, the owner's picks), PR #8:** a song the DJ skipped ("Pomiń") is not saved again for 2 hours
  from the skip (`skipped_at`, V20; the owner: 12 h was too long) — the next guest hears "Tej piosenki DJ teraz nie zagra —
  wybierz inną" (option A of three; a song only cleared with the queue may come back); a skip by mistake is undone with "Cofnij"
  (a bar for 8 s after the skip) or "↩ Przywróć" in the history (`POST /dj/dashboard/restore`); Gemini gets the schema of its answer (`ANSWER_SCHEMA`); a first login in several tabs at once gives every tab the one
  party (`FirstLoginIT`, seen red: the UNIQUE owner_id); `AiHealthMonitor` writes "AI check: N of M guest requests … unchecked"
  to the log every 5 minutes while Gemini fails; a browser in English (or any language without a bundle) gets the English texts
  on a Polish server too (`spring.messages.fallback-to-system-locale=false` — no `messages_en` file needed: `messages.properties`
  is the English one). Tests: 267 unit, 24 database (PostgreSQL 18), 26 browser scenarios.
- **Live in production since 2026-10-06** (`PROJECT_CONTEXT.md` Section 10): `main` fast-forwarded to `dev` (`b61f4fd`, CI green),
  Railway built it, Flyway baselined the April schema and applied V2..V20, Hibernate validated, the app runs at
  `https://www.scan2play.com.pl`. The owner chose no backup (only their and friends' April tests, deleted by V18 / V19 anyway). Railway
  variables tidied (YouTube, Spotify and `SPRING_JPA_HIBERNATE_DDL_AUTO` removed). The owner's manual test passed: the DJ's login, a
  guest's request through the QR code, "Pomiń" → "Cofnij", the history; the live Gemini answered with `ANSWER_SCHEMA` (no "the AI
  could not be asked" in the log). **Both Railway services sleep** — kept on purpose for now (only friends use it); the log showed
  the cost: an app that woke before its database failed once and was restarted by Railway, and one request at 16:04 got an error
  page when the sleeping database dropped its connection. Before real customers: switch the database's sleep off (or at least
  `spring.flyway.connect-retries` for the start).
- **Notifications on the DJ's devices (2026-10-06, the owner's request; not deployed yet):** a switch "🔔 Powiadomienia na tym
  urządzeniu" per browser (the owner: a checkbox for the DJ); a **new** song on the list (not a vote) vibrates the DJ's phone with
  "🎵 Nowa prośba — <song>" (the owner's picks: new songs only, the title on the lock screen); several fold into "🎵 Nowe prośby: 3".
  Web Push (`PushNotificationService`, `zerodep-web-push-java`), **V21** `push_subscription`, `/sw.js`, the web app manifest and its
  icons (an iPhone takes notifications only from the dashboard added to the Home Screen, iOS 16.4+ — the switch says so), the privacy
  policy PL / EN. Then: the VAPID keys read as they are pasted (the owner's IntelliJ variable had lost characters; the log now names
  the key and its length, and a public key of another pair is refused), the status bar's small icon (`badge-96.png`: the mark alone
  on transparent — the app icon showed as a white square), a button "📲 Zainstaluj aplikację" (Chrome's own offer does not come back
  for months after a dismissal or a removal). Tests: 290 unit, 27 database, 35 browser (9 new scenarios, seen red on a broken
  `push.js` / `install.js`). **The owner's try (2026-10-06, local, Android, over HTTPS): a notification arrived** ("🎵 Nowa prośba —
  Elektryczne Gitary - Widziałem Orła Cień" — a song 2.5 Flash made up: it is Varius Manx, "Orła cień"); no sound because "Nie przeszkadzać" was on (a DJ adds Scan2Play to its exceptions).
  Not tried yet: an iPhone, production (the keys on Railway).
- **Deployed 2026-10-06 22:02** (PR #9 → `dev` → `main` `2b31300`, V21 applied, the VAPID keys on Railway: "Push notifications on").
  The owner then saw no switch: Cloudflare served the old `main.js` (the HTTP log: the browsers never asked the server for it), a
  "Purge Everything" did not help. Fix: the scripts' and styles' addresses carry the deploy's version (`/<commit>/js/...`,
  `PROJECT_CONTEXT.md` Section 6.3). Worth a look in Cloudflare (Caching → Cache Rules, Browser Cache TTL): the server sends
  `no-store`, yet the edge kept the files.
- **Checked in production (2026-10-07, the logs):** PR #10 live (`484fa54`); the dashboard loads `/<commit>/js/...`; notifications on
  for two devices (Windows, Android); a guest's request at 22:35 UTC → the Android phone fetched the notification's icons at once,
  no push error in the log. The owner set Cloudflare's Browser Cache TTL to "Respect Existing Headers": no more `max-age=14400`,
  the browsers get `304`s again (Cloudflare's edge still keeps `sw.js` / images: `cf-cache-status: HIT`). No "CSP violation", no
  "AI check" since the deploy (little traffic). Not tried yet: an iPhone. The log's `HikariPool-1 - Failed to validate connection`
  warnings are the sleeping database (the pool reconnects).
- **"🗑 Wyczyść historię" (2026-10-07, the owner's request; live, PR #11 / #12):** in the history's filter row, asks first;
  `POST /dj/dashboard/clear-history` → `DjService.clearHistory` (under the party's lock) → `SongRequestRepository.deleteHistory`:
  the party's played and rejected requests deleted, never a waiting one, never a skip of the last 2 hours (it keeps its song out).
  The confirmation says the guests' words go and the AI forgets what played. Without the summary of the night for now (Next,
  item 2). Tests: 293 unit, 28 database, 36 browser (`history-clear`, seen red with `forms.js`' branch taken out).
- **The AI's comment style (2026-10-07, the owner's picks; live, PR #11 / #12):** "💬 Komentarze AI" under the vibe — Klasyczne (the
  prompt as before), Zabawne, Sarkastyczne (łagodne — gone in V26, 2026-10-08), Sarkastyczne (the owner: sharp, the DJ's own responsibility), Krótkie (an
  example under the list was tried and dropped: the owner did not want it). **V22** `party_settings.comment_style`; the blocks in
  `prompts/prompt-comment-style_{pl,en}.txt`; every style keeps "no profanity, mock the request, not the person" and reminds the
  AI not to name an accepted song (the sarcastic one did in the owner's local try).
- **The guest's waiting songs (2026-10-07, the owner: confusing):** the result page no longer says "Twoja prośba „X” czeka u DJ-a"
  (it named the guest's oldest waiting song under the result of another one); the party page names the song when one waits and
  counts them when several do ("Czekają u DJ-a Twoje prośby: 3"; `GuestQueue.myWaiting`), the list marks them "Twoja".
- **A skip keeps the AI's comment (2026-10-07, the owner):** "Pomiń" no longer writes "Skipped by the DJ ⏭" over it, "↩ Przywróć"
  / "Cofnij" no longer write "Restored by the DJ ↩" (English on a Polish page): the request comes back with the AI's comment; the
  history says "⏭ Pominięta przez DJ-a" from `skipped_at`. **V23** clears the old notes (their AI comments are lost). The result
  page's "Energy: 7/10" is "Energia: 7/10" in Polish (`result.energy`). A cleared queue's note followed on 2026-10-08 (V25, below).
- **The history on a phone (2026-10-07, the owner: chaotic):** a card per request, as the queue — the title across the card (it
  broke into a word per line), the skip and "↩ Przywróć" inside the screen, the AI's comment shown; sort by song / votes kept.
  Tests: 300 unit, 30 database (`SkipCommentMigrationIT`, seen red without V23), 38 browser (`history-phone`, seen red without
  its CSS). A skipped request shows "⏭ Pominięta przez DJ-a" (amber) in place of the red "ODRZUCONE" / ✖ (the owner: the AI took
  it, the DJ skipped it); still under the "Odrzucone" filter, by the owner's choice. "ANY" shows as "Dowolny", the column "Vibe" is
  "Klimat", the landing page says "klimat imprezy" (not "Global Vibe").
- **The queue's buttons (2026-10-07, the owner):** "▶ Zagrane" (filled cyan, one line — "Oznacz jako zagrane" broke into three) and
  "⏭ Pomiń" (outlined, readable — the grey looked switched off — amber under the pointer); on a phone "▶ Zagrane" is two thirds of
  the card's row (`requests-only-phone`, seen red without it). "🔍 Podejrzyj" grey in the queue as in the history (red is for
  what deletes). On a phone the history's AI comment is one line with "…", a tap opens it, another folds it (`list-tools.js`;
  `history-phone`, seen red without the toggle).
- **CI** (`gh` is not installed; the public API answers: `https://api.github.com/repos/Adas553/scan2play/actions/runs?head_sha=…`;
  failed tests are public **annotations**: `.../check-runs/<id>/annotations`): Unit tests, Browser tests, Database tests. Green up
  to `b2e9620`. Unit tests also check that the Copilot copy of `AGENTS.md` matches it.

- **Deployed 2026-10-07 12:46** (PR #11 → `dev`, PR #12 `dev` → `main` `a06e4be`, the owner merged both): the start log showed
  Flyway applying V22 and V23 (0.05 s), "Push notifications on", started in 5.5 s, no error; the landing page answers with the new
  texts (PL / EN). Pushes to `dev` / `main` from Claude were refused by auto mode ("Production Deploy"); the owner merges on GitHub.

- **"⚠ Sprawdź" (2026-10-07, the owner's request; live, PR #13 / #14):** a guest wrote "orła cień" (Varius Manx - "Orła cień"; our own notes had said Elektryczne Gitary — that was 2.5 Flash, too) and the AI saved
  "Dżem - Sen o Victorii" — another song by its mood, which the prompt forbids. Now a song with none of the guest's words is marked
  "⚠ Sprawdź" in the queue and the history (a yellow badge under the song, a tooltip says why) and its "🔍 Podejrzyj" searches by
  the guest's words. `SongNames.sharesNoWord`: the guest's words of 3+ letters, each one's stem (its first max(3, length − 2)
  letters: "sen" whole, "baśce" → "bas") looked for in the AI's name by `comparable` — "ta o Baśce" finds "Wilki - Baśka", "labamba" finds "La Bamba", a bare
  artist finds its song. Computed when shown (old rows too, no migration); the link is decided when saved. **Measured** on the
  local database (21 requests with the guest's words): no false mark — and one miss: "orła cień" → "Budka Suflera - Cień Wielkiej
  Góry" shares "cień". Marked although right: a description by context ("ta z Shreka" → "Smash Mouth - All Star"), a name spelled
  by ear ("bitelsi"), a line of lyrics with no word of the title. Tests: 323 unit (`SongNamesTest`), 39 browser
  (`check-song-phone`, seen red with the badge taken out).
- **"□" in a song's name (2026-10-07, the owner; live, PR #13 / #14):** not a dash the font lacks — a control character. The guest's
  "–" (U+2013, as iTunes' suggestions write it) came back from the AI as a backspace (U+0008: "Hulewicz □ Za zdrowie Pań") or a
  line break ("Brathanki↵– Czerwone Korale", "Grubson↵G Nie Nie Nie" — that stray "G" cannot be undone); a guest's "-" never did.
  Now the AI is given "-" for every dash (`forPrompt`), and its name is tidied when read (`SongNames.tidy`: a control character is
  " - ", every dash "-", one in a row). Rows saved before keep their "□" (local ones; gone after 30 days). Tests: 325 unit.
- **The DJ's profiles (2026-10-07, the owner's picks; live, PR #13 / #14, V24):** Instagram, Facebook, TikTok (not a website; the owner: no
  "follow the DJ" on the result page). Three fields in a card under the dashboard's QR code (the owner: not beside "Kto gra" — it stretched the heading's column) → `POST /dj/dashboard/dj-links`; the DJ types "@name", a name
  or a link copied from the app, the server keeps an https address on that site (`util/SocialLinks`; a look-alike host, another
  site, a post instead of a profile → 400, nothing saved, the form says why and its button shows ✗). **V24** (three columns). The
  guest page: buttons with the sites' icons (Bootstrap Icons 1.11.3, MIT: their paths inline in `index.html`) under "🎧 Gra: …"; the QR print (the poster: under the code; every card: our logo above the code, the profiles under it, the texts beside — the owner; `qr-print-cards-layout`, three profiles fit a 68 mm card with ~4.5 mm to spare): "Instagram @djkoko", "TikTok @…", "Facebook <name>"
  (a page known only by its number is left off the paper). The privacy policy PL / EN names them (public; the sites open only on
  a click). Tests: 363 unit, 30 database (V24 by `MigrationIT`), 41 browser (`dj-links-refused-then-saved`, seen red with the old
  `forms.js`). Not tried yet: how the poster looks printed with three profiles (it must stay one A4 page).

- **Gemini 3.5 Flash (2026-10-07, the owner: "czas na model 3.5, nie może być takich pomyłek"; live, PR #15 / #16):** `GeminiComparison`
  (`src/test/java/com/scan2play/service`, run from IntelliJ with `GOOGLE_AI_API_KEY`; writes `target/gemini-comparison.md`): 30 hard
  requests × 2 on 6 variants. 3.5 Flash (low): every checkable song right, 2.7–3.1 s on average, ≤ 8.3 s, ~$2.4–2.6 per 1000
  requests; 2.5 Flash (today): "orła cień" got a different wrong song on each try (Lady Pank, IRA, Dżem, Perfect, Niemen — it is
  Varius Manx), ~$1.9; 3.5 Flash medium: no more right, ~$8.6, once over 10 s; 3.5 Flash-Lite: made-up artists ("Delfin - Sen o
  Wiktorii"), "bajlando" read as Magdalena Tul. Now: `gemini-3.5-flash`, `google.ai.thinking-level=low` (a gemini-3 model takes a
  level, a gemini-2 one the budget — `thinkingConfig`), the call's timeout 15 s. **The prompt rewritten** (the owner's pick, measured
  first): three steps, the song named before the verdict (`ANSWER_SCHEMA`'s order), no "ruthless DJ" and no capitals, the DJ's
  note before the genre. On 3.5 Flash as right as the old one, a little faster (≤ 6.6 s), stricter with the note ("Salsa": Macarena
  rejected — the owner: right, it is latino, not salsa). Tests: 366 unit.
- **Live 2026-10-07 (PR #15 / #16), the owner's first tries on production (live, PR #17 / #18):** a sarcastic comment quoted the
  prompt's code to a guest ("Ale skoro 'ANY', to niech będzie"), and "somos hermanos" became "Armando Manzanero - Somos Novios"
  (a similar-sounding title; "⚠ Sprawdź" stays quiet: "somos" is shared). Fixed: no genre reaches the AI in words
  (`genreForPrompt`: "dowolny (DJ nie wybrał gatunku)", never "ANY"), the comment must not quote the instructions, and Step 1
  says a similar-sounding title is another song (with that very example). `GeminiComparison` now marks a comment with "ANY"
  WRONG and expects "Somos Hermanos". Then "con que" became "Ray Sepúlveda - Con Qué Derecho" (a guess dressed as a fact):
  Step 1 now adds an artist or the rest of a title only when the words point to one well-known song, a request too short or too
  general is copied as typed (the comparison's case "=con que": nothing added). The comparison then (3.5 Flash low): 59 of 60
  right, "con que" copied every time, no "ANY"; "somos hermanos" still "Somos Novios" once of two — its comment ("myląc rodzeństwo
  z kochankami") shows the AI "correcting" the guest. Step 1 now (the owner: guests do get it wrong too): fix typos and garbled
  words of a well-known title, but a real song's title means that song, not a better-known one — kept as typed, the DJ's
  "🔍 Podejrzyj" still finds what the guest meant; swapped, the DJ gets a wrong song unawares (not measured yet).
  ~$3.7 per 1000 requests (was ~$2.4: a longer prompt, more thinking) — ~$1.1 for a wedding of 300. Tests: 367 unit.

- **3.5 Flash timed out on production (2026-10-07 16:51–16:53 UTC; fixed in the next PR):** two requests ("dichavate") waited 30 s and
  went to the DJ unchecked ("AI check: 2 of 2"): the call passed its 15 s, and the SDK's own retry held the guest until the
  request's 30 s ran out. A few minutes later it answered well again (the owner: keep 3.5). Now: one try, no retry
  (`GeminiConfig.httpOptions`, `HttpRetryOptions.attempts(1)`) — a slow Gemini sends the request on at 15 s; the log says
  "AI answered in N ms (model)" for every call — watch it. If it happens again: `GOOGLE_AI_MODEL=gemini-2.5-flash` on Railway
  (no deploy).
- **The song field's placeholder (2026-10-07, the owner):** "Wykonawca – Tytuł" / "Artist – Title" in the empty field — the format the
  suggestions fill in (`song-autocomplete.js`); the hint under it stays (a title, an artist or a line of the lyrics will do).
- **Live 2026-10-07 18:55 UTC (PR #19 / #20, `cc31cdf`), CI green.** The first start failed — the sleeping database refused the
  connection to Flyway — and Railway's restart came up in 3.8 s (the known cost of the sleep, "Na później"). Until 2026-10-08 morning
  no guest request since, so no "AI answered in" yet; no "CSP violation"; open PRs: only #7 and #2 (to close).

- **2026-10-08 (not deployed yet):**
  - **The hint under the song field** (the owner): it asks for the artist with the title or a line of the lyrics, "Nie znasz
    wykonawcy? …" — no check of the format (a guest may know only "sanah" or a line; the owner dropped a "do you know the artist?"
    question at sending for the same reason). The owner then edited the text.
  - **A cleared queue in the page's language (V25):** "🧹 Wyczyść kolejkę" no longer writes "Cleared by the DJ 🧹" over the AI's
    comment; `cleared_at` says it, the history shows "🧹 Wyczyszczona przez DJ-a" (amber as a skip, no "↩ Przywróć", still under
    "Rejected"). V25 turns the old notes into `cleared_at` (their request time; the AI's comment under them is lost). On a phone
    (the owner) the words go: the icon alone, ⏭ (with "↩ Przywróć") or 🧹, beside the votes as ▶ / ✖ (`history-phone`, seen red).
    On a wide screen (the owner: the long words stretched the column, the songs broke into four lines) one word in capitals as
    "ZAGRANE": "POMINIĘTE" / "WYCZYSZCZONE", the meaning in its title, "↩ Przywróć" on the same line (`history-wide-skip-and-clear`,
    seen red).
  - **The versioned scripts and styles kept for a year** (`VersionedAssetCacheFilter`: `public, max-age=31536000, immutable` on
    `/<commit>/js|css/...`; never on `dev` locally; everything else `no-store` as before). After the deploy: check in the HTTP log /
    the browser that `main.js` comes from the cache on the second load.
  - Tests: 372 unit, 30 database, all browser scenarios; the new ones seen red without the change.
- **Live 2026-10-08 10:09 UTC (PR #21 / #22, `0a39b7d`), CI green:** Flyway applied V25 (0.03 s), started in 4 s, no error;
  `/<commit>/js/guest-party.js` answers `Cache-Control: public, max-age=31536000, immutable` (Cloudflare: `HIT`), the pages `no-store`.

- **A guest's 👍 on the list (2026-10-08, the owner's idea and pick "A"; live 12:20 UTC, PR #23 / #24):** every waiting song on the guest page
  but the guest's own has "👍 N"; one vote per song, as many songs as the guest likes, a second tap takes it back. Then (the owner:
  too much — a song up to three times) **one list** "🔥 Prośby gości": the most votes first, the newest first among equals, 5 shown,
  the rest under "Pokaż pozostałe prośby (N)"; "Najwięcej głosów" and "Ostatnio wysłane" gone. **The screen does not jump** (the
  owner): after a 👍 only the songs' votes are put in place (`applyVotes`), the order changes with the next fetch; a song gone
  meanwhile stays dimmed; the note floats over the page. `POST /p/{code}/vote` (sent in the background by `guest-party.js`),
  `GuestVoteService` (memory by session id — a double tap counts once — and the session, so it outlives a
  deploy), `addGuestVote` / `removeGuestVote` (atomic, this party's waiting song only, never below 1), a per-network limit of its own
  (`guest.limit.votes-per-ip-party`, 300 / 10 min). No migration; the DJ's side unchanged (the "Głosy" column). A song with the
  guest's 👍 asked for again is theirs ("Ta piosenka już czeka w kolejce i ma Twój głos"). The privacy policy PL / EN names the 👍.
  **A busy night (the owner: "a co jak mamy 300 próśb?"):** the waiting queue was read 100 oldest at a time — the DJ's queue, the
  guests' list and the match of a song asked for again: with more than 100 waiting a vote became a second row. Now the 300 oldest
  (`findTop300…`, a party's day of requests; `SongRequestVotesIT`, 120 waiting, seen red with 100). The guests' folded rest is
  fetched only when unfolded (`GET /p/{code}/queue/more`) and has "Szukaj w prośbach" (no accents needed); a 👍 is answered with
  that song's row only (the fragment `voteAnswer`).
  Tests: 395 unit (`GuestVoteServiceTest`, `GuestVoteWebTest` — the CSRF token of the list's forms, 403 without it), 33 database
  (40 parallel votes; 120 waiting), 45 browser (`guest-vote` — nothing moves, measured with the page scrolled —,
  `guest-vote-song-gone`, `guest-more-and-search`; seen red with the whole list put back after a vote, and without the fetch of the
  rest). Then (the owner, on the computer): one pill of one size on every row — the guest's own a green one, "Twoja" beside the
  name (`guest-list-tidy`, seen red) —, a dark edge on a filled pill's 👍, the hint "👍 Oddaj głos na piosenkę — DJ widzi, czego
  chcecie najbardziej".
- **Live 2026-10-08 12:20 UTC (PR #23 / #24, `c1dfbe9`), CI green** (one flaky `CodeGeneratorTest` on the way: two of 100 random
  codes alike, ~1 in 12,000 runs — it now allows one pair). No migration. The start took 15 s, not 4: the sleeping database had
  closed the pool's connections right after Flyway, Spring Session waited twice 5 s for one ("Error while extracting database
  name") — the cost of the sleep again (Next / "Na później": switch the database's sleep off before customers).
- **"← Twój panel DJ-a" in the app (2026-10-08, the owner; live 13:05 UTC, PR #25 / #26, `a07d684`, CI green, started in 5.6 s):** a DJ testing their QR code with the app installed
  landed on the guest page inside it with no way back. Now the guest, result and "party ended" pages have the link, shown only in
  the installed app (`display-mode: standalone`; `guest-back-to-dashboard-in-the-app`, seen red). Not tried on a phone yet. The owner tried the first version locally: "działa dobrze". Not tried yet: a real
  phone.


## Next (the owner picks)

0. **"⚠ Sprawdź"** — built 2026-10-07 (Start here; live). Still a case for the Gemini comparison (item 5: does 3.5 Flash
   know "orła cień"?).

1. **Show the requests-only party to DJs** and tell what they said. Questions (2026-10-01): how many requests per wedding and how
   many they do not have; is searching a pain at all; should a guest hear "the DJ does not have it" at once.
   New questions (2026-10-06): during the party, do you look at the laptop, or should the phone vibrate; would you upload your
   library.
   Later (2026-10-07, the owner): **the DJ's login.** Only Google today — no passwords kept, no table of users (the DJ is the
   Google subject, `owner_id`), Google's own 2FA; it began with the YouTube API, gone since 2026-10-04. Ask: does any DJ lack a
   Google account or mind it; does it work on an iPhone from the Home Screen (Next, the iPhone test). If they ask, by effort:
   "Sign in with Apple" (an Apple Developer account, $99 a year), a sign-in link by e-mail (a mail service — Resend / Postmark —
   and a table of accounts), e-mail and password (not advised: hashing, resets, lockouts). Until then: Google only.
2. **The owner liked (2026-10-06):** the **"clear the history"** button — built 2026-10-07 (Start here) —, best paired with a
   **summary of the night** to download first (built 2026-10-08, Start here); the
   **DJ's branding on the guest page**: the profiles are built (2026-10-07, Start here); left: **the DJ's logo** (small,
   re-encoded on the server to PNG, kept in the database, served from our address — the CSP needs no change; the privacy policy
   to update). A candidate "premium" feature.
3. **Ideas, only if the DJs ask:** the DJ's library (an export from rekordbox / Serato / M3U) → "✓ you have it" beside each request,
   a lyric matched to the version the DJ has, "the DJ does not have it" at once, a list of what guests asked for and the DJ lacks;
   (notifications on the DJ's phone: built 2026-10-06, see Start here — a store app only if Web Push is not enough). Other
   ideas raised: a "do not play" list of the couple / the DJ checked before the AI; a link for the couple to fill in their
   must-play / do-not-play list and the vibe before the wedding; parties planned ahead (several, each with its own QR code).
   Decided against (2026-10-03): "play later" without rejecting; a sound / a count in the tab title on a new request.
4. **After go-live:** watch the log for "CSP violation" and "AI check" for a few days, then `CSP_ENFORCE=true` — first the Cloudflare
   Web Analytics beacon (the only report so far): switch it off in Cloudflare (Analytics → Web Analytics, the automatic setup for
   the site) or allow it in the CSP. Dependabot alerts and security updates: GitHub → the repository's Settings → "Advanced
   Security" (older UI: "Code security and analysis") → "Dependabot alerts" and "Dependabot security updates" → Enable.
5. **The Gemini comparison** — done 2026-10-07 (Start here): 3.5 Flash, level low, the rewritten prompt. `GeminiComparison` stays
   for the next model or prompt change. The key's limits (Tier 1): 1 000 requests a minute, 10 000 a day — ~30–60 weddings a day.
6. The review (`docs/history/review-2026-09-30.md`): only 2.3 (one instance) is left; 7.x done 2026-10-08 (the review moved to
   the history, the needless `hibernate.dialect` gone). The prompt (2026-10-08): "not knowing a song is no reason to make one up"
   — the owner tries it on production.

## Waiting for the owner (not code)

- **The notifications' keys:** run `VapidKeyGenerator` (`src/test/java/com/scan2play`, IntelliJ's green arrow) once, put its two lines
  `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` on Railway (and in IntelliJ's run configuration for a local try) — secrets, never in the
  repo or a chat; then switch the notifications on on an Android phone and on an iPhone (from the Home Screen) and send a request.

- **YouTube API key:** delete the key **"Klucz API 2"** in Google Cloud Console and the variable **`YOUTUBE_API_KEY`** in IntelliJ's
  run configuration (Railway: done 2026-10-06).
- **Spotify:** the Spotify app in the Developer Dashboard and the variables `SPOTIFY_*` and `SPOTIFY_TOKEN_KEY` in IntelliJ are no
  longer used — delete them (Railway: done 2026-10-06).
- **Dependabot** alerts and security updates: switch them on in GitHub (Next, item 4).
- **A budget alert** for the Gemini key's project in Google Cloud Billing (each request is paid now).
- **The OAuth client "Klient internetowy 1"** shows a warning in Google Cloud Console (2026-10-03): probably the disabled secret
  `****IgiS` — delete it (`****pfTe` is the one in use).
- **`SCAN2PLAY_GUEST_URL` in IntelliJ** holds the computer's address in the local network (the QR code's link): it changes with
  the network (2026-10-03: 192.168.68.54, the run configuration said 192.168.100.184). Change it in Run → Edit Configurations.
- **`origin/backup/local-main-2026-04`** holds two old local commits of `main`; never push `main` from it. Can be deleted once the
  `guest-url` design is decided.
- **`D:\Users`**: an empty directory tree left by a mistaken path in an earlier session; safe to delete by hand.
