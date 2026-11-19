> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**"Pytanie do gości"** (V74, Pro, the permission POLLS; `docs/ideas.md` item 15): the DJ asks the room a question of their own —
"Disco polo: tak czy nie?", "Wolniej czy szybciej?", "Kto pierwszy na parkiet?" — with two to four answers; the guests answer on their
phones, one vote each, and the DJ sees the results live, the TV screen too. Nothing of the guest is kept but a token and the answer.

**A question** (`party_poll`, `PollService.Poll`): `question` (≤ 120, one line, `PollService.tidyQuestion`), `answers` (`varchar(40)[]`,
2 … 4, the empty ones and repeats — by case — left out, `tidyAnswers`; none typed is "Tak" / "Nie" in the DJ's language,
`polls.yes` / `polls.no`), `vote_count`, `opened_at` / `closed_at` — the state: neither = saved for later ("Zapisz na później"),
`opened_at` alone = asked now, both = ended. `results_hidden` (V75, picked when it is made: "Od razu po ich głosie" — the default — or
"Dopiero gdy je pokażę", and switched on each question asked or ended: "👀 Pokaż wyniki gościom" / "Ukryj wyniki przed gośćmi",
`POST /dj/polls/{id}/results`, any plan) and `revealed_at` (when the DJ last showed them). **One question of a party is asked at a time** (the partial unique index
`uk_party_poll_asked`): "Zadaj teraz" ends the one asked before in the same transaction, under the party row's lock (two "Zadaj" at
once leave one asked, `PollServiceIT`); an ended one asked again ("Zadaj ponownie") keeps its votes and takes more. A party keeps at
most `MAX_POLLS` = 30 (counted under the same lock); deleted by "Usuń" (any plan), with the party, 90 days after it was made
(`PollService.purge`, daily 04:55 Polish time); its code follows "Zmień kod imprezy" (ON UPDATE CASCADE).

**A vote** (`party_poll_vote`, PK `(poll_id, voter)`): `voter` is the first 128 bits of the SHA-256 of the guest's session id
(`PollService.voterOf`) — not the id itself, nothing written to the session (two first votes at once would each write a token of
their own), and never the address (a hall shares the venue's Wi-Fi). `choice` the answer's index. Under the question's row lock
(`SELECT … FOR UPDATE`): the question still asked, then the voter's row changed (`CHANGED`) or — below `MAX_VOTES` = 5 000 — a new one
and `vote_count + 1` (`VOTED`), else `FULL`; a saved or ended question `NOT_ASKED`, an answer it has not `INVALID`. A double tap, or
fifty guests at once, are counted right (`PollServiceIT`). The address's limit of a 👍 holds too (`GuestRequestLimiter.tryAcquireVote`).
A guest who drops the cookie is a new voter: a hint for the DJ, as a 👍.

**The results** (`PollService.results`): one `GROUP BY choice` of the question's votes, in a Caffeine cache by the question's id for
2 s (every guest who voted, the DJ's page every 3 s and the TV every 10 s ask for them), dropped after a vote's commit (`AfterCommit`);
the share in whole percents, the answers with the most votes "lead" (none at 0).

**The guest's page** (`GuestPollController`, `PollView`, `fragments/guest-poll.html`, `js/guest-poll.js`): the question asked — or
the one that ended, or whose results the DJ showed, in the last 15 minutes (`SHOWN_AFTER_END`, from the later of the two), with its results, no form — is a card above the request form, a button
for each answer; answered, the card shows the results (bars: `<progress>`, no inline style under the CSP), "✓ Twoja" at the guest's
answer and "Zmień odpowiedź:" with the others. **Results kept for later** (V75): no number of an answer is in the page at all (`PollView`
leaves the results out of the model — hidden by the look, the page's source would tell them): the guest's own answer, "Wyniki pokaże
DJ.", how many voted (`vote_count`) and "↻ Sprawdź wyniki" (the card fetched at once). On the page "DJ nie przyjmuje teraz próśb" (`party_ended.html`) too: a DJ who closed
the requests asks the room alone. A question to answer makes the session its form's CSRF token needs before the page is drawn
(`PollView.addForGuest`; the landing page's trap of 2026-10-18 — `GuestPollWebTest`); nothing asked makes none. The answer goes in
the background (`POST /p/{code}/poll/{id}`, `X-Requested-With: fetch`) and comes back as the card — the typed song stays; a double
tap sends one; an expired form goes back to the party's page (`GuestFormExpiredHandler`). The card is fetched again
(`GET /p/{code}/poll`) when the guest comes back to the page — no timer, as the list. Without the plan's `POLLS` no card and no
script. The kiosk has none (one device, many guests).

**The TV** (`tv.html`, `TvScreenController`): while a question is asked, and 15 minutes after, the list's place shows it — the
question, "Zeskanuj kod i odpowiedz na telefonie", a bar for each answer with its share, the votes — in the screen's languages; with the results kept for later
"Wyniki pokaże DJ" and the votes coming in, no bars — the moment the DJ shows them is here. The words of the question are the DJ's.

**The DJ's page** `/dj/polls` (`DjPollsController`, `polls.html`, `js/polls.js`; POLLS; reached from the dashboard's QR card
"🗳 Pytanie do gości" and the settings' card): the form (the question, four answer fields — "Tak" / "Nie" as placeholders —,
"Zadaj teraz" / "Zapisz na później"; Pro), the party's questions the newest first — the one asked with its results fetched again
every 3 s while the page is shown (`GET /dj/polls/{id}/results`, the fragment `polls :: pollResults`), "Zakończ pytanie", "📺 Pokaż
na TV"; a saved one "Zadaj teraz", an ended one its results and "Zadaj ponownie"; "Usuń" asks first. Without Pro the offer; questions
made before may be ended and deleted. Not in the evening summary (yet).
