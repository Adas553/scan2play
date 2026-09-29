# Session Handoff — 2026-09-29

Where the work stands, for whoever continues (a new Claude Code session or a person). The history of the
first session (2026-09-28, remote) is summarised at the bottom.

## Start here (written at the end of the 2026-09-29 session)

- Phase 3 (the DJ sees and reorders the playlist queue — buttons **and drag and drop**) is finished, was tried by the owner
  on a real phone (Galaxy S25, Chrome; "everything worked", 2026-09-29) and is committed on `dev` as two local commits
  (code `fc7252d`, docs `808bf4c`). Phase 4 stages 0 and 1 are committed on top, two commits each, and stages 2 and 3
  together as one more pair (code `0a87a41`, docs `1ce76ec`) (below). **All of it is pushed to `origin/dev`** (2026-09-29, at the
  owner's request: Phase 2 stages 4–5, Phase 3, Phase 4 stages 0 to 3, each as code and docs — `git status -sb` should show
  `dev...origin/dev` with nothing ahead). Local `main` has two commits that are
  on neither `origin/main` nor `dev` — see "Open items for the owner", item 1.
- The phone login problem is solved: see **"Trying the DJ dashboard on a phone"** below (the owner reaches the local app on
  `https://dev.scan2play.com.pl`, which is registered in the Google OAuth client).
