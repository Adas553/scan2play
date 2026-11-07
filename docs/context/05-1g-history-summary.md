> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**The history** (`PlayHistoryService`): the party's requests that played or were rejected, by the DJ's moment —
`COALESCE(played_at, skipped_at, cleared_at, requested_at)`, the time it shows too (a skip was placed by its request time until
2026-10-12) —, newest first, one bounded query (`limit + 1`). The History tab (in place of the queue — the page then is the page
"Historia" as the standalone one: `body.s2p-history-shown` hides "Impreza i kod QR" and the settings, the Panel tab brings them back as
they were; `history-tab-like-its-page`, 2026-10-29) and the standalone page `/dj/history-view`:
filters All / Played / Rejected applied by the server, "Show more" (50 at a time, ≤ 300); both keep the search text and the sort by a
column (`captureListState` / `restoreListState` in the tab; on the standalone page, which loads itself again, through
`sessionStorage` once). A heading row where a new day starts, Polish time (`tr[data-day-heading]`: "dziś — sobota, 03.10",
"wczoraj — …", "wtorek, 29.09"; `Times.dayKey` / `weekday`) — the history keeps 30 days, so the parties of a month share it.
**On a wide screen** (the design review of 2026-10-10, variant C, https://claude.ai/artifact/HJ7JdqVoXRpBGdD7h3d2Wv) the columns are
ID, the time, the song, the votes and "Decyzja" (was "Werdykt AI" over the DJ's own decisions): under the song **one muted line**, the
guest's words · the AI's comment, cut with "…", the whole of it in its title (`.s2p-history-sub`; the comment's column was the widest
and the loudest, the songs broke inside words — "Małomiasteczk / owy" —, four entries fitted the list's 540 px, now seven); no column
"Klimat" ("Dowolny" in every row) — the vibe the AI judged by is the decision's title ("Klimat: Wesele"); the decision a coloured dot
and one quiet word in capitals (eight colours of text competed in a row). Browser scenario `history-wide-calm`.
On a phone (narrower than 768 px) every entry is **one line** (2026-10-10, `cards.js`; 52 px, was a card of 191 px): the song (cut with
"…"), "⚠", the votes, the decision ▶ / ⏭ / ✖ / 🧹 and, for a skip or a played one, "↩" (44 × 44 — the one thing done in the history, so still one tap;
its words kept for a screen reader). A tap on the line (or its "▾") unfolds the card: the whole song across it, "⚠ Sprawdź", the guest's
words, "Podejrzyj", the number, "↩ Przywróć" in words and the AI's whole comment; it stays unfolded after "Pokaż więcej", a filter or
a skip put back (`tabs.js` → `reopenCards`). The column sort stays as a row of buttons.
`list-tools.js`: the search hides a heading with no row left under it; a sort by a column hides them all (`.s2p-sorted`), undoing it
puts the server's order back. On the standalone page (`/dj/history-view?party=`) a filter and "Pokaż więcej" load the page again with
the party it shows (its `#partyCode`; the review of 2026-10-14 W7 — without it the server showed the person's default party under this
one's heading). **"Wyczyść historię"** (shown while the history is not empty; asks first — `data-confirm`) sends
`POST /dj/dashboard/clear-history` (`DjService.clearHistory`, under the party's lock "S2PR"): the party's played and rejected requests
are **deleted**, the guests' words with them — never a waiting one, and never one the DJ skipped within the last 2 hours (it keeps its
song out of the queue and stays in the history until that ends). The AI's duplicate rule reads the played songs, so after it the AI no
longer knows what played. The evening summary keeps their songs (V61, `evening_song`, copied by the same statement — below). The
History tab sends it in the background and loads itself again (`s2p:history-changed`).

**"Podsumowanie wieczoru"** (beside "Wyczyść historię", a new tab; its question points to it — on a phone both under one "⋯" beside the
history's filters, the search the whole width under them: two rows above the first entry, not three, 2026-10-28;
`.s2p-history-more`, `history-actions-menu-phone`, `history-actions-row-wide`): `/dj/summary`
(`DjSummaryController`, `EveningSummaryService`, `summary.html`) — one evening of the DJ's own party (no party code asked for) on a
white page to print or save as PDF ("🖨 Drukuj / PDF", `js/summary.js`, `css/summary.css`): an **evening runs from 6:00 to 6:00 Polish
time** (`EVENING_STARTS`; a wedding past midnight is one evening); the evenings with requests to pick (≤ 52, latest first, grouped by
month — `<optgroup>` "listopad 2026" —, with their guests' votes, "sobota, 03.10 · 👍 34" — `SongRequestRepository.findEvenings`, native
SQL with the same 6 hours over both tables below, the votes as the page counts them; `SongRequestRepositoryIT`, `EveningSummaryIT`).
**An evening lasts 13 months** (V61): what the summary needs of a request is copied into `evening_song` by the very statement that deletes
it — "Wyczyść historię" (`deleteHistory`) and the 30 days' purge (`deleteRequestedBefore`), `WITH gone AS (DELETE … RETURNING *)` +
`SongRequestRepository.KEEP_FOR_THE_SUMMARY`: the song's name (none for a request the AI rejected — that is what the guest typed), the
times, the status, votes, tips, the count of dedications, added by the DJ, from the kiosk; never a guest's words, an AI comment, a
dedication's text or who asked. So clearing the history, even in the middle of a party, loses nothing of the summary, and no job is
needed. `EveningSummaryService.requests` reads an evening from both tables (`findSongsBetween`, ≤ 2000; a request is in one of them) and
turns a kept song back into the request the summary counts (`fromKept`). The nightly purge (`SongRequestRetentionService`, 04:45) deletes
the kept songs and the evenings' rows 13 months after the evening (`SUMMARY_MONTHS`; the evening as a whole — its songs after midnight
with it, the cut at its 6:00); the party's row takes both along (the account's
deletion, `AccountDeletionIT`) and a new party code moves them (`ON UPDATE CASCADE`, `PartyCodeChangeIT`). The privacy policy (PL / EN,
section 5) says so. **A song is counted once:** one asked for again — after it played (out of the AI's duplicate
window), after "Wyczyść kolejkę", two hours after a skip — is a row of its own, and the lists put its rows together by `LibraryKey`
(`EveningSummaryService.Song`: the votes and tips added, how often it played, its best end — played, waiting, skipped, cleared), so a song
that played and was asked for again is one line of the most wanted and never "wanted, not heard". **Two versions** (the bar's "Dla mnie" /
"Dla gospodarzy", `view=hosts`). **The DJ's:** four counts — played, the guests' votes (requests and 👍 of what the AI let through, the
DJ's own first vote of a song they added left out), the songs wanted (let through, each once), rejected by the AI —, the rest in one line
when there are some (skipped, cleared, waiting, from the kiosk, added by the DJ, 💸 tips, 💌 dedications — a count, never their words), how
long the played requests waited (from the request to "played": the average and the longest), the 10 most wanted (votes, then the earlier;
"✓ ×2" when it played twice), "Chcieli, a nie usłyszeli" (the wanted ones that never played, with their status), "Nie masz w bibliotece"
(with a library, V42) and its "Kopiuj listę" (the songs one a line — `dj-nav.js`'s "Kopiuj", shown by `summary.js`), "Wykonawcy kilku
piosenek" (the artist of the AI's "Artist - Title", grouped by `SongNames.comparable`, only those of two songs or more), "Nowe prośby co
pół godziny" (each song's first request — the later votes and 👍 have no time of their own; bars of an SVG — attributes, not inline
styles, for the CSP —, the busiest in the accent, the quiet half-hours between too; counted in time from 6:00, so the hour the clocks go
back is two bars of "02:00"), what played in the order it played (folded on a phone, unfolded to print, two columns on paper); **why
the AI rejected** (V62): "już grały: 4 · nie pasowały: 5" under its count, and "Odrzucone przez AI" — the songs it rejected each once,
how many asked, 🔁 / ✗ (a kept one has no name, so it is not there); **"Zatrzymane, zanim zapytano AI"** (V63, one line, `#refusals`):
the requests refused before the AI judged a song — never saved as requests — counted by reason in `evening_refusal` (`EveningRefusals`,
one upsert a refusal off a transaction of its own, a failure only logged, the demo none): a song on the hosts' "nie grać" list, one the
DJ skipped within two hours, a mood or a message ("nie piosenka"); no song and no word of the guest's; **"Ostatnie wieczory"** (screen only, `#compare`): the shown evening
among up to 8 neighbours (`EveningSummaryService.compared`) — the votes, the played, the rejected, the first and last request, each row
a link — from the evenings' own query (no read more). **The
hosts'** (the couple, the client): three big numbers (the votes, the songs played on request, the busiest half-hour), the first request and
the last song played, the 10 most wanted (votes only), "Kiedy goście prosili", what played — nothing of the AI's rejections, the skipped
and cleared, the library, the tips, the dedications; one A4 page for about forty played songs (browser scenario `summary-hosts-one-a4`).
**In the hosts' version, the panel "Dla gospodarzy"** (not printed, `#hostsPanel`): "Tytuł wieczoru" (≤ 60, one line; `POST
/dj/summary/title`; the page's heading then, "Podsumowanie wieczoru" small above it — the DJ's version too) and **the hosts' read-only
link** — "Utwórz link dla gospodarzy" (`POST /dj/summary/share`: a secret of 128 bits, `evening_summary.share_token`, works 30 days,
`SHARE_DAYS`), then the address with "Kopiuj" (`dj-nav.js`), "Działa do 24.11.", "Nowy link" (the old one stops) and "Wyłącz link"
(asks first; `POST /dj/summary/share/off`); **"Do internetu"** (`js/summary-social.js`, the hosts' version only): a story 1080 × 1920
or a post 1080 × 1350 drawn on a canvas in the page from what the page shows — the day, the title, the DJ, the votes and the songs
played, the top 5, the DJ's logo on a white badge, "Działa na Scan2Play" —, dark with the cyan glows of every Scan2Play picture, a
story's words between Instagram's bars (250 px), shrunk to fit; shared (a phone's share sheet) or saved as
`scan2play-<day>-story.png`; nothing sent, no guest's words (`summary-social-pictures`, `summary-social-longest`). Title and link: one row of `evening_summary` an evening (upserts on `(party_code, evening)`),
only for an evening the party has (else 404), with the permission SUMMARY and Pro. **`/s/{token}`** (`SharedSummaryController`, public
GET): the hosts' version alone — no bar, no panel, no `dj-nav.js` —, `X-Robots-Tag: noindex` (`NoIndexFilter`) and a robots meta,
`Referrer-Policy: no-referrer`, `Cache-Control: no-store`; an unknown, old or switched-off link, or a party whose plan has no summary any
more, is a 404 (`summary-shared-page`, `summary-hosts-panel-phone`). The DJ's logo beside the title, our logo at the foot; the bar (not
printed): the evening (300 px on a computer), the version, "Drukuj / PDF", and under "⋯" the CSV, the way back and the hint — one compact
row on a computer, three on a phone (`summary-phone-compact`, `summary-wide-one-row`, `summary-copy-library`). `/dj/summary/csv`: the same evening as a CSV
file (`scan2play-<day>.csv`; BOM and ";" for Excel, the DJ's language, every value quoted, and one that starts with `= + - @` or a tab
gets an apostrophe — a guest's text is never a formula); a row a request, after the columns of before how long a played one waited, its
artist and title apart, the count of its dedications and why the AI rejected it (V62), then "W bibliotece" with a library.

