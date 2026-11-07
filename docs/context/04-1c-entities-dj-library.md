> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 4.1 (cont.). Keep it to what is true now.

**`SongRequestDedicationEntity` → `song_request_dedications`** (V38, 2026-10-17; an idea from Rekwest) — a guest's dedication
with a request ("dla Ani na 18-stkę"): `songRequestId` (FK to `song_requests`, `ON DELETE CASCADE` — the 30 days, "Wyczyść
historię" and the account's deletion take them with the requests), `text` (≤ 100, one line), `checked` (false: the AI could not be
asked, the queue marks it "niesprawdzona"), `createdAt`. One row per dedication: a guest who asks for a waiting song (a vote) or for
their own waiting song adds theirs too — at most 20 a song (`SongRequestCommandService.DEDICATIONS_PER_SONG`). The song's
`song_requests.dedications` counts them (an atomic `UPDATE`, under the party's requests' lock): the queue's fingerprint reads it, and
the queue reads the table (`findNewest`, the newest 3 a song, a window query on `idx_dedication_request`) only for songs that have
one. Only the DJ sees them (the queue), never the other guests; plan Free (the owner); the DJ can switch them off
(`party_settings.dedications_on`, the owner 2026-10-17).

**`EveningSummaryEntity` → `evening_summary`** (V61) — one evening of a party the DJ named or shared: `partyCode` (FK, `ON DELETE /
UPDATE CASCADE`), `evening` (a date; UNIQUE with the party), `title` (≤ 60, the DJ's), `shareToken` (≤ 32, UNIQUE, the hosts' link
`/s/{token}`) and `shareExpiresAt`, `updatedAt`, `morningSentAt` (V64: when the morning e-mail of the evening went — a row is made for
it when the DJ never named the evening); written by upserts (`EveningSummaryRepository.saveTitle`, `saveShare`, `claimMorning`). **`evening_song`**
(V61, no entity — native SQL only): what "Podsumowanie wieczoru" keeps of a deleted request — `song_request_id` (PK), `party_code` (FK,
cascades as above), `song_name` (null for one the AI rejected), `requested_at`, `played_at`, `status` (PLAYED / WAITING / SKIPPED / CLEARED
/ REJECTED_BY_AI), `votes`, `tips`, `dedications`, `added_by_dj`, `via_kiosk`; indexes `(party_code, requested_at)` and `(requested_at)`
(the purge). Both kept 13 months (Section 5.1).

**`FeedbackEntity` → `feedback`** — the DJ's bug reports and ideas (`message` ≤ 2000).