- The owner's next requests (2026-09-29): previous/next buttons for the DJ, like a normal player, and a way to make the
  active queue and the history readable when they hold many songs. Agreed plan = Section 14, **Phase 4**, three stages.
  **Stage 0 ("one window plays") is done and committed** (the owner tried it on the computer and the phone: "works well") —
  see "Phase 4, stage 0" below. **Stage 1 (⏭ Next as a remote control, readable lists, the "up next" list refreshed across
  windows) is done and committed** (the owner tried it: "działa"); see "Phase 4, stage 1" below. **Stage 2 (`V6`, the
  history as one timeline by play time, ⏮ Back) and stage 3 (⏯ pause from any window, the History tab scrolling into view)
  are DONE AND COMMITTED** (2026-09-29, on the owner's "zrób to"; the owner had tried them — ⏮ and the History tab on the phone —
  and asked for the two follow-ups below): one pair of commits for both stages (code, then docs) on top of `b7c0035`, because
  the two stages touch the same files; see "Phase 4, stage 2" and "Phase 4, stage 3" below: 360 unit tests, V6 and the history
  queries run against a real PostgreSQL 18, ⏮ and ⏯ checked on the real dashboard page in two browser tabs. **The two decisions
  of the owner from the very end of that session are BUILT, COMMITTED AND PUSHED** (stage 4, 2026-09-29, a third session, on the
  owner's "możesz wypychać"): ⏮ pressed twice within 10 s goes to the previous track, and the navigation is three tabs Panel DJ-a /
  Kolejka / Historia in a sticky bar — see "Phase 4, stage 4". One more pair of commits (code, then docs) on top of `36c76a9`; `git
  status -sb` should show `dev...origin/dev` with nothing ahead. (That session's prompt said stages 2 and 3 were uncommitted;
  `git status` showed a clean `dev...origin/dev` and the commits `0a87a41` / `1ce76ec` / `36c76a9`, so there was nothing to
  commit and the question about it was moot.) **One open question of the owner** (asked right after the push): ⏭ pressed after ⏮
  skips the guest song that played just before — see "Next", item 1.
- Working agreements are in `CLAUDE.md` (leave changes uncommitted until the owner has reviewed them and says to commit, never touch the
  `scan2play` database, test in a copy of the repo, CRLF, secrets).

## Where things stand

- Branch `dev`: `origin/dev` = `0519d78` (the 8 commits below plus one docs commit that added `CLAUDE.md` and this
  file). **Phase 2, stages 4 and 5, Phase 3 and Phase 4 stages 0 and 1 are committed on top of it as eight commits, and
  pushed; stages 2 and 3 came later as one more pair of commits, also pushed** —
  Phase 2: first the client code (`youtube-autopilot.js` rewritten, `dashboard.js`, `dashboard.html`), then the docs
  (`PROJECT_CONTEXT.md` with Section 5.4 rewritten and Sections 5.1/13/14 updated, `AGENTS.md`,
  `.github/copilot-instructions.md`, this file, and three stale comments in `DjPartySettingsController` /
  `DjDashboardController` — comments only). Phase 3 and Phase 4 (below): first the code and tests, then the docs.
  `origin/main` is untouched (`5314006`); local `main` is not (open items, item 1).
- **Phase 3 (the DJ sees and reorders the playlist queue) is committed** (2026-09-29, after the owner tried it on a phone):
  migrations `V4` and `V5`, the fallback queue code (`FallbackTrackCommandService`, `FallbackTrackRepository`,
  `YouTubePlaylistClient`, `FallbackPlaylistService`, `NextTrackService`, the entity), the new `FallbackQueueService` /
  `DjFallbackQueueController` / `MoveDirection` / `fragments/fallback-queue.html`, the panel in `dashboard.html`,
  `dashboard.js`, one line in `youtube-autopilot.js`, the PL/EN messages, tests, and `PROJECT_CONTEXT.md` (Sections 4.1,
  5.4, 6, 7.3, 10, 12, 13, 14). The owner's local `scan2play` database has been through the restart that applies `V5`
  (`manual_move`), and the owner tried the moves and the drag on it.
- **Phase 4, stage 0 (one dashboard window plays) is committed** (two commits, code then docs, on top of Phase 3): new
  `PlayerLeaseService`, `DjPlayerLeaseController`, `PlayerLeaseMode`, `PlayerLeaseResponse`,
  `fragments/player-lease-banner.html`; changed `DjDashboardController` (`next-track` takes `deviceId`, answers 409),
  `NextTrackResponse` / `NextTrackService` (a background track names its `playlistId`), `youtube-autopilot.js`,
  `dashboard.html` (one `th:replace`), the PL/EN messages, tests, `PROJECT_CONTEXT.md` (Sections 5.4, 6, 13, 14) and
  this file. No migration.
- **Phase 4, stage 1 is committed** (two commits, code then docs, on top of stage 0): new
  `PlayerCommand`, `fragments/player-controls.html`; changed `PlayerLeaseService` (`sendCommand`, the holder collects the
  command), `PlayerLeaseResponse` (`queueVersion`, `command`), `DjPlayerLeaseController` (`POST .../player-command`),
  `FallbackQueueService` (`getVersion`, `versionOf`), `DjFallbackQueueController` (`X-Queue-Version` header), `DjService` /
  `SongRequestRepository` / `DjDashboardController` / `ViewAttributes` (paged history: `limit`, `HistoryPage`; the unused
  `findTop50…` is gone), `dashboard.html`, `history.html`, `app.css` (`.list-scroll`), `dashboard.js` (`initListTools`,
  "Show more", the version), `youtube-autopilot.js` (⏭ Next, commands, the version), the PL/EN messages, tests (313 pass)
  and `PROJECT_CONTEXT.md` (Sections 5.4, 6, 13, 14) and this file. No migration.
- **Phase 4, stage 2 is committed** (together with stage 3, below): **migration `V6`**
  (`song_requests.played_at`), `SongRequestEntity.playedAt`, `DjService.markPlayed` (also used by `SongEvaluationService`),
  new `PlayHistoryService` / `HistoryEntry` / `RecentTrack`, `YouTubeUrls.extractVideoId`, `SongRequestRepository.findHistory`,
  `FallbackTrackRepository.findPlayedTracks`, `PlayerCommand.PREVIOUS`, `GET /dj/dashboard/recent-tracks` in
  `DjPlayerLeaseController`, `DjDashboardController` (uses `PlayHistoryService`), `history.html`, `fragments/player-controls.html`
  (⏮ and ⏭), `youtube-autopilot.js` (⏮), the PL/EN messages, tests (344 pass) and `PROJECT_CONTEXT.md` (Sections 4.1, 5.4, 6, 10,
  13, 14) and this file.
- **Phase 4, stage 3 is committed** (in the same pair of commits, on top of stage 2's changes, same files): `PlayerCommand.PAUSE` / `RESUME`,
  `PlayerLeaseService` (the holder's `playing` state, `Status.playing`, a 4-argument `report`), `PlayerLeaseResponse.playing`,
  `DjPlayerLeaseController` (optional `playing` parameter), the ⏯ button in `fragments/player-controls.html`,
  `youtube-autopilot.js` (pause / resume, an extra report on PLAYING / PAUSED), `dashboard.js` (`revealContent`), `history.html`
  (a heading in the tab), `DjDashboardController` / `ViewAttributes` (`historyHeading`), the PL/EN messages, tests (360 pass) and
  `PROJECT_CONTEXT.md`.
- **Phase 4, stage 4 is committed and pushed** (⏮ pressed twice, the three tabs; code, then docs): `youtube-autopilot.js`
  (`loadIntoPlayer`, `trackLoads`, `lastRestart`, `DOUBLE_PRESS_MS`), `dashboard.js` (`initTabSwitching` → `initTabs`),
  `fragments/components.html` (the `dj-nav` fragment: root is a `th:block`, a row of account buttons, `<nav id="djTabBar">`;
  the scroll-restore script skips a URL with a hash), `dashboard.html` (`activeTab='panel'`), `app.css` (`.dj-tabbar`,
  `scroll-margin-top`), the PL/EN messages (`dashboard.nav.panel`, `dashboard.nav.queue`, the ⏮ tooltip), tests (365 pass: the new
  `DjNavFragmentTest`, one more in `PlayerControlsFragmentTest`) and `PROJECT_CONTEXT.md` (Sections 5.4, 6.5, 6.6, 14). No migration.
- One stash, deliberately parked: *"Spotify playback redirect-uri as {baseUrl} template (parked: Spotify rejects
  http://localhost)"*. It makes `spotify.oauth.redirect-uri` follow the request host like the login flow does.
  Spotify only accepts HTTPS or a loopback IP (`127.0.0.1`) redirect URI, so it does not help local testing
  until the app is opened via `127.0.0.1`/HTTPS. `git stash pop` restores it.
- Tests: 365 tests pass (`.\mvnw.cmd -B test "-Dtest=!Scan2playApplicationTests"`, see `CLAUDE.md` for how to
  run them without disturbing the app running from IntelliJ) — run on 2026-09-29 in a scratch copy of the working
  tree with Phase 3 and Phase 4 stages 0, 1, 2, 3 and 4 (`BUILD SUCCESS`; 360 before stage 4, 344 before stage 3, 313 before stage 2, 266 before stage 1, 232 before stage 0,
  146 before Phase 3). Nothing in `youtube-autopilot.js` / `dashboard.js`
  has automated tests; stage 4 was verified against the real YouTube player, Phase 3 against a real PostgreSQL and the
  real JS on stub endpoints (see below).

## What was done (commit order)

| Commit | What |
|--------|------|
| `18f8281` | `@Builder.Default` on `PartySettingsEntity` — new parties were created with limits 0/0/0 |
| `1afdecf` | Auto-Pilot no longer jumps to a queued guest song when the DJ seeks the background track (`fallbackTrackChanged`) |
| `3da8ece` | Unit tests for `findNextPlayableGuestTrack` and `GET /dj/dashboard/next-guest-track` |
| `3670e57` | `SCAN2PLAY_GUEST_URL` documented (QR code / party link; use the LAN IP for a phone) |
| `2c0a19b` | **Flyway** adopted, baseline `V1` (Section 10 of `PROJECT_CONTEXT.md`) |
| `acc9201` | **Phase 2, stage 2**: `V2__create_fallback_track`, `YouTubePlaylistClient`, `FallbackPlaylistService` — the playlist is imported into the DB when the DJ sets it |
| `046f243` | `V3`: parties with `request_limit`/`cooldown_minutes` < 1 get 2 / 3 (zero disabled guest rate limiting) |
| `c0d1e2b` | **Phase 2, stage 3**: `POST /dj/dashboard/next-track` (`NextTrackService`) — guest first, else the next background track |
| after `0519d78` | **Phase 2, stage 4**: `youtube-autopilot.js` becomes a "dumb player" that asks `next-track` (see below) |
| after that | Docs (stage 5): Section 5.4 rewritten, Section 14 marks Phase 1–2 done, three stale comments fixed, this file |

The Section 14 stage table in `PROJECT_CONTEXT.md` is the authoritative progress list.

## Phase 2, stage 4 — the client uses the server (done)

`youtube-autopilot.js` is now the "dumb player" of the roadmap. It asks `POST /dj/dashboard/next-track` **only when a
track is about to be loaded**: Auto-Pilot on and the player idle (page load, Auto-Pilot switched on, the dashboard's
3 s poll), on `ENDED`, and after a player error. The endpoint is **not read-only** (it marks a background track
`PLAYED` as it hands it out), so it is never polled. Decisions taken with the owner on 2026-09-29:

- A guest song that arrives while a background track **plays** waits until it ends (same as the old playlist boundary).
- A **paused** player is never touched — a pause is the DJ's choice (the old code switched a paused *fallback* track to
  a waiting guest immediately). With Auto-Pilot **off** nothing starts by itself when a track ends.
- The dashboard does not show the import result (`X-Fallback-Import: ok|failed`) yet — left for a separate step.

Side effect to be aware of: a track the DJ starts by hand (▶ link) is no longer overridden by Auto-Pilot within 3 s (the
old "orphaned playback" branch paused it and started the next song); when it ends, Auto-Pilot carries on.

Removed from the JS: `loadPlaylist`/`setShuffle`/`setLoop`, the playlist index/video tracking (`fallbackTrackChanged`,
`fallbackTrackIndex`, `lastFallbackIndex`, `fallbackVideoId`), `guestSongPending`, `pendingPlaylistSetup`, the BUFFERING
early detection, `stateVersion`, `updateFallbackShuffle`, the `data-fallback-*` attributes on `#yt-player-card`. Kept:
`erroredSongIds` (sent as `exclude`), `lastMarkedPlayedId`, guest confirmation via `POST /dj/dashboard/play`.
`window.updateFallbackSource` / `stopFallback` remain, now meaning "drop the running *background* track" (a playing guest
song is left alone). `GET /dj/dashboard/next-guest-track` is no longer called by the client (endpoint and tests kept).

Details worth knowing:
- After 5 player errors in a row (no video reached `PLAYING`) the client stops asking immediately and leaves it to the poll
  (one ask per 3 s), so a playlist of unplayable videos is not burnt through in a tight loop.
- The answer of a lookup is dropped if, meanwhile, Auto-Pilot was switched off or the DJ started a track by hand. A
  background track handed out for a dropped answer is skipped for that round of the playlist (acceptable); a guest song
  is not consumed until it plays.
- `stopFallbackPlaylist()` in `dashboard.js` now stops the player only **after** the server has cleared the playlist —
  stopping first would let the poll ask for a new track in between.
- Measured on the real player (server stubbed at 0 ms): `ENDED` → next track `PLAYING` = 230–250 ms, which is YouTube's
  own load time. **No pre-fetching needed** unless a gap turns out to be audible at a real party.

**How it was verified (no login needed):** a throw-away static page with the real `youtube-autopilot.js`, `window.fetch`
stubbed for `/dj/dashboard/*`, `YT.Player` wrapped to log every `onStateChange` with `getVideoData().video_id`, served by
`python -m http.server` and opened in the built-in browser (the page is not kept in the repo). 13 scenarios passed: start
when idle (1 request, no `/play`); polls while playing and while paused (0 requests); `ENDED` → waiting guest plays and is
confirmed exactly once; a guest song the player cannot play (→ `exclude=<id>`, not confirmed); a run of errors (6 requests,
then one per poll); Auto-Pilot off (nothing on `ENDED`, resumes when switched on); `updateFallbackSource` stops a
background track but not a guest song; a hand-picked track is not overridden and Auto-Pilot continues after it; three
simultaneous triggers = 1 request; the answer is dropped when Auto-Pilot is switched off or a track is picked by hand
during the lookup.
Gotchas of that setup: the page needs a real click first (user activation) or YouTube leaves the player on the play button,
and the browser tab must be visible; `YT.Player` methods exist only after `onReady` (wrap them there); the player pauses
itself after a few seconds unless muted (`__p.mute()`); many well-known videos have embedding disabled and fail with error
150 (`jNQXAC9IZ_w`, `dQw4w9WgXcQ`, `9bZkp7q19f0`) — `M7lc1UVf-VE` and `aqz-KE-bpKQ` play fine.

## Phase 3, step 1 — the DJ sees what plays next from the playlist (done, committed)

Requested by the owner on 2026-09-29: see the next song of the uploaded playlist and, later, change the order. The owner
agreed to store titles (same 30-day rule) and to do it in two steps; step 2 (reordering) followed the same day, see below. Section 14 of
`PROJECT_CONTEXT.md` ("Phase 3") and Section 5.4 ("Up next panel") describe it; the short version:

- **The order is now fixed in advance.** `fallback_track.play_order` (V4) — the next track is the queued one with the
  lowest value; before, it was picked at the moment the player asked (a random row under shuffle), so there was nothing
  to show. Set at import, when a round of the playlist starts, and when the DJ flips the shuffle switch. A new round is
  prepared as soon as the last track is handed out (so the list is never empty), and a shuffled round never opens with the
  track that is still playing.
- **Shuffle switch, decided to be intuitive** (the owner asked for that): it re-orders the tracks still queued at once and
  the panel refreshes to show it. On → fresh random order. Off → playlist order *continuing after the last played track*
  (not a jump back to track 1); played tracks do not come back. The caption above the list always names the active order.
- **Titles** come from the import's `videos.list` call (`part=status,snippet`, no extra quota). Rows imported before V4 have
  no title: the panel shows `youtu.be/<id>` until the DJ saves the playlist again (a single video's title is looked up
  best-effort).
- **Panel:** in the YouTube Player card, to the right of the video (below it on a narrow screen — the owner picked this
  place; the first version sat in the playlist card), **all** queued tracks of the round (up to 500) in a list of fixed height
  (17rem) with its own scrollbar, first one marked "Next", tracks left in this round, a hint that guest songs play first.
  Refreshed on page load, after saving/clearing the playlist, after the shuffle switch and each time the player takes a
  background track; only the newest answer is shown; errors/redirects leave the list alone; the scroll position is kept.
- `takeNextTrack` makes up to 10 attempts (everyone races for the same head of the queue).

**How it was verified:** 186 unit tests (40 new) in a scratch copy; then, against a **throw-away PostgreSQL 18 database**
(`s2p_*`, dropped afterwards): V4 applied to data created at V3 (shuffle parties get random keys, others playlist order);
V1–V4 on an empty database plus Hibernate validation (a `@SpringBootTest` with dummy env vars and `PGDATABASE=s2p_...`);
the real queue SQL — playlist order over several rounds, 25–60 shuffled rounds without a track repeating at a round
boundary, switching shuffle off continuing after the last played track, `moveToEnd`, 8 concurrent callers, replacing the
playlist — and timings for 500 tracks (import ≈ 280 ms, new round ≈ 16 ms, shuffle switch ≈ 8 ms). The real fragment was
rendered with the real message bundles and looked at in the built-in browser (PL/EN, all four states), the whole
`dashboard.html` was rendered with a stubbed model, and the real `dashboard.js` + `youtube-autopilot.js` were run on that
page against a stub server (which request follows which event, overlapping refreshes, 500 and login redirects). None of
that test code is in the repo. (The real dashboard behind the login was opened by the owner later the same day, see step 2.)

Gotcha for anyone editing here with the same tools: `\u` escapes typed into tool inputs (Write/Edit/Bash) are decoded into
real characters, but `messages*.properties` must keep literal `\uXXXX` escapes — generate them with a script (use
`chr(92)`), encode the whole result *before* opening the file for writing (a failed encode after `open(..., 'w')` leaves
an empty file — it happened once and the file was restored from git).

## Phase 3, step 2 — the DJ reorders the queue (done, committed)

Requested together with step 1 (the owner wanted to be able to change the order); built after the owner confirmed the
shuffle design on 2026-09-29: **switching shuffle asks first when tracks were moved by hand.** Section 14 ("Phase 3") and
Section 5.4 ("Moving tracks") describe it; the short version:

- Every row of the "up next" list has three small buttons: ⇑ *play next*, ↑ *up one place*, ↓ *down one place* (disabled where
  they cannot do anything). `POST /dj/dashboard/fallback-queue/move` (`partyCode`, `trackId`, `direction` = UP / DOWN / TOP);
  204 done, 409 the track can no longer be moved (the player took it, another party's, an old playlist's), 400 bad parameter.
- The list is refreshed afterwards, the moved track is scrolled into view and flashes green; clicks are ignored while a move
  is in flight.
- Moves apply to the **current round** only — a new order (import, shuffle switch, next round) replaces them. `V5`
  `fallback_track.manual_move` flags moved tracks; the caption then says "..., changed by hand" (`data-manual` on the
  fragment tells `dashboard.js`), and flipping the shuffle switch shows a `confirm()` first (Cancel puts the switch back and
  sends nothing).
- **Drag and drop** (added on the owner's request right after the first review; the buttons stay): press on a row and drag
  it (mouse), or press and hold ~0.4 s and drag (touch — a finger that moves earlier is scrolling). The row moves through
  the list as the pointer passes the middle of the others, the list scrolls near its top/bottom edge, Esc / a cancelled
  touch puts the row back, a drop where it started sends nothing, and the list is not refreshed while a row is dragged.
  On drop: `POST /dj/dashboard/fallback-queue/place` (`trackId`, `beforeTrackId`, empty = the end) — the server refers to
  the track it goes in front of, so a queue that changed meanwhile cannot make the drop land in the wrong place. The
  title is plain text now (a press on it starts the drag); the YouTube link is the small ↗ beside the buttons.
- Not built: skipping/removing a track.

**A real bug found by the real database:** a stress test (concurrent moves and takes) made PostgreSQL report a
**deadlock** — statements that update many rows at once (renumbering, re-order, new round) lock the same rows in different
orders. Fixed by a per-party advisory lock (`pg_advisory_xact_lock`, `FallbackTrackRepository.lockQueue`) taken first thing
in every `FallbackTrackCommandService` method that changes the queue; a unit test pins that. Unit tests with mocks could not
have shown this — keep that in mind when touching those methods.

**How it was verified:** 232 unit tests in a scratch copy; against a throw-away PostgreSQL 18 database (dropped afterwards):
V5 on data created at V4, V1–V5 on an empty database with Hibernate validation, and the real SQL — exact orders after up /
down / play next (also twice in a row, and at the ends of the queue), ties in `play_order` (resolved by the renumbering), the
flags being set and cleared by every kind of new order, a track of another party / a wrong playlist / an unknown id / one the
player already took being refused, and 8 threads moving and taking at the same time; then, for dragging, every kind of drop (down, up, to the front, to the
end, onto itself, in front of its own successor) with the exact resulting order, ties, refusals, and 9 threads dropping,
moving and taking together (3 runs of all 17 database tests in a row, no deadlock). The real
`dashboard.js` ran on the rendered dashboard against a stub server: the click sends the right track id and direction, the
disabled buttons do nothing, a rapid double click sends one request, a 409 still refreshes the list, the moved row is brought
into view and flashes, and the shuffle switch asks (with the exact Polish text) — Cancel changes nothing, OK goes through,
and without manual moves it does not ask. The drag was driven with synthetic mouse events at real coordinates: a drop down
and up (the right `trackId` / `beforeTrackId`), a press on a button or the ↗ link does not start a drag, Esc puts the row
back and the refresh asked for meanwhile is done afterwards, holding the pointer near the bottom edge scrolls the list and
carries the row, dropping where it started sends nothing; and, in a phone-sized view, touch events: a moving finger is
scrolling (no drag), a tap does nothing, press-and-hold + move + release drops, a cancelled touch puts the row back.
**Afterwards the owner tried it on the real dashboard behind the login and on a real phone (Galaxy S25, Chrome, via
`https://dev.scan2play.com.pl`): the buttons and the drag worked** — the touch drag was the part that had only been
checked with synthetic events before.

Payload note: the fragment is about 1 KB per row (a 500-track playlist ≈ 500 KB per refresh) and the server does not
compress responses; fine on a LAN, and acceptable for the usual playlist size. `server.compression.enabled=true` would cut it
to a few percent but affects every page (mind BREACH, the dashboard shows guest-supplied song names next to a CSRF token), so
it was not switched on.

Gotchas of this session's tools: a long Python heredoc in the Bash tool was rejected once (quoting) — write the script to a
file with the Write tool and run it. Also see the `\u` gotcha above. The built-in browser pane pauses `requestAnimationFrame`
when it is not visible (screenshots then time out): the drag tests replaced `requestAnimationFrame` with a timer; a real,
visible page is fine. Synthetic touch events need the mobile viewport preset (it defines `Touch` / `TouchEvent`).

## Phase 4, stage 0 — one dashboard window plays (done, committed)

Why: the owner said they usually control from one device but may open the dashboard on a phone to peek. Reading the code
showed that a second window is **not passive** — every window has its own player and asks `next-track` while Auto-Pilot is
on, so a peeking phone would take a background track off the queue (consumed the moment it is handed out) that the main
window never plays, and a guest song could play on the phone's speaker. Existing behaviour, not caused by Phase 3.
Section 5.4 ("One window plays") has the full rules; the short version:

- A per-party **lease** in memory (`PlayerLeaseService`, 10 s timeout, single instance like the caches). Windows report to
  `POST /dj/dashboard/player-lease` every 3 s with a random id (`sessionStorage`) and a mode: `CLAIM` (renew, or take only when
  free), `WATCH` (never take), `TAKE_OVER` (the DJ's button). The holder plays; the others show a banner "Odtwarzanie działa
  na innym urządzeniu" with a "Odtwarzaj na tym urządzeniu" button (confirmation first when another window still plays).
- The server enforces it: `next-track` (with `deviceId`) answers **409** to a window that does not hold a live lease.
- **A playlist saved or cleared in a window that does not play** (found by the owner's question about the shuffle switch:
  the shuffle switch, moves and drags are server-side and need nothing, but *Save* / *Stop* reach only the player of the window
  where the DJ clicked, so the playing window would have finished the old playlist's track): the lease answer now carries
  `fallbackPlaylistId`, and `next-track` carries the `playlistId` of every background track. The playing window stops a
  background track whose playlist is no longer the current one (≤ 3 s) and asks for the next. A report sent before the
  running track was loaded is ignored (`trackLoadedAtLeaseSeq`), otherwise a slow answer describing the old playlist would
  stop the track just started for the new one.
- Decided with the owner: *Next* will work with Auto-Pilot off (stage 1). Not asked, chosen by me: a window that was told
  another one plays never re-claims by itself (only the button does), so closing the main tab does not make a phone start
  playing; the ▶ links do nothing in such a window (they just open YouTube).

**How it was verified:** 266 unit tests in a scratch copy (34 new: the service with a hand-moved clock, the controller, the 409,
the playlist ids, the banner fragment rendered with the real PL/EN bundles). The **real `youtube-autopilot.js` in two browser tabs** against a
throw-away stand-in server (Python, same rules and a CSRF check; a fake `YT.Player` that logs its calls; none of it is in the repo):
first tab plays and asks once; the second is refused, silent, shows the Polish banner and its ▶ link falls through; takeover
shows the exact Polish confirmation and moves playback, the old tab stops and shows the banner; a direct `next-track` from the
refused tab (with and without its id) gets 409 and hands nothing out; leaving the page releases the lease (beacon with `_csrf`
in the body) and the other tab shows "no device is playing" and takes over without a confirmation; a reload keeps the role; a
14 s outage of the lease endpoint changes nothing; a 409 from `next-track` silences a window that thought it played; a
playlist replaced or cleared "elsewhere" (the stand-in's state) stops the old track within one report, the next comes from
the new playlist and is not stopped again; with the lease answers delayed by 2.5 s a report sent before a same-window save
answers with the old playlist and is ignored. (The stand-in's `?playlist=` cannot be blank — Python's `parse_qs` drops blank
values; it uses `NONE` for "cleared". A first attempt showed "no stop" for exactly that reason, not because of the script.)
**Not done:** two real devices for the playlist part (the owner tried the lease itself on the computer and the phone), the real
Spring Security filter chain (that `_csrf` in a `sendBeacon` body is accepted — Spring's default reads that parameter, but it
was not run; if it were refused the 10 s timeout does the same job), out-of-order answers of two overlapping reports (guarded
by a sequence number, not exercised).

## Phase 4, stage 1 — Next from any window, readable lists (done, committed)

Requested by the owner (2026-09-29): ⏭ *Next* "like a normal player" and a queue and a history that stay readable when they
hold many songs; decisions: *Next* works with Auto-Pilot off, and in a window that does not play it is a **remote control**
of the window that does (option 3 of three offered); lists may have their own scroll box on a phone. Section 5.4 ("Next ⏭",
"The up next list in a window that did not change it", "Long lists") has the details; the short version:

- **⏭ Next** (`fragments/player-controls.html`, under the video): in the window that plays, `skipToNext()` asks `next-track`
  and loads the answer whatever the player is doing (Auto-Pilot off too; 204 → the current track carries on). In another
  window it posts `player-command` (`NEXT`); the server keeps one command per party and hands it out once, in the answer of
  the holder's next lease report (≤ ~3 s). The sender's button says "Sent…" and is disabled for 3.5 s, so two presses are one
  skip. 409 when nobody plays (banner: "no device is playing"). A command is dropped when the lease changes hands, is given
  up or expires; one that reaches the window mid-lookup is lost (press again).
- **The owner's shuffle question turned up a display gap:** the "up next" list refreshed only after events in its own
  window, so a shuffle or a move on the phone left the computer's list old until its next track (and the phone's list never
  noticed the computer taking tracks). Playback was right (the queue is server-side). Now the lease answer carries
  `queueVersion` (a hash of the `FallbackQueueView`, `FallbackQueueService.getVersion`; ≤ 500 tracks read once per report per
  window — if that ever matters, cache it for a couple of seconds) and the list endpoint sends `X-Queue-Version`; a window
  fetches the list again when a report brings a version it does not know.
- **Lists:** the active queue and the history are a count + a search box (accent-insensitive: "zolc" finds "Żółć") over a
  60 vh box with its own scrollbar and a sticky header (`.list-scroll` in `app.css`); the scroll box is outside the polled
  `<tbody>`, so a poll keeps the position and `applyListFilters` runs again after it. History: Played / Rejected filter and
  "Show more" (`?limit=`, 50 → 300, one row more read than asked to know if older ones exist, "50+" in the count; in the
  dashboard's tab it replaces the fragment in place keeping search, filter and scroll; on the standalone page it reloads).
  `DjService.getHistory(partyCode, limit)` now returns `HistoryPage(rows, hasMore)`; the unused `findTop50…` repository
  method is gone.

**How it was verified:** 313 unit tests in a scratch copy (47 new: commands and version in `PlayerLeaseServiceTest` /
`DjPlayerLeaseControllerTest` / `FallbackQueueServiceTest`, the paged history in `DjServiceTest` /
`DjDashboardControllerHistoryTest`, and template tests that render the real history fragment, the queue's polled tbody
(with a web context, for the `@{…}` links) and the Next button with the real PL/EN bundles). Then the **real dashboard**: a
scratch Java test (only in the scratch copy, not in the repo) rendered the whole `dashboard.html` in Polish with 60 songs in
the queue and the history fragment with 120 requests, and a stand-in server (Python; same lease/command/409/version rules;
fake `YT.Player`; not in the repo) served them with the real `static/js` and `static/css`; two browser tabs were two windows.
Checked: the queue box scrolls (432 px of a 720 px viewport, 2410 px of content), the header is sticky, search and count, a
poll replaced the rows and kept scroll position and filter; History tab: 50+, the Rejected filter (12 of 50), search, "Show
more" twice keeping both, 120 at the end with no button; ⏭ in the playing window (also with Auto-Pilot off); ⏭ in the other
window: "Sent…", disabled, one command for two presses, the playing window skipped within a report (also with Auto-Pilot off);
the playing window left → ⏭ got 409 and the banner said nobody plays and the button did not stay disabled; changing the
"up next" order on the server updated the list in both windows with one fetch each and none afterwards.
**Not done by me:** real devices — the owner tried the result afterwards and said it works ("działa"), without details of what
was tried, so the ~3 s delay of a remote ⏭ has not been judged in words; the pane of the built-in browser was not visible, so
no screenshot was taken and the look of the lists (spacing, the sticky header over the rows, the buttons on a phone) was
checked only through computed styles, not by eye;
the real Spring Security chain for `sendBeacon`; the standalone history page's "Show more" (it just reloads with `?limit=`).
Gotcha of the harness: the rendered `dashboard.html` needs the fake `YT` loaded first (the stand-in injects it at the start of
`<head>`), and Python's `parse_qs` drops empty values (`?playlist=` did nothing — use a word such as `NONE`).

## Phase 4, stage 2 — the timeline of what played, and ⏮ Back (done, committed)

The owner said "zaczynaj" (2026-09-29) after stage 1 was pushed, and had said that the remote ⏭ "works very well". The stage was
started, interrupted, and finished in a second session the same day. It is committed together with stage 3
(one pair of commits, code `0a87a41` and docs `1ce76ec`, on top of `b7c0035`) and pushed. Section 5.4 of `PROJECT_CONTEXT.md` ("Back ⏮", "The history is one timeline") has the
full rules; the decisions below are the ones nobody was asked about.

**Decisions I took (the owner was not asked; all easy to change — they are listed in the report to the owner):**
- `V6__song_request_played_at.sql` adds `song_requests.played_at` (nullable). **No back-fill**: rows played before V6
  keep `NULL` and the history places them by `requested_at` (`COALESCE(played_at, requested_at)`); a rejected request is
  placed by `requested_at` too. No expression index — the query is bounded by the pageable and one party's rows.
- A request becomes "played" in exactly one place, `DjService.markPlayed(song, now)` (package-private): sets the
  decision and `playedAt` (kept if already set, so a second confirmation does not move it). Used by `markSongAsPlayed`
  (the YouTube player's confirmation and the "Mark Played" button), `pushToSpotify` and
  `SongEvaluationService.handleAutoQueue` (Spotify auto-queue).
- **History = one timeline** (`PlayHistoryService`, new): guest requests (`findHistory`: played + rejected, ordered by
  `COALESCE(played_at, requested_at) DESC, id DESC`) and background tracks (`findPlayedTracks`: status PLAYED,
  `played_at IS NOT NULL`, newest first), each read with a bound of `limit + 1`, merged in Java (the n newest of the
  union are among the n newest of each side), ties by id then source. `DjService.getHistory` / `HistoryPage` are gone;
  `DjDashboardController` (constructor +`PlayHistoryService`) uses `PlayHistoryService.Page(entries, hasMore)`.
  The model attribute `history` of the history page/fragment is now a list of `HistoryEntry` (record in `model`:
  source GUEST|BACKGROUND, id, at, title, trackUrl, videoId, style, decision, djComment, energyLevel; `key()` = `G:<id>` /
  `B:<id>`), so `history.html` reads `req.title` and `req.at`; a background row shows a "🎶 Playlist" badge in the vibe
  column and a 🎶 before the title (the vibe column is hidden on a phone), "—" for the energy; the "Time" column is now the
  time of the event (played / rejected), not of the request. A background track counts as played when the player *takes*
  it (that is when `played_at` is set), so a track handed out but never heard is in the history too (documented in the
  class comment). A background track without a title is shown as `youtu.be/<id>`.
- `YouTubeUrls.extractVideoId(url)` (new, public, `Optional`): a watch URL or `youtu.be/…`; a search URL / Spotify URI /
  null give empty. `DjService.findNextPlayableGuestTrack` now uses it (its private copy is gone; behaviour the same for
  the URLs the app stores).
- **⏮ Previous** — `PlayerCommand.PREVIOUS` (same channel as NEXT: one command per party, the last press wins),
  `GET /dj/dashboard/recent-tracks?partyCode=` in `DjPlayerLeaseController` (read-only, ownership-validated; the last
  `RECENT_TRACKS_LIMIT = 30` tracks that played and are playable — `PlayHistoryService.getRecentlyPlayed` drops entries
  without a video ID — as `RecentTrack(key, source, id, videoId, title)` JSON, newest first). Client (`skipToPrevious` in
  `youtube-autopilot.js`), like a normal player: playing/paused/buffering and `getCurrentTime() > 3` → `seekTo(0)`;
  otherwise fetch the list, find the running track by `nowPlayingKey` (`G:<id>`/`B:<id>`, set in `playTrack`, `replayTrack`,
  null for a hand-picked ▶ track) and play the entry *after* it (older); an unknown key → the newest entry; when the player
  is idle (the track ended) and the track is in the list → replay that one; nothing older → `seekTo(0)`. A replayed track is
  **not** marked played again, is not a "background track" (no playlist check), and when it ends Auto-Pilot carries on with
  the queue — **⏭ after ⏮ goes to `next-track`, it does not walk forward through the history** (a deliberate simplification;
  repeated ⏮ does walk further back). Remote: `onControlClick(command, button)` handles both buttons (pending state per
  button, `COMMAND_PENDING_MS = 3500`); the lease answer's `command` runs `skipToPrevious()`.
- `fragments/player-controls.html`: the fragment is now `controls` (both buttons: `#playerPreviousBtn` before
  `#playerNextBtn`); messages `dashboard.player.previous(.title)`, `history.source.background` (PL/EN, the escapes made with a
  script as always). The tooltip text says "3 seconds" — keep it equal to `RESTART_AFTER_SECONDS` in the script.

**Tests (344 pass in the scratch copy: 313 before, +31):** `PlayHistoryServiceTest` (13: merge order, play time vs request
time, the fallbacks, ties, mapping, the `limit + 1` bound and `hasMore`, recently-played filtering), `YouTubeUrlsTest`,
`DjServiceTest` (play time recorded / kept / not set for another party / Spotify push; `markPlayed`),
`DjDashboardControllerHistoryTest` (rewritten for `PlayHistoryService`), `DjPlayerLeaseControllerTest` (PREVIOUS,
`recent-tracks`), `PlayerLeaseServiceTest` (PREVIOUS, last press wins), `HistoryFragmentTest` (rewritten for
`HistoryEntry`, background rows), `PlayerControlsFragmentTest` (both buttons). The three tests that build
`DjDashboardController` got the extra constructor argument.

**How it was verified:**
- **A real PostgreSQL 18** — throw-away databases `s2p_stage2` and `s2p_stage2_up` (created and dropped by hand and by the test;
  the owner's `scan2play` was not touched), a `@SpringBootTest` that lived only in the scratch copy: Flyway V1–V6 on an empty
  database plus Hibernate validation of `played_at`; **V5 → V6 on data that already existed** (both rows kept, `played_at` NULL,
  exactly one migration executed); the timeline over both tables with the real JPQL — the order (a background track 12:30, a guest
  song played 12:10, a rejected one 11:30, a background track without a title, a guest song played before V6 placed by its
  request time, a Spotify song), another party's rows and QUEUED / CANCELLED tracks left out, `hasMore` at the limits 3 / 5 / 6,
  `getRecentlyPlayed` (played only, no Spotify link, no rejected one); `markSongAsPlayed` persisting `played_at` and a second
  confirmation leaving it unchanged. (The first run failed on the test's own data — a row played "now" in the same party as the
  timeline test — not on the code; fixed by giving it a party of its own.)
- **The real dashboard in a browser** — the harness of stage 1, extended (the scratch renderer now builds `HistoryEntry` rows,
  every third a background track; the stand-in server keeps the timeline of what played, answers `recent-tracks`, accepts
  `PREVIOUS`, turns a background hand-out into a timeline entry and a confirmed guest song too; the fake player has
  `getCurrentTime` and `seekTo` and lets a test fire its events). ⏮ within the first seconds → the track before, again → the one
  before that (a background track, then a guest song, then a background track); after 3 s → only `seekTo(0)`; nothing older →
  `seekTo(0)`; a replayed guest song is not confirmed again (0 confirmations); a replayed track that ends is followed by a
  `next-track` track; ⏭ after ⏮ asks `next-track`; a hand-picked ▶ track → ⏮ goes to the newest entry; after an end with
  Auto-Pilot off ⏮ replays the track that ended and nothing loads by itself; a new guest song is confirmed once, joins the
  timeline, and ⏮ from it goes to the track before it; ⏮ from the second tab: "Wysłano…", disabled, one command, carried out by
  the playing tab within a report, its own player silent, 409 and "no device is playing" when nobody plays, the button not stuck;
  the history tab of the real page: 16 background rows of 50 (badge, 🎶 marker, "—" for the energy, the video link), the Played
  filter (42) includes them.
**Not done:** real devices (the owner should try ⏮ from the phone with the computer playing, and look at the history with
background tracks in it); the look of the buttons and the history on a phone was not seen by eye (the built-in browser pane was not
visible, checked through computed styles and text only); the real Spring Security chain for `sendBeacon` (from stage 0);
`⏮` while the window that plays is in the middle of a lookup is ignored (press again).

Things to look at on the way: a Spotify party's history page renders only its guests' songs (no background tracks there), which
was not looked at in a browser; an expression index `(party_code, COALESCE(played_at, requested_at))` would remove the sort of one
party's played and rejected rows in the history query — not added, see Section 5.4.
Gotchas of the tools: the Bash tool rejects a long Python heredoc (write the script to a file and run it — CLAUDE.md says so
too); the PowerShell tool refused a command that used `Remove-Item Env:…` (use `$env:NAME = $null`); `Select-Object -First N` on
the output of `mvnw` kills the build (exit 255) — use `-Last`; `psql` is `C:\Program Files\PostgreSQL\18\bin\psql.exe` (set
`PGPASSWORD` for the session); Python's `parse_qs` drops empty values in the stand-in server.

## Phase 4, stage 3 — pause from any window, and the History tab scrolling into view (done, committed)

Two requests of the owner, made while trying stage 2 on the phone (2026-09-29): "maybe we add the option to pause on the device
that does not play", and "when I click History on the phone it is hard to read, I have to go to the very end to notice that
anything changed". Section 5.4 of `PROJECT_CONTEXT.md` ("Pause ⏯", "The History tab scrolls into view") has the rules.

**Pause ⏯.** Explicit commands `PAUSE` and `RESUME` (not a toggle, so a stale button cannot invert the state), same channel as
⏭ / ⏮ (one command per party, the last press wins, 409 when nobody plays). The window that plays says in every lease report
whether its player makes sound (`playing`; `PlayerLeaseService` keeps it in the `Lease` and only the holder is believed; a
report without it leaves the last state; nobody plays → null), every answer tells it back, and the pause button in every window
shows "⏸ Pauza" or "▶ Wznów" by it (the window that plays looks at its own player). Two details that came out of the browser
check: the window that plays reports a change at once (on PLAYING / PAUSED — one extra report), and a window that sent a pause
or resume keeps "Wysłano…" until the state has really changed (or 9 s) — before that the label flipped back to the old text for a
moment and took up to ~9 s to be right. Measured 4–5 s from the press to the new label. A pause made at the computer itself shows
on the phone within a report. A paused player is left alone by Auto-Pilot (unchanged rule). Caveat: a browser may refuse to start
sound in a window nobody has touched, so a remote *resume* can fail on a page that was never clicked.

**History tab.** The cause was plain: the tab swaps the list in *below* the settings, the QR code and the player, so on a phone
nothing changed where the DJ was looking (in the harness the list started at 2194 px of a 3188 px page, on an 812 px screen).
Now `revealContent` (`dashboard.js`) scrolls the new content to the top of the screen after the switch (and after going back to
the queue), unless it is already in the upper 40 % of the screen; the fragment has a heading ("📜 Historia imprezy", the existing
`history.title`) when it comes as the tab (`historyHeading`; the standalone page has its own h1). The tab bar stayed at the top,
so going back meant scrolling up — the sticky bar of stage 4 fixed that (`revealContent` now aims below the bar).

**How it was verified:** 360 unit tests (16 new: the state and the commands in `PlayerLeaseServiceTest` /
`DjPlayerLeaseControllerTest`, the three buttons in `PlayerControlsFragmentTest`, the heading in `HistoryFragmentTest` and
`DjDashboardControllerHistoryTest`). The stage 1 / 2 browser harness (real rendered dashboard, real scripts, a stand-in server, a
fake player that now has `pauseVideo` / `playVideo` firing the player's state events): local pause and resume with the label
following and no track started by Auto-Pilot while paused; the server learning `playing` false / true; remote pause and resume
from the second tab ("Wysłano…", one command for two presses, the playing tab paused / played, its own player silent, the label
following after 4.6 s and 5.2 s); a pause made in the playing tab showing on the other tab within a report; 409 with the banner
and a button that does not stay disabled when nobody plays. History: on a 375×812 viewport the click scrolled the page from 0 to
2186 with the heading at 8 px and the filter buttons and the first rows (three of them background tracks) in view; going back to
the queue kept the queue in view.
**Not done:** the **smooth** scroll — the built-in browser pane was not visible, so animation frames do not run and even a plain
`scrollTo` (which follows Bootstrap's `scroll-behavior: smooth`) never moved; the check used the "reduce motion" path, which is
`behavior: 'instant'` (the smooth path is the standard browser behaviour but was not seen); real devices (the owner should try ⏯
from the phone with the computer playing, and the History tab on the phone); the look of the three buttons on a narrow screen.
Gotcha for the next harness: `window.scrollTo` in that pane does not move unless the behaviour is `'instant'`; set
`window.matchMedia` to report "reduce motion" to take that path.

## Phase 4, stage 4 — ⏮ pressed twice, and the three tabs (done, committed and pushed)

Two decisions of the owner from the last message of the stage 3 session (2026-09-29), built in a third session the same day and
committed and pushed when the owner said "możesz wypychać". Section 5.4 of `PROJECT_CONTEXT.md` ("Back ⏮", "Three tabs in a bar that stays in view") has
the rules; the short version:

**1. ⏮ — option B.** A second ⏮ within 10 s after a restart that ⏮ itself caused goes to the previous track. Why: from the
phone the previous track could practically never be reached — a remote press is disabled for 3.5 s (`COMMAND_PENDING_MS`) and
reaches the playing window at the next lease report (every 3 s), so two presses are collected 3 s or more apart, and the track
has always played longer than `RESTART_AFTER_SECONDS` (3) by then and restarted again. All in `youtube-autopilot.js`: every
track goes into the player through the new `loadIntoPlayer` (three call sites: `playTrack`, `replayTrack`,
`playInEmbeddedPlayer`), which counts the loads in `trackLoads`; `skipToPrevious` notes `lastRestart = {loads, at}` when it
restarts a track, and `restartedByBackJustNow()` (same track, less than `DOUBLE_PRESS_MS` = 10 000 ms) makes the next ⏮ skip the
restart. A single press, and a press in the first 3 s of a track, are unchanged. The tooltip `dashboard.player.previous.title`
(PL/EN, made by a script) says "10 seconds" — keep it equal to `DOUBLE_PRESS_MS` (and the "3 seconds" to `RESTART_AFTER_SECONDS`).

**2. Three tabs in a sticky bar.** Panel DJ-a scrolls to the top of the page, Kolejka shows the queue and scrolls to it, Historia
shows the history and scrolls to it; the lit tab follows the scroll position.
- `fragments/components.html`, fragment `dj-nav`: the **root is now a `th:block`** — `position: sticky` works only inside a parent
  as tall as the page, and the old root `<div>` ended right after the navigation, so the bar has to be a direct child of the
  page's `.container`. The account buttons (feedback, end party, logout, delete account) are **a row of their own above the bar**
  (they scroll away; on a wide screen that is one extra row of ~30 px, on a phone the buttons are now above the tabs instead of
  below them). The bar: `<nav id="djTabBar" class="dj-tabbar …">` with three links `data-dj-tab="panel|queue|history"`; the
  hrefs are `/dj/dashboard#top`, `/dj/dashboard#queue-content`, `/dj/history-view` (what works without the script, on a Spotify
  party's dashboard for History, and on the standalone history page). `activeTab` is now `panel` on the dashboard, `history` on the
  standalone page (`queue` also lights the queue tab; nothing passes it).
- `app.css`: `.dj-tabbar` (sticky, `top: 0`, `z-index: 1020` above the lists' sticky headers, opaque `--s2p-bg-card`),
  `white-space: nowrap`, a smaller padding below 400 px, and `scroll-margin-top: 4rem` on `#queue-content` / `#history-content`.
  The scroll-restore script in `<head>` does not restore the saved position when the URL has a hash (otherwise it would beat the
  anchor).
- `dashboard.js`: `initTabSwitching` became `initTabs` (finds the tabs by `data-dj-tab`, no longer by `href`; does nothing on
  the standalone page, which has no lists). Panel → `scrollToPosition(0)`; Kolejka → swap the history away if it shows, light,
  `revealContent(queue)`; Historia → with YouTube fetch the fragment once (a second press while it shows only scrolls), swap,
  light, `revealContent(history)`; without YouTube the link is left alone. `revealContent` now aims at *bar height + 8 px* (it used
  to aim at 8 px). `tabByPosition()`: Panel when `scrollY < 2` or the list's top is below the middle of the screen, otherwise the
  list that shows; at the very bottom the list wins. A click holds its tab lit for 900 ms (`litLockedUntil`) and `scrollend`
  ends the hold early, so a smooth scroll does not flicker the highlight. `window.reloadHistory` is defined only with YouTube (as before).
- Messages: `dashboard.nav.panel` ("DJ Panel" / "Panel DJ-a") and `dashboard.nav.queue` ("Queue" / "Kolejka"). The old
  `history.nav.queue` is not used by any template; its text was changed to match all the same.

**Decisions I took (the owner was not asked; all easy to change):** the account buttons in a row above the bar (instead of a
sticky bar that carries them too — that would keep "Delete account" always in view); Panel does not change which list shows; the
thresholds of the lit tab (0.5 of the screen height) and the 900 ms hold; Historia pressed while the history shows scrolls there
(before, it did nothing); the 4 rem `scroll-margin-top`.

**Tests (365 pass in a scratch copy: 360 before, +5):** `DjNavFragmentTest` (4: the three tabs in order with their hooks, hrefs
and labels in EN and PL; only the `activeTab` tab is lit and marked `aria-current`; the bar is a `<nav id="djTabBar">`, the
fragment starts with the banner — no wrapper element — the divs balance, the buttons come before the bar) and one in
`PlayerControlsFragmentTest` (the tooltip says "10 seconds", EN and PL).

**How it was verified.** There was no harness left from the earlier sessions (they ran on another machine), so a new one was
built in the scratch directory — none of it is in the repo: a scratch JUnit test in a copy of the repo (`HarnessRenderTest`)
that renders the real `dashboard.html` (YouTube PL and EN, Spotify PL; 60 songs), `history.html`, the history fragment and the
up-next fragment with a stub model (`WebContext`; the dashboard needs `_csrf` — a bean with `getToken()` / `getHeaderName()` —
`isActive`, `partyCode`, `globalVibe`, `activeProvider`, `isSpotifyConnected`, `playbackMode`, `requestLimit`, `cooldownMinutes`,
`duplicateCheckWindow`, `fallbackPlaylistUrl`, `fallbackPlaylistId`, `fallbackShuffle`, `qrCodeBase64`, `permanentLink`,
`history`), a Python stand-in server on port 8765 (lease with a 10 s timeout, one command per party handed out once, 409 without
a lease, `next-track`, `recent-tracks`, the real `static/js` and `static/css` served from the repo) and a fake `YT.Player`
(every call logged, the clock and the position controllable). Bootstrap came from its CDN.
- ⏮, locally: at 30 s a press restarts (`seekTo(0)` only); a second press at 4 s goes to the previous track; a track loaded in
  between makes the note stale (a press restarts again); after 12 s a press restarts again and the one after it goes back; ⏭
  between the two presses also resets it; in the first 3 s of a track one press goes back at once. Chain: 4 → 3 → 2 → 1.
- ⏮, from a second browser window in **real time** (the button "Wysłano…" for 3.6 s): the first press restarted the track in the
  playing window after 2.8 s, the second (pressed as soon as the button was free) made it load the previous track 3.0 s after the
  restart — exactly the case that could not work before.
- Tabs on 1280×800 and 375×812: the bar is sticky (`position: sticky`, top 0 once scrolled, z-index 1020); Kolejka lands with the
  list 50 px from the top (bar 42 px + 8), Historia fetches the fragment **once** (a second press only scrolls), shows the
  "Historia imprezy" heading and 50 rows (plus the hidden "nothing matches" row), Panel goes to 0 and leaves the history showing, Kolejka swaps the queue back; the lit
  tab and `aria-current` by scroll position (Panel at the top and while the list is below the middle, the list once it is above,
  the list at the very bottom); on the phone the three Polish tabs take x = 16…265 of 375 and do not wrap, no horizontal
  overflow, the buttons row sits above and scrolls away; English labels ("DJ Panel / Queue / History") on the wide screen; the
  stuck bar over the queue looked right in a screenshot (which was cropped at the right edge; the computed geometry of the cards
  and the input showed no overflow).
- Plain links: the standalone history page lights Historia, its Panel and Kolejka links carry the hashes; a real click on
  Kolejka there navigated to `/dj/dashboard#queue-content` and the saved scroll (100) was **not** restored (the anchor won: 1831
  at the moment of the jump); with smooth scrolling off, a same-page jump to `#queue-content` put the list at 64 px (the
  `scroll-margin-top`); a Spotify dashboard: Panel and Kolejka scroll and light, `reloadHistory` is undefined, and a click on
  Historia navigated to `/dj/history-view` (a normal link).

**Not done:**
- **The smooth scroll animation** was not seen (and `scrollend`): the built-in browser pane is not visible, so it draws no
  frames and a smooth `scrollTo` never moves. The checks used the "reduce motion" path (`behavior: 'instant'`, by overriding
  `matchMedia`) or switched `html { scroll-behavior: auto !important }` on. The scroll events for the lit-tab checks were
  dispatched by hand after `scrollTo` (a hidden pane fires none); the browser firing them is standard. A 900 ms hold may be
  shorter than a very long smooth scroll: the highlight could flip for a moment before `scrollend` sets it right.
- Real devices (the owner should try ⏮ twice from the phone with the computer playing, and the three tabs on the phone — do the
  buttons above the tabs feel right, does the bar cover anything when a list is at the top).
- Arriving at a **YouTube** dashboard by `/dj/dashboard#queue-content` (only possible from the standalone history page): the
  anchor jump is right at the moment it happens, but the up-next list is filled in afterwards and pushes the queue down by its
  height (~300 px); seen in the harness, not fixed (the dashboard's own tabs do not reload the page).
- The real Spring Security chain (unchanged from stage 0), and PostgreSQL — nothing in this stage touches the database.

Gotchas of the tools: the hidden pane reports a 0×0 viewport until `resize_window` sets one (mobile preset or a custom size);
a page load jump to a `#anchor` happens at rendering, so it looks undone until a frame is drawn (a screenshot draws one);
`messages.properties` holds one raw "—" in a comment (`# Footer — Legal links`), so a script must read and write it as UTF-8, not
ASCII; in the Bash tool `powershell -Command "… $_ …"` loses the `$_` (use the PowerShell tool — and stop the harness by the
process that listens on the port, not by name); `Read`/`Edit` handle the CRLF working files fine (`git diff --stat` showed no
whole-file rewrites).

## Trying the DJ dashboard on a phone (Google login) — solved

**How it works now (2026-09-29):** the owner opens the local app, on the phone and on the computer, through
`https://dev.scan2play.com.pl`. `https://dev.scan2play.com.pl/login/oauth2/code/google` is registered in the Google OAuth
client, the name resolves to Cloudflare addresses, and a `cloudflared` process runs on the owner's computer — so it is
presumably a *named* Cloudflare tunnel to `localhost:8080` (the tunnel configuration was not inspected). `{baseUrl}` follows
the forwarded HTTPS host (`server.forward-headers-strategy=FRAMEWORK`), so the login redirect is the registered one. Login
and all Phase 3 features work on a Galaxy S25.

**Why the LAN address can never work (kept for reference):** the owner had first tried `http://192.168.100.184:8080`. The
Google Cloud Console refuses such a redirect URI when it is added ("must end with a public top-level domain", "must use a
valid domain of a private top-level domain type"), and when the app sends it anyway Google answers *Error 400:
invalid_request* — "device_id and device_name are required for private IP". Google's rules
(developers.google.com/identity/protocols/oauth2/web-server, "URI validation"): HTTPS required (localhost exempt), no raw IP
hosts (localhost IPs exempt), the TLD must be on the public suffix list. So do not open the dashboard through the LAN IP;
use the `dev.` HTTPS address, or `http://localhost:8080` on the computer. Nothing in the app needed changing for this.

**Other ways, not needed now:** Android over USB — `chrome://inspect/#devices`, port forwarding 8080 → `localhost:8080`, then
`http://localhost:8080` in Chrome on the phone. On the owner's Samsung, *Auto Blocker* blocks USB debugging, and after it was
switched off the authorisation prompt still did not appear, so this was dropped. The HTTPS address should also make
Spotify usable locally (the parked stash makes its redirect follow the request host; the URI
`https://dev.scan2play.com.pl/dj/spotify/callback` would have to be added in the Spotify Developer Dashboard) — untested.

## Next

1. **Open question of the owner (2026-09-29, right after stage 4 was pushed): ⏭ after ⏮ loses the guest song.** The case: the
   playlist plays, a guest song arrives and plays; ⏮ goes back to the playlist track that played before it; ⏭ then does not return
   to the guest song but hands out the *next* playlist track (`skipToNext` asks `next-track`, and the guest song is already
   marked played, so it is not queued any more) — while ⏮ from there finds it again. It is the deliberate simplification of stage 2
   ("⏭ after ⏮ goes to `next-track`, it does not walk forward through the history", Section 5.4), and it does confuse. **Proposed
   (recommended to the owner, not built — waiting for a yes):** after ⏮ the ⏭ button retraces the steps — a replayed track
   (`replayTrack`) sets a flag; `skipToNext` in a flagged window fetches `recent-tracks`, and when the running track is not the
   newest entry it replays the entry just *newer* (`recent[position - 1]`, no confirmation, no new `played_at`), and only at the
   newest entry (or with a hand-picked track, or a track not in the list) asks `next-track` as before; `playTrack` and
   `playInEmbeddedPlayer` clear the flag. The natural end of a replayed track stays as it is (Auto-Pilot carries on with the
   queue, so a party does not hear the same tracks twice by accident). Client only, no server change; needs the ⏭ tooltip, Section
   5.4 ("Back ⏮" says ⏭ does not walk forward), a harness check (the case above: [G, B2, B1] → ⏮ → B2 → ⏭ → G → ⏭ → new track) and
   a decision about the remote ⏭ (it uses the same `skipToNext`, so it would behave the same). Alternative: leave it and say so in
   the ⏭ tooltip.
2. **The owner tries stage 4 on the phone** (⏮ twice with the computer playing; the buttons row above the tabs; the bar over a
   list) and says what to change. The migration `V6` is applied at the next restart of the app (the owner's `scan2play` database
   was at `V5`; the owner has already restarted and tried stages 2 and 3). With stage 4 Phase 4 is complete.
3. Follow-ups the owner may also want: skip/remove a track from the "up next" list, one line in the panel
   saying how many guest songs wait ("Czeka 2 piosenki gości" — they play first; the owner asked about showing both lists
   together and agreed to keep the guest table separate), and a note in the panel when Auto-Pilot is off (nothing plays
   by itself then).
4. Optional (Section 14): show the import result on the dashboard (`X-Fallback-Import: ok|failed`,
   `X-Fallback-Import-Reason`) — the new panel is a natural place — instead of always flashing the Save button green;
   remove `GET /dj/dashboard/next-guest-track` and its tests, which nothing calls any more; browser-level tests for the JS
   (every stage of Phase 4 was checked by hand in a throw-away harness that is not kept — a Playwright / similar suite in the repo
   would replace it; see "Phase 4, stage 4", "How it was verified", for what such a harness needs).
5. `dev` → `main` is the next real decision (open items 1 and 3 below), but the owner said on 2026-09-29 that they do
   **not** want to merge yet — they want a polished `dev` first. Leave `main` alone until they bring it up.

## Open items for the owner

1. **`main`**: `origin/main` is still the old pre-session state (`5314006`). Merging `dev` into `main` is a deliberate step
   (see `AGENTS.md`): the production database has never been checked against `V1` (item 3) and the client still has no
   browser-level tests. **Local `main` also holds two commits of the owner's from 2026-04-06/07 that exist only on this
   computer** — `33eef2a` (Screen Wake Lock, so the display does not sleep during DJ sessions) and `b3870d6` (an
   environment-agnostic configuration: `{baseUrl}` placeholders for the Spotify/Google redirects, `scan2play.guest-url` from
   `${BASE_URL}`) — found on 2026-09-29 while pushing; they are not on `origin/main` and not on `dev` (`main` is not an
   ancestor of `dev`), and were left alone. The redirect change overlaps with what `dev` already did (`{baseUrl}`), so
   expect conflicts when merging. Push `main` or otherwise keep those two commits before anything is done to that branch.
2. **`YOUTUBE_API_KEY`**: the production key was pasted into a chat/screenshot on 2026-09-28 — rotate or restrict it
   (Google Cloud Console → Credentials: restrict to the YouTube Data API v3). Local run configuration: the variable
   name must have no stray characters (it had a trailing `:`, which silently disabled the key).
3. **First production deploy** of Flyway: backup, compare `pg_dump --schema-only` with `V1__baseline.sql`
   (checklist in `PROJECT_CONTEXT.md`, Section 10). The production schema has never been checked against `V1`.
4. Spotify locally: see the stash above; the Spotify Developer Dashboard also needs the redirect URIs.

## History — first session (2026-09-28, remote Claude Code)

That session attached the repo, committed the three previously local-only docs (`PROJECT_CONTEXT.md`, `AGENTS.md`,
`.github/copilot-instructions.md`) to `dev`, fixed a stale Section 5.4, wrote the "Master Queue" roadmap (Section 14),
found that its `PENDING`/`APPROVED` guest-approval gate never existed in the code, shipped Phase 1 (server-side
`next-guest-track`, `e811ba4`) and replaced the hardcoded production OAuth2 login redirect URIs with
`{baseUrl}` templates. Its Phase 1 change had not been build-verified; that was done in the second session.
