# Ideas — the owner's list (2026-10-28 …)

The numbered items `SESSION_HANDOFF.md` and the code call "item N" / "Next N": what each is, what was decided, what is built and
what waits. Moved word for word from `SESSION_HANDOFF.md` on 2026-11-07, so the handoff stays short; read it when picking the next
piece of work or when a task names an item. A finished item stays here until its release is in `docs/history/`.


1. **A first contact with a new DJ** (the owner, 2026-10-28: "jak ktoś nowy zaloguje się, wysłać maila, że może do nas pisać") — (a) and
   (b) **released 2026-10-28 (#192, V48).** The owner's picks (2026-10-28): the card for new accounts only, above
   the queue until closed, closed by "✕" or "Napisz do nas", the list of new organisers on `/dj/stats`.
   (a) **The organiser's e-mail** (V48): `party_settings.owner_email` — Google's verified address, kept with the name when the organiser
   opens their panel (`DjDashboardController.keepOwner`; never the staff's), gone with the account (`AccountDeletionIT`); `created_at`
   (the parties of before: null — never "new"); the privacy policy PL / EN (sections 2, 3, 5, Google's Gmail in 6). `/dj/stats`: the
   organisers new in the last 7 days with their address (at most 50, `NewOrganisersIT`). The message, sent by hand from
   `scan2playcontact@gmail.com`: `docs/welcome-email.md` (PL, EN). The accounts of before fill the address at their next login.
   (b) **The welcome card** above the queue (`welcome_dismissed_at`; the accounts of before closed by the migration): "Pierwszy raz? Napisz
   do nas", "Napisz do nas" → "Pomoc i kontakt", "Jak to działa →"; closed on the server (`POST /dj/dashboard/welcome-dismiss`,
   `welcome.js`); browser scenarios `welcome-card-*`, seen red first, the one-line heading also with the runner's fonts.
   (c) **automatic e-mails (V64) — released 2026-11-05 (#230; the sender `contact@` #234, the welcome's words #236).** Details: `PROJECT_CONTEXT.md` 5.1 ("The automatic e-mails"), 7.4 (Brevo), 10 (V64). What was built, by the owner's picks:
   - **Brevo** API v3 behind `EmailSender` (`BrevoEmailSender`; a fake in the tests). From `Scan2Play <contact@scan2play.com.pl>`,
     Reply-To `scan2playcontact@gmail.com`. The key: Railway **`BREVO_API_KEY`** (already set; Brevo key "scan2play-production",
     Restricted, Transactional email = All; expires 4.11.2027 **or after 90 days unused** — rotate before). Brevo's "Authorised IPs"
     must stay off. A 401 / 403 is the ERROR line "E-MAIL KEY REFUSED …"; `/dj/stats` shows whether the key is set, the last send Brevo
     took and a later failure in capitals (`email_status`). A send never throws: the login and the pages never wait for it.
   - **The welcome**: once an account (`welcome_sent_at`, a claim — two logins at once send one), after the commit on the e-mails' own
     thread, the panel's language (`email_language`, kept at each panel's opening), the text of `docs/welcome-email.md` (templates
     `email/welcome_pl|en.html`), no marketing; a failed send is given back and the nightly job (4:35) retries the accounts of the last
     7 days. **The accounts of before V64 count as welcomed** (the migration) — nobody old is mailed on the deploy.
   - **The morning summary**: Pro (`PlanFeature.SUMMARY`), ≥ 5 guests' requests that evening (the DJ's additions not counted), at 10:00
     Polish time the next day (12:00 retries the failed), once an evening (`evening_summary.morning_sent_at`); the title and day, 3
     numbers (requests, played, votes), the top 3, a button to `/dj/summary`. The switch **"Poranne podsumowanie"** (the organiser's
     card in "Ustawienia imprezy", on by default) and **"Nie wysyłaj mi tego"** in the morning e-mail (`/email/stop/{token}`, no login: the
     link asks, its "Tak, wyłącz" switches off — mail filters open links —; also the mail program's one-click "Unsubscribe", RFC 8058,
     a `POST`). The welcome has neither (the review of 2026-11-05).
   - Privacy policy PL / EN: Brevo (Brevo SAS, France) as a processor, the welcome and the morning summary in sections 2, 3, 6;
     its data stored in the EU (OVH France, GCP Belgium), its sub-processors outside the EEA said — Cloudflare (USA) and the Brevo group
     in the USA and India (maintenance), on SCCs / the DPF — from Brevo's terms, annex 2 (https://www.brevo.com/legal/termsofuse/,
     read by the owner 2026-11-05; the optional ones — SMS, AI, Integry, Convrrt — are not used: only transactional e-mail by the API).
   - Tests: `AutomaticEmailsTest`, `BrevoEmailSenderTest`, `EmailStopControllerTest`, `AutomaticEmailsIT` (two logins at once, the jobs
     run twice and at once — red without the claims' `IS NULL`), the browser scenario `morning-summary-switch` (red before `forms.js`
     left the form to `cards-in-place.js`).
   - **Released 2026-11-05 (#230); set up the same day:** Brevo's "Authorized IPs" blocking for API keys **deactivated** (it refused
     Railway's address, 401 "unrecognised IP address" — logged as "E-MAIL KEY REFUSED", the key itself was fine); the sender changed to
     **`contact@scan2play.com.pl`** (`SCAN2PLAY_MAIL_FROM`, default) — it must be a **verified sender** in Brevo: the first four
     welcomes were taken (201) and then dropped by Brevo ("sender kontakt@… is not valid", only in Brevo's log). `contact@` is
     forwarded to the Gmail address by Cloudflare Email Routing (the verification code came that way). Those four test accounts count
     as welcomed. Proposed, not built: Brevo's webhook of errors / bounces, so such a drop shows on `/dj/stats` and the welcome is
     tried again.
   - **After the release:** (seen 2026-11-05: Flyway's "now at version v64", the start's "E-mails on (Brevo), from contact@…") the
     first welcome on `/dj/stats` ("last sent") and in Brevo's log (Transactional → Logs); open one in Gmail — "Show original": DKIM and DMARC `PASS`
     for `scan2play.com.pl`. After a few weeks of clean sending, `_dmarc` `p=none` → `quarantine`. Brevo's log of sent e-mails: 1 month,
     no previews of their content (set by the owner 2026-11-05 in Brevo's "Email retention rules", all senders — the default was
     forever; the privacy policy, sections 5 and 6, says so). Brevo's data processing agreement needs no step: it is part of Brevo's Terms of Service, accepted
     with the account (Brevo's help article "Where can I find the Data Processing Agreement (DPA)?", asked 2026-11-05 — there is no
     acceptance page in the app).
   - Not done (proposed): a test e-mail button for the site's owner on `/dj/stats`; the welcome of an account whose address Google did
     not verify (it never has one, so it gets no e-mail).
2. **"Zmień kod imprezy"** (V49; the owner, 2026-10-28: "gość, który ma zawsze jego kod QR i spamuje imprezę DJ-a") — **released
   2026-10-28 (#192, V49).** The owner's picks: a new code (not a ban by address — a venue's Wi-Fi and a mobile network share one), the organiser
   only, last card of "Ustawienia imprezy", asks "Zmienić kod imprezy? Stare plakaty i karty przestaną działać.". Everything of the party
   moves (FKs ON UPDATE CASCADE, requests and feedback by UPDATE), the old code is retired for good (`party_code_retired`), the TV
   screen opened again from the panel. Not done (proposed): closing a party by itself after hours without requests.
3. **The QR print's editor, enriched** (the owner, 2026-10-29: "chcę wzbogacić edytor plakatów i kart") — the ideas, the competitors and
   the mock-ups: the artifact "Edytor wydruku: co dalej" (claude.ai, private). The owner's picks: first **the table tent** (one A4
   sheet, two A5 faces alike, the upper one upside down) and **the number of tables** saved with the party (V50, "Liczba stołów", on the
   cards and the tents, never on the poster), "Numer pierwszego stołu" (never saved) — **released 2026-10-29 (#197,
   V50)** (still to do: print a tent on a home printer and fold it). Then (the owner, 2026-10-29: "zrób następne
   rzeczy z listy") **"Nagłówek na okazję" and "Do internetu" (a story / post PNG)** — built on
   `claude/bold-bell-w3epu5` again, no migration. The theme "Oszczędny" was not built: "Klasyczny" is already the white sheet with no
   fills (an ink note under the club theme was built and taken off again: the owner — everyone knows a dark sheet takes ink). New looks (themes, fonts, ornaments) for every plan; photos on the poster
   later, as Pro. **Released with #197.** Then (the owner, 2026-10-29: "zrób na razie 1") **the print's own fonts** ("S2P Sans" = Source
   Sans 3, "S2P Serif" = Playfair Display, `static/fonts`, OFL, `/fonts/**` public and kept a year) **and the DJ's heading and line
   fitted**, and (the owner: "dodaj wybór kroju") **"Krój nagłówka"** (V51: the theme's, sans, serif, script — Dancing Script) —
   built on `claude/bold-bell-w3epu5` (the longest words were cut on a card and a tent before), released with #197 with V52 and V53, and **four more themes** (V52: Rustykalny, Gala, Neon, Konfetti; the owner picked four of seven mock-ups — Morski, Retro,
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
   **released 2026-10-31 (#214, V56 — it also adds KIOSK to the co-organisers' permissions).** The owner's picks: Pro; with votes; whoever has the new permission KIOSK
   makes the link; the most wanted beside the form, as the TV screen. A guest's turn is a session ("Następny gość", or by itself after a
   minute without a touch); the kiosk's own limits (3 requests a minute, 20 👍 per 10 min) in place of the address's; a request marked
   "🖥 kiosk" in the queue. After the review of 2026-10-30 (three fixes, each with a test seen red first): "Następny gość" keeps a
   session with a login (it logged the DJ out), a refused request ends its turn by itself too (the next guest found the last one's
   words and dedication), a lost 👍 goes to the kiosk's address instead of reloading (on an answer that sent the request again).
   Then the review's other points: the list not put in place under a finger (5 s after a touch, 15 s after a 👍's note, an answer
   asked for before a 👍 dropped, the focus kept), "Następny gość" sent in the background (the Wi-Fi down: no browser error page), a
   party closed meanwhile sends the kiosk to its page, the kiosk's limit of 👍 said as the kiosk's, "Wyłącz" without Pro, the request's
   way in `GuestRequestService` (no controller in a controller), "🖥 kiosk" in the history and "Z kiosku" in the summary and a column of the CSV,
   a guard test that V56's words are the stored whole set. Then the layout (the owner's screenshot): one screen, the page never
   scrolls — the list scrolls in its card, "Następny gość" under the form (ten songs had pushed it under the screen of every tablet).
   Then the second review (2026-10-31): the list keeps its place in its card (a refresh or a 👍 threw it back to the top) and fades
   while more is below; a fetch with no answer is given up after 10 s (a hung "Następny gość" held the kiosk for good); the kiosk loads
   its page only once that page answers (the reload every 10 minutes could leave it on the browser's error page); no 👍 while
   "Następny gość" is on its way; a phone scrolls as a page (on 360 × 640 the list had no height); a warning when the kiosk is opened
   with a login (the DJ's panel on a tablet at the bar). Then the review's rest: the secret out of the tablet's address bar (a cookie
   for `/k`, the page shows `/k`), the kiosk's limit of 👍 says the real wait, the same 👍's note read out twice, "Usuń stary link
   kiosku" with a line why on the card without Pro.
   Details: `PROJECT_CONTEXT.md` 5.1. To try on a real tablet: an iPad in Guided Access and an Android tablet
   with the screen pinned, the native keyboard over the form, the suggestions.

7. **The library's details (V57) — variant B of the analysis "Tryb „tylko z biblioteki”"** (the artifact
   https://claude.ai/artifact/7nEtKmgJkVMUie3K5dBT2u; the owner, 2026-11-04: "zróbmy na razie B") — **released
   2026-11-04 (#221, V57 and V58; V60 with #224)** (still to do: upload a rekordbox collection again and see "128 BPM · 5A" beside "✓"
   in the queue). Each song is kept as the file writes it beside
   its key (artist, title, from rekordbox the tempo, key, genre, year; never a path, notes, rating or play count); the queue shows the
   tempo and the key beside "✓" / "?"; the libraries of before stay (their marks as they were; a rekordbox one is asked for again on
   its card). Two causes of a false "✗" fixed with it: a rekordbox track never tagged (no artist, "Wilki - Baśka" for its name) is read
   by its name, both ways round; an M3U's file name loses a track's number ("01 - ") and its underscores. The privacy policy PL / EN
   says what is kept now. The owner tried it locally (2026-11-04, a screenshot): "✓ Masz 128 BPM · 5A" in the queue.
   **Then D0 (V58; the owner, 2026-11-04: "zrób D0")** — on the same branch: the guests' suggestions (iTunes's, as before) with the
   songs the DJ has first, "✓ DJ ma" — the party page and the kiosk ask `GET /p/{code}/library/has` for the suggestions shown (at
   most 10, "✓" only, waited for at most 600 ms); nothing is refused, free text stays. "Podpowiedzi gości" in the library's card, on
   by default (`party_settings.library_hints`); the privacy policy PL / EN says it. Chosen over the full D because Railway's
   PostgreSQL volume is 500 MB (121 MB used, 2026-11-04): a trigram index of whole libraries would fill it after a dozen large ones.
   Next, only if DJs ask: the full D (the library searched — `pg_trgm` + `btree_gin`, which Railway's image `postgres-ssl:18` on
   `postgres:18-trixie` should have: check with the query of the analysis; a bigger volume), then "tylko z biblioteki" (✗ refused,
   "?" and ⭐ through, the nearest songs offered). Open: a real rekordbox export and an M3U of VirtualDJ / Serato to check the fields
   (the fixture is built by hand); whether the rating should filter anything. To try: the test files the owner was given
   (`biblioteka-test-rekordbox.xml`, `biblioteka-test.m3u8`), the guest page typing "wilki".
   **Then V60** (the owner, 2026-11-04: "jeśli 3 jest łatwe … możesz zrobić"): one word's two spellings make one key — "Livin'" /
   "Living", "Stayin'" / "Staying", "Mambo No 5" / "Number 5" were "✗" —, the keys saved before changed the same way by the migration.
   Still apart (not done: the stored keys have no spaces, so such a rule cannot be applied to them): "&" / "and" in a title ("Rock &
   Roll"). The owner has no library of their own: a Spotify playlist exported with Exportify (CSV) can be made an M3U8 to try the
   matching on real titles; rekordbox's own format only from rekordbox (free) with a few files.

8. **"Motyw dla gości" (V59) — variant A′ of the analysis** (the artifact https://claude.ai/artifact/T8uNK1tcQm4nynE53DK1uU; the
   owner, 2026-11-04: "zróbmy A′, Free") — **released 2026-11-04 (#224, V59; #226
   the ghost).** The owner's picks: Free; whoever has VIBE sets it (the staff too); the New Year's theme
   made a universal one, a birthday too ("Konfetti"); "Świąteczny" for the winter one; "Straszny" for Halloween, scarier (the analysis's
   orange kept, a near-black page, a violet mist above and a dark red one below, a dark drip with a small ghost, a bat and a spider, the DJ's name in
   Nosifer's dripping capitals); the TV in the same change; the print's choice apart, its editor saying the guests' theme ("Strona
   gościa: … · zmień"); the ornament's band (56 px above the card, the card 36 px lower; none in the kiosk) and our logo's cyan "2" kept —
   left to me, as recommended. Details: `PROJECT_CONTEXT.md` 5.1, 6.7. To try on real phones: each theme in a dark room (the contrast is
   measured, the feel is not), Nosifer's "Gra: …" with a long DJ name, the TV at the venue; a photo of the TV in "Straszny" for Instagram.
   Next, only if DJs ask: the DJ's own colours (Pro, on the same tokens: a contrast fix and the statuses' hues kept away), more themes
   (each a block of tokens and an SVG; `GuestThemesCssTest` checks it), a summer one once a hue far from the statuses and the cyan is found.
9. **"Podsumowanie wieczoru" — v1 and v2 of the analysis** (the artifact https://claude.ai/artifact/C9kWpRuZ8h5pbnbPDS6Fvx; the owner,
   2026-11-04: "Zrób v1 w całości, potem zaczniemy v2", the open questions left to me; then "zrób razem z v2" for the bar's width on a
   computer) — **released 2026-11-05 (#228, V61, V62 and V63; v3 and the
   refusals before the AI with it).** **v2:** an evening lasts 13 months — instead of a snapshot of the counts
   (planned), the statement that deletes requests ("Wyczyść historię", the 30 days' purge) copies what the summary needs of each into
   `evening_song` (`KEEP_FOR_THE_SUMMARY`; no guest's words, no name of a request the AI rejected): a clear in the middle of a party
   loses nothing and no 06:30 job is needed; the page reads both tables. The evenings' list up to 52, by month; the DJ's title of the
   evening and the hosts' read-only link `/s/{token}` (30 days, one an evening, "Nowy link", "Wyłącz link", `noindex`, no referrer) in
   the panel "Dla gospodarzy" of the hosts' version (`evening_summary`); the bar one compact row on a computer (the owner's screenshot:
   the list half the screen wide); the privacy policy's section 5 and "Wyczyść historię"'s question say the summary keeps the songs.
   Tests: `EveningSummaryIT` (seen red without the copy), `AccountDeletionIT`, `PartyCodeChangeIT`, browser scenarios
   `summary-hosts-panel-phone`, `summary-shared-page`. Kept for Free too (the copy does not ask the plan), read with Pro. **v1:** (no migration). The numbers: a song asked for again is one line (`EveningSummaryService.Song`, by
   `LibraryKey`) — it was twice in the most wanted and in "Chcieli, a nie usłyszeli" though it played; four counts (played, the guests'
   votes, the songs wanted, rejected by the AI), the rest in a line; the evenings' list with the votes (`findEvenings`,
   `SongRequestRepositoryIT`); the half-hours counted in time (the night the clocks go back). The look: the bar in three rows on a phone
   (the counts at ~300 px, were ~560), bars of an SVG in the accent, artists of two songs or more only, what played folded on a phone and
   in two columns on paper. "Dla mnie / Dla gospodarzy" (`view=hosts`): the hosts' version without the DJ's own — one A4 page for ~40
   played. "Kopiuj listę" of the library's gaps; the CSV with four columns at the end. My picks: the switch on the same page; the hosts'
   version without "Chcieli, a nie usłyszeli"; no title from the poster (its heading is a call like "Piosenka dla Pary", and today's, not
   that evening's) — the title is the DJ's, typed (v2). Details: `PROJECT_CONTEXT.md` 5.1. **v3** (the owner: "ok, rób v3"; after v2
   on the same branch, **V62**): why the AI rejected — saved with the request from what the code knows (on the recently played list
   the AI was given: "już grała"; else "nie pasowała"; the prompt unchanged), counted under the rejections, "Odrzucone przez AI" listed,
   a CSV column; "Ostatnie wieczory" (up to 8 around the shown one, from the evenings' query); "Do internetu" in the hosts' panel — a
   story or a post drawn in the page (`summary-social.js`, as the poster's), shared or saved. **Later:** the morning e-mail with the
   summary's link waits for item 1c's sender. **Then** (the owner: "prośby odrzucone jeszcze przed AI — możemy zrobić"; **V63**): the
   requests refused before the AI (the hosts' "nie grać" list, a song the DJ skipped lately, a mood or a message) counted per evening and
   reason in `evening_refusal` — no song, no guest's words —, one line "Zatrzymane, zanim zapytano AI" in the DJ's version.
10. **The guide's videos on "Jak działa Scan2Play" / "How Scan2Play works" — waiting** (the owner, 2026-11-12: not now). Five videos a
   language (setting up, the QR print, the party, the phone, after it; ~27 MB of mp4) with their `<figure>`s, CSS and
   `GuidePageRenderTest.assertTheVideos` are on the branch `claude/epic-noether-ikboeo`, PR #259 kept as a draft — not merged, so
   nothing of them is in `main`'s history; once merged, every recording stays in it for good (mp4 does not delta). The review of
   2026-11-12 found in them: the cursor and the highlight left on the next row after "Pomiń" / "Zagrane" / "Przywróć" (video 3 at
   ~34 s looks like skipping Queen, while Smash Mouth is skipped), the undo bar gone before "Cofnij" is clicked (video 3, ~30 s), the
   16:9 captions unreadable on a phone (~6 px at 390 px), captions too short or over what they name, one-frame black flashes between
   pages, test data ("HARN1", "DJ Koko", dates of the history and the summary that disagree), "Puściłeś" / "pominąłeś". **Back to it
   when:** the recording script is in the repo (next to `src/test/browser/screenshots.py`) — a changed screen means recording again;
   those faults are fixed; the captions are readable on a phone (zoom on the place of the action, or upright videos); and where the
   files live is decided — the repo (a version in the file name, a purge of Cloudflare after a change) or Cloudflare R2 on its own
   host (`media-src` in the CSP, a sentence in the privacy policy). Meanwhile the guide's text alone covers what the videos show.
11. **A guest's message to the DJ, and the guest's contact — variants B0 and A0 of the analysis "Wiadomość gościa do DJ-a"** (the
   artifact https://claude.ai/artifact/PccjYWtKHVmc8zWtSwLtq8; the owner, 2026-11-12: "zrób B0 i A0, Pro, e-mail i telefon") — **on
   `claude/blissful-fermi-izymid`, not released (V67).** The analysis: of the cases (wishes, thanks, "I want to book this DJ", a remark
   during the set, a problem) only the lead is worth a feature, and it needs no guest's data with us. **B0:** the DJ's e-mail and phone
   for bookings (Pro with the profiles, `PlanFeature.PROFILES`; the organiser's alone; the card "Profile, kontakt i napiwki") in
   "Zarezerwuj tego DJ-a" — under an accepted request and on "party ended", a `mailto:` with the subject filled in (the evening's date
   under a request) and a `tel:`; the guest writes or calls outside Scan2Play, which sees nothing; the box shows with a contact alone
   too (before: only with a profile). Not on the party page, the kiosk or the TV. Cloudflare's "Email Address Obfuscation" is off
   (checked 2026-11-12: the addresses on `/privacy` are plain) — switched on, it would rewrite the `mailto:` links. The privacy policy
   PL / EN (the DJ's contact, public; the message goes outside Scan2Play; kept until changed), the terms' Pro list, `/pro`, the guide.
   **A0:** the refusal "a mood or a message, not a song" says where the words can go — "Chcesz coś przekazać DJ-owi? Wpisz piosenkę, a
   swoje słowa dopisz w „💌 Dodaj dedykację”." (only while dedications are on; the kiosk too). **Later, only if DJs ask:** A′ — "Podziękuj
   DJ-owi" after the party (no contact, checked by the AI, its own limit outside the Free plan's 150, read in the evening summary).
   **Only on conditions:** B — a form with the guest's contact (the DJ the controller, Scan2Play the processor, art. 6(1)(b), "18+",
   30 days, the owner only; no log, e-mail or AI) — once B0 shows the demand and the privacy policy names the controller, the legal
   bases and the guests' rights (missing today, whatever this idea becomes). **Rejected:** a message during the party (A), the DJ's
   answer on the guest's page (C), a chat (D) — they distract the DJ at work, need moderation, and C would not outlive the guest's
   30-minute session.