**`PushSubscriptionEntity` → `push_subscription`** (V21) — one of the DJ's browsers that takes notifications of new requests:
`ownerId`, `endpoint` (≤ 1000, UNIQUE: the push service's address of that browser — only Google FCM, Apple, Mozilla or Microsoft
WNS hosts are taken, `PushSubscriptionService.isPushServiceEndpoint`), `p256dh` / `auth` (its encryption keys), `locale` (the
notification's language; a device without one — subscribed before it was kept — gets Polish, as a page without a language), `createdAt`. At most 10 per DJ (the oldest goes); gone when switched off, on a 404 / 410 of the push
service, or with the account.

**`PartyStaffEntity` → `party_staff`** (V30, the owner 2026-10-09: a pub's bartender) — one person on a party's staff: `partyCode`
(FK to `party_settings.party_code`, ON DELETE CASCADE), `memberId` (their Google subject), `memberName` (≤ 100, the name of their
Google account — the owner's list), `joinedAt`, `permissions` (V32, ≤ 200, NOT NULL: what the person may do — the names of
`model/StaffPermission`, comma-separated, `StaffPermissionsConverter`; the role is not stored: `StaffRole.of` names the set); UNIQUE
(party, member), index by member. At most 10 per party **together with the invitations by e-mail waiting** (`Plan.PRO.staffPlaces()`,
`placesTaken`, counted under the party row's lock — `PartySettingsRepository.lockByPartyCode` / `lockByStaffToken`). Gone when the
owner takes the access away, with the member's account, or with the party.
**`StaffInvitationEntity` → `staff_invitation`** (V34, the owner 2026-10-10) — an invitation to a party's staff by the e-mail address of
a Google account: `partyCode` (FK, ON DELETE CASCADE), `email` (≤ 254, as the organiser typed it, in lower case — their list shows it),
`emailKey` (≤ 254, what the login is matched against: `util/EmailAddresses.key` — for Gmail without dots and "+…", "googlemail.com" is
"gmail.com"; other domains as they are), `permissions` (as `party_staff.permissions`), `invitedAt`. UNIQUE (party, email_key) — the
same address again changes the role and gives it 30 days again —, index by `email_key` (the login's lookup). Waits
`StaffInvitationEntity.MAX_AGE_DAYS` = 30 days: an older one counts for nothing (every read takes `invited_at` after now − 30 days) and
`StaffInvitationService.purgeOldInvitations` deletes it at 04:50 (Polish time) and after every start. Gone when answered ("Dołącz": it becomes a row of `party_staff`,
without the address; "Nie, dziękuję"), cancelled by the organiser, or with the party.
**The DJ's library → `dj_library`, `dj_library_track`** (V41 – V43, V57, plan Pro; plain SQL in `DjLibraryService`, no entity) — the
songs of the organiser's own collection, so the queue marks each waiting request "✓ Masz", "? Podobny" or "✗ Nie masz", with the tempo
and the key of the DJ's track beside "✓" and "?" ("128 BPM · 5A", V57).
`dj_library`: one row per party (`party_code` PK, FK ON DELETE CASCADE), `track_count`, `format` (`M3U` / `REKORDBOX`), `uploaded_at`
(part of the panel's ETag: a new upload marks the same queue anew), `playlist` (V42: the rekordbox playlist it is, by its folders —
"Kluby / Piątek"; null for a whole collection or an M3U; cut to its 200 characters — it is only shown), `with_details` (V57: uploaded
with the columns below; false for the libraries of before — their marks work as they did, and the card asks a rekordbox one to be
uploaded again: "Wgraj kolekcję jeszcze raz…", `LibraryDetailsMigrationIT`). `dj_library_track` (V42):
(`party_code` FK to `dj_library` ON DELETE CASCADE, `title_key` ≤ 200, `artists` ≤ 200, `swapped` — V43), PK of the three — the
queue's lookup, its titles at once (`title_key = ANY(?)`). Matched by the keys alone (`util/LibraryKey`): the title without a version in
brackets or after a dash with a version's word ("- Original Mix",
"- 2011 Remaster", "- Live") and without "feat. X", one word's two spellings made one on the key (V60, `LibraryKey.SPELLINGS`: "ing"
is "in" — "Livin'" and "Living" —, "number" is "no"; V60 changed the keys saved before the same way); the artists a sorted set (split at "," "&" "x" "and" "i" "feat." "vs" …, no
leading "The"). Beside the key, only shown (V57, `DjLibraryParser.Track`, all null when the file does not say it): `artist_name` and
`title_name` as the file writes them (one row per key: of a song's versions the shortest title, "One More Time" before "One More Time
(Extended Mix)"; an M3U's artist is the part of its name before " - "), and from rekordbox `bpm` (numeric(5,2), `AverageBpm`; 0 is
none), `tonality` (`Tonality`), `genre`, `year` (0 is none) — never a path, the DJ's `Comments`, `Rating` or `PlayCount`. The mark
(`LibraryKey.markIn` / `matchIn`, `LibraryMark`; `DjLibraryService.matches` adds the tempo and key of the track it was found as): the
same title with an artist in common "✓"; the same title alone (a title of at least 4 letters) "?"; else "✗". A **swapped** key (V43: an M3U's name read the other way
round — its title is an artist's name) only says "✓" (the title and an artist both the same), never "?": "Ed Sheeran - Perfect" is no
song like the band Perfect's. The file (`DjLibraryParser`, streamed: the JDK's own StAX (`newDefaultFactory`) with no DTD and no
entities for a rekordbox collection — its `COLLECTION`'s `TRACK`s by `Artist` + `Name` (its `Mix` added in brackets when the name
does not say it; a track with no artist whose name has " - " — a file never tagged, named by the file — read as an M3U's name, both
ways round), or with `playlist` only the tracks that
playlist's `TRACK Key`s point at (the `occurrence`th playlist of that path: rekordbox lets two of a folder share a name), the `PLAYLISTS`
tree read as rekordbox shows it, at most `MAX_DEPTH` = 32 folders deep; with a playlist the collection is held until the playlists,
so it may have at most `MAX_COLLECTION` = 100 000 tracks (`COLLECTION_TOO_LARGE`: unlimited, 64 MB of tiny tracks were half a
gigabyte of the heap, which is 512 MB on Railway; with V57's details such a collection of 100 000 with a playlist of 50 000 is read in
~4 s within 100 MB); an M3U's `#EXTINF` titles or its files' names — without a track's number before them ("01 - ", "01. ", not "50 Cent")
and with underscores read as spaces —, line by line in UTF-8 or else
Windows-1250 (an old Windows playlist), a name with one " - " kept the other way round too, as a swapped key) of at most
`DjLibraryService.MAX_TRACKS` = 50 000 tracks kept and 64 MB replaces the whole library in one transaction under the party row's lock
(`DjLibraryIT`: two uploads at once end as one of them). One file is read at a time on the whole server (`DjLibraryController`, a
semaphore: a second upload waits up to 30 s, then 503 "try again"; the file must arrive within 5 minutes — `READ_LIMIT`, then 408
"Wgrywanie trwało za długo" — so a body sent a byte at a time cannot hold the one turn). What the settings and the panel's ETag read of it (`Info`) is
cached (`djLibrary`, evicted after an upload's or a deletion's commit and with the party). Goes when deleted ("Usuń bibliotekę", whatever the plan), with a new upload,
or with the party; V42 deleted the libraries of V41 (their single key could not be made into a title and artists —
`LibraryTitlesMigrationIT`). The playlists to choose from are read from the chosen file in the browser (`js/dashboard/library.js`,
`playlistsOf`: the same paths as the parser; a second playlist of a name offered as "Wesele (2)"); a file over 64 MB is refused in the
browser before it is read or sent, only an `.xml` is read for its playlists, and only a plain-text answer is shown as the server's
sentence (a proxy's HTML page for a status never is). "Usuń bibliotekę" asks first (`data-confirm`). The card (2026-10-25): one line of what it does and "Jak przygotować plik?" folded under it; what the party has in a quiet box with a small "Usuń" at its end; the file's field is our own — the browser's input hidden (its button never filled a 44 px field on a phone), the label `#libraryDrop` the tap target, "Wybierz plik", then the file's name and size, a file dropped on it chosen too — and "Wgraj" the card's one action, enabled only with a file the server would read. Where the marks show: the queue (with "Ukryj „Nie masz”" beside the search — on a phone "Ukryj ✗", and "+ Dodaj" for "+ Dodaj piosenkę",
the whole words their `aria-label`, so the two and the search share one line, 2026-10-28, `library-tools-one-row-phone`, `list-tools.js`:
the "✗" rows hidden; the tempo and the key beside the mark, on its line also in a phone's folded card,
`library-tempo-beside-mark*`), and "Podsumowanie wieczoru" — "Nie masz w bibliotece" (the songs the AI let through with "✗" or "?", each once,
the most wanted first) and the CSV's column "W bibliotece" — by the library as it is now. And, unless the organiser switches it off
(`party_settings.library_hints`, V58, on by default; "Podpowiedzi gości" in the card, a select saved as picked, its part of the card answered in place with "✓ Zapisane" — `POST /dj/library/hints`, browser scenario `library-hints-switch`),
the guests' song suggestions on the party page and the kiosk: the ones the DJ has first, "✓ DJ ma" (Section 5.2, `LibraryHintService`).
