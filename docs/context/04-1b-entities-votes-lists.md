> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 4.1 (cont.). Keep it to what is true now.

**Votes** (`SongRequestCommandService.saveOrVote`, V15): an accepted request for a song that already waits in the party's queue (the
same name by `util/SongNames.comparable` — case, accents, punctuation ignored) is not a row of its own: the waiting song gets
`votes + 1` (a conditional `UPDATE … WHERE decision = 'accepted'`; when the DJ played it meanwhile, the request is a new row) and
keeps its name, link and first guest's words. The guest's own waiting song asked for again changes nothing and gives the guest's limit
back (`DjResponse.ownSong`). Under a per-party advisory lock ("S2PR" in the key's upper half, the code packed in the lower — six bits
a character, so two parties never share a key: `lockKey`, `RequestsLockKeyTest`), so guests asking at once make one row (`SongRequestVotesIT`,
20 rounds × 16 guests — red without the lock). A request the AI rejected this time for a song that waits is a vote too, and the
guest hears the verdict the song was taken with (the AI does not judge a song the same way every time — 2026-10-04, a song accepted
once, then rejected four times while it waited); any other rejected request is its own row. The AI's duplicate rule lists only
PLAYED songs, the latest to play first (`findRecentlyPlayed`; a waiting one is a vote). The DJ's "Zagrane" and "Pomiń" are conditional
`UPDATE`s (`markPlayed`, `skip`): only a waiting request, so a "Pomiń" right after a "Zagrane" in another window leaves the song played
(`SongRequestVotesIT`); `SongRequestEntity` is `@DynamicUpdate`, so the entity writes ("↩ Przywróć") touch only the columns they change
and a vote that commits meanwhile is kept. A request for a party deleted while the AI judged it is not saved: `saveOrVote` asks whether
the party is there under the lock the account's deletion holds (`AccountDeletionIT`). **A song
the DJ skipped** ("Pomiń" — usually "I do not have it") is not saved again for 2 hours from the skip (`skipped_at`, V20; the owner:
12 h was too long): the next guest who asks for it hears `guest.skipped_by_dj` ("Tej piosenki DJ teraz nie zagra — wybierz inną"),
nothing reaches the queue (`Outcome.SKIPPED_BY_DJ`, `findSkippedByTheDj`). A song only cleared with the whole queue may be asked for
again. **A skip or a "Zagrane" by mistake is undone** ("Cofnij" in a bar for 8 s after either — "Pominięto:" / "Zagrane:" —, "↩
Przywróć" at the request in the history, at any time; `POST /dj/dashboard/restore`, `DjService.restoreSong`, under the same lock):
the request waits again in its old place with its votes, the guest's words and its tips, `skipped_at` / `played_at` cleared — a
played one no longer counts as played, so the AI's duplicate rule lets it through again (the owner, 2026-10-10: "Zagrane" by mistake
had no way back, and the AI then refused the song as played) — unless the same song waits already. Never a request the AI rejected
or one cleared with the queue. "Zagrane" itself is one conditional `UPDATE` (`SongRequestRepository.markPlayed`): only a request of
the party that waits or has played already — a window with an old list cannot play a request skipped, cleared or rejected meanwhile
(review 2026-10-14 W5). The queue's ETag follows every waiting row (`computeFingerprint`: count-maxId-md5 of each row's id, votes,
tips and dedications — V38: a dedication on the guest's own waiting song changes no vote —, so a 👍 or 💸 moved from one song to another, or a "Zagrane" and a restore at once, changes it — review W6), and when it changed the
poll renders the queue read now, not the 3 s cache (`DjService.getWaiting` — review K1: the cache the guests' page fills gave the old
list under the new ETag, and every later poll was a 304 over it). The DJ's queue and
history have a "Głosy" column (sorted most-first on the first click, `data-sort-first="desc"`; the history sorted by it is the
party's ranking); the guest page's list is sorted by them (below) and the result page says "Ktoś już o to prosił — dodaliśmy
Twój głos! Głosów: N".

**Numbers and tips** (V28, the owner 2026-10-08): every song in the queue has a stable number. The guests see "#27" before the song
in "Prośby gości" and "Numer Twojej piosenki: #27" under an accepted request; with the DJ's tip link (V27) the button says what to
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

**A song the DJ adds** (V44, "Dodaj piosenkę"): a guest asked at the booth. A button beside the queue's heading folds out one field
with the guests' suggestions (`song-autocomplete.js`) and "Dodaj" (`add-song.js`; sent in the background by `forms.js`, the field
emptied, the queue fetched at once) → `POST /dj/dashboard/add-song`, for whom has QUEUE (`SongRequestCommandService.addByTheDj`).
The song (one line, ≤ 150, `SongNames.tidy`) goes into the queue as an accepted request under the party's lock: the next number, a
"Podejrzyj" link, `checked` (the guests' list and the TV show it, the guests 👍 it), no guest's words, no AI's comment, no style; the
same song waiting is a vote on it (the row stays the guest's; `SongRequestVotesIT`). No AI, no guest's limit, no notification; a song
the DJ skipped lately comes back (the DJ asks for it — the guests still hear "not now"); the hosts' "🚫 nie grać" does not stop it:
the queue marks a song on that list "🚫 Gospodarze: nie grać" (computed when shown, the list's hash in the ETag,
as ⭐). The queue and the history mark the song "🎧 Dodana przez DJ-a" (a phone's folded line of the history "🎧" alone, as "⚠" —
the whole badge cut the song to "sanah …"; 2026-10-28, `history-added-by-dj-phone`); the evening summary counts it in the requests and apart
("Dodane przez Ciebie"), its first vote left out of the guests' votes; the CSV's column "Dodana przez DJ-a" says "tak".

**The hosts' lists** (V29, the owner 2026-10-09: the couple at a wedding — or the host of a party, the pub's owner — usually send
the DJ a "do not play" and a "must play" list): "🚫 Nie grać" and "⭐ Koniecznie zagrać" in `party_settings`, one song or artist per
line (`util/SongList`: at most 100 lines of 150 characters, repeats and lines of fewer than 3 letters left out; matched in our code,
never sent to the AI). Under each list, as it is typed, "37 z 100 pozycji" and over 100 "Za dużo pozycji — zapisze się tylko 100
pierwszych." (`js/song-list-count.js`, the server's way of counting — `SongList.entries`; 2026-10-16: 120 lines were saved as 100
without a word); the hosts' page after such a save says "Zapisane, ale lista mieści najwyżej 100 pozycji…" (`hostDropped`). A line matches a
song when its words stand in the song's name one after another, as people read them (case, accents, "ł" and punctuation ignored;
whole words: "Szampan" is not "Szampany"): "Akcent" is every song of Akcent, "Przez twe oczy zielone" that song by anyone. In
`SongEvaluationService`: a request whose guest's words name a blocked one is refused **before the AI is asked**; one the AI worked
out to a blocked song is refused after it — not saved, `guest.host_blocked` ("Gospodarze poprosili DJ-a, żeby tej piosenki dziś nie
grać"), the guest's limit given back. A song on the wish list the AI rejected is **accepted** anyway, with `guest.host_wanted` as
its comment (`withTheHostsWish`) — unless it played within the DJ's duplicate window: then the AI's refusal stands. The DJ's queue
marks a song of the wish list "⭐ Życzenie gospodarzy" (computed when shown; the queue's ETag has the list's hash, so a change of it
sends the queue again). The lists are edited in a card under the limits, side by side (`POST /dj/dashboard/host-lists`) and by the hosts
themselves, **without an account**, on `/h/{token}` (`HostController`, `host.html`: two textareas, "Zapisz listę"; `noindex`,
`no-referrer`; an unknown or old secret is a 404): the DJ makes the link ("🔗 Utwórz link"), a new one makes the old one dead (asks
first), "Wyłącz link" takes it away — a party code serves every event, a link one. Last save wins (the DJ and the hosts edit the
same two texts). The lists stay until cleared: for the next event the DJ clears them and makes a new link.

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

