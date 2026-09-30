# Session Handoff — 2026-09-30

Where the work stands, for whoever continues (a new Claude Code session or a person). The history of the
first session (2026-09-28, remote) is summarised at the bottom.

## Start here (updated at the end of the 2026-09-30 session, the sixth)

- **The very newest session (2026-09-30, the sixth) is described in "The session of 2026-09-30, the sixth" (after the fifth's section).** In
  short: at the owner's choice of all four, it added **browser scenarios for the lease, the lists, the History tab and the tabs** (27 scenarios
  now), made **`<html lang>` of the dashboard follow the language** (`html.lang` in both bundles), **removed `GET /dj/dashboard/next-guest-track`**
  with its tests, and wrote **a GitHub Actions workflow for the browser tests** (not run on GitHub yet). The Auto-Pilot hint / IFrame message
  are still **not built**: the owner tried the friend's steps on another laptop and they worked; they will ask her again. **All of it — and
  item 7 of the fifth session, which the same files carry — is COMMITTED as one more pair (code `50558eb`, then the docs) on the owner's
  "ok, commituj"; NOT pushed: `dev` is 12 commits ahead of `origin/dev`** (`git status -sb`: `dev...origin/dev [ahead 12]`).
- **The fifth session (2026-09-29/30) — see "The session of 2026-09-29/30" below.** In short:
  the browser tests are now **in the repo** (`src/test/browser`, `python src/test/browser/run.py`), the three small JS fixes (and, added
  later that day at the owner's request, two buttons "Wstecz" / "Od początku" in a window that does not play — its section, 6) and the
  three polish items are built, the guests' song requests are purged after 30 days (`V8` is a new migration — the status `SKIPPED`), and
  the Auto-Pilot hint / IFrame message were **not** built (the owner wants the friend's steps reproduced first). **All of it is
  COMMITTED as one more pair (code `8248f9c`, then the docs) — on the owner's "działa, commituj", said after trying the two back buttons;
  NOT pushed: `dev` was 10 commits ahead of `origin/dev` (12 since the sixth session's pair).** The list of the changed files is in that section.

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
  Kolejka / Historia in a sticky bar — see "Phase 4, stage 4". One more pair of commits (code, then docs) on top of `36c76a9`; after
  that push `git status -sb` showed `dev...origin/dev` with nothing ahead. (That session's prompt said stages 2 and 3 were uncommitted;
  `git status` showed a clean `dev...origin/dev` and the commits `0a87a41` / `1ce76ec` / `36c76a9`, so there was nothing to
  commit and the question about it was moot.) **Right after the push the owner asked about ⏭ after ⏮ skipping the guest song that
  played just before; on "zbuduj" it was built — ⏭ retraces the steps after ⏮ — and, on "możesz commitować", COMMITTED (a pair,
  code then docs, on top of `74d1e05`; NOT pushed yet)**: see "Phase 4, stage 4", "Follow-up". **Then the owner asked whether the
  history should keep the playlist tracks and what a history of 1000 songs would cost; on the answer ("ok, zrób to") history
  filters — Guests / Playlist next to the old ones, applied by the server — were built and, on "commituj", COMMITTED (another pair,
  code then docs, on top of `def4991`; NOT pushed yet — `dev` is 4 ahead of `origin/dev`)**: see "Phase 4, stage 4", "Follow-up 2".
  **The owner decided that the round-reset finding at the end of that section ("the history of the playlist starts over when the
  playlist loops") is to be handled in a NEW session. That session (2026-09-29, a fourth one) BUILT it — a play log, `V7`
  (`fallback_play`) — reviewed by the owner in IntelliJ and, on their "ok, commituj", COMMITTED as one more pair (code, then docs) on top of
  `0feabec`; NOT pushed yet — `dev` is 8 ahead of `origin/dev` (these three pairs of commits, then the wake lock and the docs of the session that followed): see "Phase 4, stage 4", "Follow-up 3", and "Next", items 1 and 1a. The
  history of the playlist is now the whole party (30 days at most), not one round.**
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
- Tests: **426** tests (425 run, 1 skipped — the fixture recorder) after the sixth session (2026-09-30: 429 before it, minus the six tests of the
  removed `next-guest-track` endpoint, plus three of `DashboardPageRenderTest`; counted in a scratch copy, `BUILD SUCCESS`) and **27 browser scenarios**
  (19 before). The next bullet's numbers are those of the fifth session (**429** tests, 428 run, 1 skipped, with the work of the 2026-09-29/30 session — 386 before it; the rest of
  this bullet describes the earlier runs)
  (`.\mvnw.cmd -B test "-Dtest=!Scan2playApplicationTests"`, see `CLAUDE.md` for how to
  run them without disturbing the app running from IntelliJ) — run on 2026-09-30 in a scratch copy of the working
  tree (`BUILD SUCCESS`; 386 before the browser tests, the skip and the purge, 366 before the history filters, 365 before the first follow-up, 360 before stage 4, 344 before stage 3, 313 before stage 2, 266 before stage 1, 232 before stage 0,
  146 before Phase 3). The browser code has **browser tests in the repo** since 2026-09-30 (`src/test/browser`, 19 scenarios, not part
  of `mvnw test`); earlier stages were verified against the real YouTube player, a real PostgreSQL and throw-away versions of that
  harness (see below).

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
`python -m http.server` and opened in the built-in browser (the page is not kept in the repo — the harness of the later stages is: `src/test/browser`, since 2026-09-30). 13 scenarios passed: start
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
throw-away stand-in server (Python, same rules and a CSRF check; a fake `YT.Player` that logs its calls; none of it was in the repo — its descendant is, `src/test/browser`):
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
scratch Java test (only in the scratch copy then; `DashboardPageRenderTest` is its descendant in the repo) rendered the whole `dashboard.html` in Polish with 60 songs in
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
  `played_at IS NOT NULL`, newest first — **replaced by the play log, `FallbackPlayRepository.findRecent`, in Follow-up 3**), each read with a bound of `limit + 1`, merged in Java (the n newest of the
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
  the queue — **⏭ after ⏮ went to `next-track` and did not walk forward through the history** (a deliberate simplification of
  this stage; **changed in stage 4: ⏭ now retraces the steps**; repeated ⏮ does walk further back). Remote: `onControlClick(command, button)` handles both buttons (pending state per
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

### Follow-up of stage 4: ⏭ after ⏮ retraces the steps (done, committed, not pushed)

The owner's question right after stage 4 was pushed (2026-09-29): the playlist plays, a guest song arrives and plays; ⏮ goes back to
the playlist track before it; ⏭ then hands out the *next playlist track* and the guest song is "lost" (⏮ again finds it). Cause:
`skipToNext` always asked `next-track`, and a guest song that has played is no longer queued. It was the deliberate simplification of
stage 2; the owner found it confusing and, on the recommendation, said "zbuduj". Section 5.4 ("Back ⏮") has the rules.
- `youtube-autopilot.js`: `playingFromHistory` is set by `replayTrack` (a track that came back through ⏮) and cleared by `playTrack`
  (anything the server hands out), `playInEmbeddedPlayer` (a hand-picked ▶ track) and `stopPlaybackHere` (lost lease). `skipToNext`:
  with the flag set it fetches `recent-tracks` and, when the running track (by `nowPlayingKey`) is not the newest entry, `replayTrack`s
  the entry that is one **newer** (`recent[position - 1]`: no `POST /play`, no new `played_at`); at the newest entry, for a track not in
  the list, or without the flag it asks `next-track` as before. If `recent-tracks` fails it does nothing (like ⏮). **The natural end of
  a track that came back is unchanged**: Auto-Pilot goes to the queue (no accidental repeats at a party). The remote ⏭ runs the same
  function in the window that plays, so it behaves the same. The example `[G, B2, B1]`: ⏮ → B2, ⏭ → G, ⏭ → the next queue track.
- Messages: `dashboard.player.next.title` (PL/EN, by script) says that after ⏮ ⏭ goes forward again through what played.
  `PlayerControlsFragmentTest` +1: **366 unit tests pass** (365 before; run in a scratch copy: 367 with the scratch renderer,
  `BUILD SUCCESS`).
- **Verified in the browser** with the harness of stage 4 (real scripts on the real rendered dashboard, stand-in server, fake
  `YT.Player`), extended with a waiting guest song (`/__guest`), a failing `recent-tracks` (`/__fail`) and **scenario scripts**: a
  page opened with `?scenario=NAME` runs `scenario.js` after load and POSTs its result to the server, which writes it to a file — done
  because the browser pane's JS tool kept getting no verdict from the auto-mode classifier this time; the page-driven way turned out
  handy (repeatable, nothing typed by hand), and `navigate` + reading the result file is all it needs. 22 steps, all as expected,
  no errors: the owner's case `[G, B2, B1]` — ⏮ → B2, **⏭ → G**, ⏭ → the next queue track; ⏮ ⏮ ⏮ back to B1 and ⏭ ⏭ ⏭ forward
  again through B2, G, B3 (the newest entry, replayed) and then a new track from `next-track`; a hand-picked ▶ track after ⏮ →
  ⏭ asks `next-track`; a replayed track that ends → Auto-Pilot asks `next-track` and a ⏭ after that is a plain next; with
  `recent-tracks` failing ⏭ and ⏮ do nothing and work again afterwards. **Exactly one `POST /play` in the whole run** — the guest
  song was confirmed once, when it played live, and neither replay confirmed it again. From a second browser window (the banner
  "playback runs on another device", the button "Wysłano…") a remote ⏭ made the playing window load the guest song 0.8 s later
  instead of a new playlist track.
- **Not done:** real devices (the owner should try ⏮ then ⏭ from the phone with a guest song in the history); the lost-lease reset
  of the flag (`stopPlaybackHere`) is not exercised; more than 30 tracks back (`recent-tracks` holds 30: the track is then not in the
  list and ⏭ asks `next-track`); a guest song the DJ marks played by hand (button) while retracing joins the timeline as the newest
  entry, and ⏭ walking forward would replay it. (The stage 2 text above, "⏭ after ⏮ asks `next-track`", describes what was
  built and verified then; this follow-up changed it.)

### Follow-up 2 of stage 4: history filters, Guests and Playlist (done, committed, not pushed)

The owner asked (2026-09-29) whether playlist tracks belong in the history at all. Answer given: yes — the timeline is what ⏮ / ⏭
walk along and the history should tell the whole night — but the list needs a way to see only the guests' requests, because a
playlist track every ~3 minutes drowns them (the old filters were All / Played / Rejected, and Played includes the playlist). Asked
"ok, zrób to". Then, mid-work, the owner asked what a history of 1000 songs would cost; the honest answer led to changing the design
(below). Section 5.4 ("The history is one timeline", "Long lists") has the rules.
- **Cost of a long history: nothing grows with the number of songs.** Every read is bounded by the limit (`HISTORY_PAGE_SIZE` 50,
  `HISTORY_MAX_LIMIT` 300; `limit + 1` rows from each of the two tables), the history is fetched only when the DJ opens the tab or
  presses "Show more" (never polled), the browser holds at most 300 rows, and a row of the fragment is ≈ 1.8 KB (measured on
  the rendered fragments: 50 rows = 90 KB, 100 rows = 178 KB; so ≤ 300 rows ≈ 0.5 MB, uncompressed — `server.compression` is off).
  The request query sorts one party's played and rejected rows by `COALESCE(played_at, requested_at)` (no index for the
  expression, documented in 5.4) — negligible next to the bound for a party of a thousand requests.
- **But a filter applied on the page would have been wrong for a long party.** My first version filtered the loaded rows in the
  browser (a `data-source` attribute on each row; 367 tests green, its browser scenario written but never run); the 1000-songs question showed its flaw: the
  page holds the last 50 (up to 300) *mixed* entries, so "Guests" would show the handful of requests among them and miss the older
  ones. **Now the server filters, inside the bounded queries:** `HistoryFilter` (new enum: `ALL`, `GUEST`, `BACKGROUND`, `PLAYED`,
  `REJECTED`; `param()`, `fromParam()` — anything unknown is `ALL`), `PlayHistoryService.getHistory(partyCode, limit, filter)`
  (the 2-argument form is `ALL`; only the tables the filter needs are read, so `BACKGROUND` never touches `song_requests` and
  `GUEST` / `REJECTED` never touch `fallback_track`; `getRecentlyPlayed` for ⏮ / ⏭ is unchanged), `DjDashboardController`
  (`?filter=` on `/dj/history-view` and `/dj/history-view/fragment`, model attribute `historyFilter` = the lit button), `history.html`
  (the lit button comes from the model; the five buttons are separate `btn-sm` buttons in a `flex-wrap` row — a joined
  `btn-group` of five stuck out 13 px past the card on a 375 px phone), `dashboard.js`
  (`matchesFilter` is gone, `applyFilters` is search only; a filter button → `reloadHistory(limit, filter)` in the History tab — the
  button lights at once, goes back if the request fails, only the newest answer is used, the search text stays, the scroll goes to
  the top; on the standalone page a page load of `/dj/history-view?filter=`; "Show more" carries the chosen filter in both).
  Opening the History tab always starts at All. Messages: `history.filter.guest` ("Guests" / "Goście"), `history.filter.background`
  ("Playlist" / "Playlista").
- **Tests: 380 pass** (366 before; run in a scratch copy: 381 with the scratch renderer, `BUILD SUCCESS`): `HistoryFilterTest` (3, new),
  `PlayHistoryServiceTest` +6 (each filter reads only its tables, the limit counts the chosen kind, no filter = all),
  `DjDashboardControllerHistoryTest` +4 (the filter is passed on, with the limit, unknown → all, the standalone page),
  `HistoryFragmentTest` +1 (only the filter's button is lit; five buttons in order).
- **Verified in the browser** (the harness of the follow-up above: page-driven scenarios, a stand-in server that serves a
  rendered fragment per filter and limit — 400 entries, a guest every 6th, 67 guests of which 16 rejected): 12 steps, all as expected —
  All: 50 mixed (9 guests); **Guests: 50 guests reaching back to "Gość 294"** while the All page ends at entry 49; Playlist: 50 tracks;
  Played: 50, none rejected; Rejected: 16, no "Show more", no "+"; a click on the lit button sends no request; search with a filter
  (1 of 50) and the search text kept across a change of filter; **"Show more" with Guests asks `limit=100&filter=guest`** and gets
  all 67; a failed request puts the previous button back and it works afterwards; two quick clicks — the newest answer stays. The
  standalone page: the buttons navigate to `?filter=guest`, "Show more" to `?limit=100&filter=guest` (seen in the server log; the
  page is not re-rendered by the stand-in); on 375 px the buttons wrap into two rows and stay inside the card (screenshot).
- **Not done:** real devices; a real PostgreSQL run (the queries are the ones that already existed — only which of them run
  changed — so it was not run); the smooth scroll and the hidden-pane limits of stage 4 apply.
- **A finding — the history of the playlist starts over with each round. FIXED afterwards, by the play log (`V7`): see "Follow-up 3"
  below; what follows is what was found at the time.** When the last queued track of the playlist is
  handed out, `FallbackTrackCommandService.startNewRound` → `FallbackTrackRepository.requeuePlayedTracks` puts every played track
  back in the queue and sets its `played_at` to null (including the one that is playing, which then sits at the end of the new
  round). So the "Playlist" rows of the history are at most one round (≤ 500 tracks), and a short playlist that loops during a party
  loses its older rows — and `recent-tracks` loses them too, so ⏮ right after a new round starts has no playlist track to go back to.
  Before this question I described the history as the whole night; that is true only until the playlist loops (120 tracks × ~3.5 min ≈ 7 h,
  so rare with a long playlist, common with a short one). A fix would keep a separate record of plays (a play log, or a `last_played_at`
  that a new round does not clear) — a migration. **Owner (2026-09-29): "zróbmy w nowej sesji"** — see "Next", item 1a, for
  the sketch.

### Follow-up 3 of stage 4: the play log — the history of the playlist survives a loop (done, committed, not pushed)

The task the owner left for a new session (2026-09-29, "zróbmy w nowej sesji"). Built on `dev` on top of `0feabec`, left uncommitted for
the owner's review in IntelliJ, and committed on their "ok, commituj" as a pair (code, then docs; `git log --oneline -2`) — not pushed (`dev` is 8 ahead of `origin/dev` after the two commits that followed: the wake lock and the docs).
Section 4.1 (`fallback_play`), 5.4 ("The history is one timeline", "The history of the playlist does not start over…"), 10 (`V7`),
6.2, 11, 12, 13 and 14 of `PROJECT_CONTEXT.md` describe the result; the short version:

- **What was built.** `V7__fallback_play_log.sql`: the table `fallback_play` (`id` identity, `party_code`, `video_id`, `title`, `fetched_at`,
  `played_at`), the index `(party_code, played_at DESC, id DESC)`, and a copy of the tracks that are `PLAYED` with a `played_at` at that moment.
  New `FallbackPlayEntity`, `FallbackPlayRepository` (`findRecent`, `deleteByPartyCode`, `deleteFetchedBefore`). `FallbackTrackCommandService.takeNextTrack`
  writes one row per hand-out right after the claim (same transaction, same advisory lock; `now` is shared by the claim and the row) and now
  **returns the log row** (`Optional<FallbackPlayEntity>`); `NextTrackService` answers with its id; `PlayHistoryService` reads `findRecent`
  (`FallbackTrackRepository.findPlayedTracks` is gone); `purgeStaleTracks` purges the log by `fetched_at` with the tracks;
  `AccountDeletionService` deletes it. `youtube-autopilot.js`: **a comment only** (`B:<play id>`) — `'B:' + track.id` was already right once the id is the play's.
- **The sketch of the handoff, checked against the code, and what was wrong with it:** (1) "keep the track id in the log and leave the key `B:<track id>`" —
  **wrong**: with a loop `[A, C, B, A]` has one key on both A's, ⏮ from the older A finds the newer (`findIndex` = first match) and goes round in circles
  (reproduced in the browser, below), ⏭ skips entries; so the key is the id of the *play* and `next-track` returns it. (2) The sketch had no `fetched_at`, but
  "purged with `fallback_track`" means by `fetched_at`, so the row copies it. (3) Not in the sketch: the track handed out *at* the boundary was re-queued in the same
  transaction (`requeuePlayedTracks` re-queues every played track, the one just claimed included) and was in the history nowhere — the log fixes that as well.
- **The owner's two decisions** (asked at the start, `AskUserQuestion`). *Replacing/clearing the playlist:* **the log stays.** The prompt assumed that today a change
  of playlist deletes the tracks "and the history with them"; the code says otherwise (`replaceTracks` / `cancelQueuedTracks` only flip `QUEUED → CANCELLED`, `PLAYED`
  rows stay; only the account deletion deletes) — so "keep" is what the code already did, and "clear" would have been a new deletion. *Retention:* the owner
  asked whether the 30 days applies to a history that is "only text" and, after the answer, tentatively picked "until the account is deleted, like the requests" **only
  if the titles are not covered**. I read the policy (WebFetch of developers.google.com/youtube/terms/developer-policies, III.E.4): API Data = data provided through the API;
  **Non-Authorized Data** = accessible without user credentials — the import uses the API key only — "not longer than 30 calendar days" (III.E.4.d); the
  exception of III.E.4.b is for statistics and Authorized Data. Nothing exempts titles, so **30 days from the fetch** was implemented (the owner's own condition for the
  other option was not met). It is my reading of the text, not legal advice, and it is easy to relax (the purge query and one column) if the owner decides
  otherwise — **say so if the pick should have been another one.**
- **Found on the way, not touched:** `song_requests` has **no purge by age** (only the account deletion), while the privacy page says "Song requests: stored for the duration of
  the party session"; and `song_requests.track_url` holds YouTube URLs (video IDs from the search API), i.e. API data too. Worth a look by the owner, separately (see "Open items", 5).
- **Tests:** **386 unit tests pass** in a scratch copy (380 before; +5 in `FallbackTrackCommandServiceTest` — the log row is a snapshot with the claim's time, written under the lock after
  the claim, the same video in two rounds = two ids, nothing written when nothing was claimed, replace/cancel leave the log alone, the purge uses one cutoff for both tables —
  and +1 in `PlayHistoryServiceTest`, the same video in two rounds = two keys; `NextTrackServiceTest`, `AccountDeletionServiceTest` adapted).
- **Verified against a real PostgreSQL 18** (throw-away databases `s2p_play` and `s2p_play_up`, dropped afterwards; the owner's `scan2play` untouched; a `@SpringBootTest` and a plain
  Flyway test that lived only in the scratch copy, dummy API keys in the environment): Flyway V1–V7 on an empty database plus Hibernate validation; **V6 → V7 on existing data** through
  the Flyway API (exactly one migration executed; the four `PLAYED` rows with a time copied in play-time order with title, `fetched_at` and `played_at`; a `PLAYED` row without a time,
  `QUEUED` and `CANCELLED` rows not copied; `fallback_track` unchanged); a 3-track playlist that loops — 8 hand-outs through the real `NextTrackService`: after each the newest entry of
  `recent-tracks`' source has the key `B:<id from next-track>`, 8 distinct keys, `fallback_track` remembers only 2 `PLAYED` rows while the log has 8, and a port of the client's
  ⏮ / ⏭ walk visits every entry once and comes back; the track that opens a new round is in the history; 100 hand-outs of a shuffled 4-track playlist (no repeat in a row, ids in
  hand-out order, all 100 in the history); replacing and clearing the playlist keep the log; the purge (a row fetched 31 days ago goes, 29 stays) and the account deletion (another
  party's log stays); `EXPLAIN` of the history read on 60 000 rows uses the index and no sort; **concurrency:** 3 parties × 4 threads taking 40 tracks each plus the DJ's moves, drops
  and shuffle flips plus the nightly purge in a loop — 480 hand-outs, 480 log rows, no errors, no deadlock; the whole class was run **three times on fresh databases** (and once more when the
  recording for the browser was made). (Two of my own scripting slips turned up on the way and are not code problems: `psql` inherits `PGDATABASE`, so `DROP DATABASE` needs `-d postgres`;
  and the PowerShell tool does not keep environment variables between calls, so every call that runs a Spring test needs the dummy keys again.)
- **Verified in the browser** (harness recreated then **outside the repo**, in the scratch directory — **it is in the repo now**: `src/test/browser`, see "The session of 2026-09-29/30"; what it was: a scratch JUnit test renders the real `dashboard.html`; a Python stand-in server serves it with the
  real `static/js` and `static/css`; a fake `YT.Player` replaces the IFrame API — the fake keeps the real `iframe_api` script from loading by intercepting `document.head.appendChild`; scenarios run
  inside the page (`?scenario=NAME&mode=play|old`) and POST their verdict to the stand-in, which writes a file that is read afterwards, no JS tool needed). The stand-in **does not compute anything**: it
  replays the *real* answers of `next-track` and `recent-tracks` recorded from the real services on the real PostgreSQL (10 hand-outs of A B C looping, the 3rd already opening round 2). Scenario
  `boundary`, mode `play`: Auto-Pilot runs A B C A B; ⏮ after 30 s restarts only; then ⏮ ×6 loads **A4, C3 (across the boundary), B2, A1** and twice "nothing older" (restart); ⏭ ×5 loads **B2, C3, A4, B5**
  (retracing) and then a new track from `next-track`; `next-track` asked exactly 6 times, no `POST /play`. All 6 steps pass. **Negative control**, mode `old` (the same plays keyed by the queue's track id, i.e. what the
  code answered before): the same scenario fails as predicted — ⏮ ×6 gives `a c b a c b` (it circles, never "nothing older"), ⏭ ×5 gives `c a b c a`, and `next-track` is asked 10 times instead of 6 — so the check
  does see the problem. The pane was closed and the stand-in stopped afterwards.
- **Not done:** real devices (the owner should try ⏮ / ⏭ on a phone after a short playlist has looped — best with a 2–3 track playlist); the real Spring Security chain and `DjPlayerLeaseController.recentTracks`
  itself (the test called `PlayHistoryService` and copied the three-line mapping into the recording — the mapping was not run through the controller); the History *page* with background rows from the log was not looked at in a
  browser (the fragment is unchanged, `HistoryFragmentTest` still renders it; only the source of the entries changed); the production first-deploy with `V7` (Section 10's checklist is unchanged, the migration is
  additive); a dashboard window that is open across the deployment holds an old-style key for its running track (one wrong ⏮ at worst, documented in 5.4).
- **Not built, on purpose:** any "clear history" button for the DJ (today only the 30-day purge and "Delete account"), an index on `fetched_at` for the purge (a daily delete on a small table; `fallback_track` has none either).

## The session of 2026-09-29/30: browser tests in the repo, three small fixes, polish, retention (done, committed, not pushed)

The fifth session. The owner's prompt listed five tasks; all but the last were built and left uncommitted for review in IntelliJ
(`CLAUDE.md`); after the owner tried the two back buttons of item 6 ("działa, commituj") they were committed as a pair (code `8248f9c`,
then the docs); nothing was pushed, reset or stashed — `git status -sb` shows `dev...origin/dev [ahead 10]`. The state at the start was as
the prompt said (clean tree, tip `9e10a1f`). `PROJECT_CONTEXT.md` Sections 4.1, 5.4, 6.8, 10, 12,
13 and 14 describe the result; what follows is what is easy to lose.

**The two decisions asked at the start** (`AskUserQuestion`; the owner took the recommended option both times): (1) ⏭ after a change
of playlist: **clear the retracing, let the running old track finish**; (2) skipping a track of the "up next" list: **this round only**.

**1. Browser tests in the repo** (`src/test/browser/`, guide: its `README.md`; `python src/test/browser/run.py`, about a minute):
`run.py` (copies the repo to `%TEMP%\scan2play-browser-tests`, runs `DashboardPageRenderTest` there, starts the stand-in, opens each
scenario in a headless Chrome), `server.py` (stand-in, standard library only), `fake-yt.js`, `harness.js`, `scenarios/*.js` (15
scenarios), `fixtures/play-log-boundary.json`; Java: `template/DashboardPageRenderTest` (renders the real page from the model of the real
`DjDashboardController.dashboard()`), `controller/PlayLogFixtureRecorderTest` (records the fixture; skipped unless `S2P_FIXTURE_OUT` is
set; refuses any database not called `s2p_*`).
- Checked first, as asked: **headless Chrome works here** (Chrome 154, `--headless=new`, fresh `--user-data-dir`; JS runs, the CDN can be
  blocked); a first probe printed "Otwieram w istniejącej sesji przeglądarki" once and never again — `run.py` always uses its own profile and
  leaves no browser process behind. **Bootstrap from the CDN is not needed** (no script uses its API; `run.py` blocks it and every
  scenario passes).
- Based on the old scratch harness (`server.py`, `fake-yt.js`, `scenario.js`, `recorded.json` were found and reused); changes that
  matter: the fixture now holds the JSON **bodies of the real controllers** (MockMvc through the real `DjDashboardController` /
  `DjPlayerLeaseController`, real ownership and lease checks) — before, the mapping of `recent-tracks` was copied by hand; the old-keys
  variant is derived from `trackIdByVideo` instead of being recorded; scenarios configure the stand-in (`POST /__config`) instead of the
  stand-in knowing them; the fake can hold a load in UNSTARTED / CUED, move `Date.now()`, block the API.
- **Two races in the harness itself, found by the new scenarios and fixed** (not app bugs): the first lease report of the page left
  before the scenario had configured the stand-in and got the default "you are the holder" answer (now `fake-yt.js` holds it until
  `fake.start()`); and a `/__config` is *merged*, so `delays: {}` did not remove a delay (set it to 0).
- **The first scenario, as asked:** `boundary` — ⏮ / ⏭ across the round boundary with the real answers (all steps pass) — and
  `boundary-old-keys`, the control with the keys of before the play log: it **fails on all three steps it must fail on**
  (⏮ ×6 gives `a c b a c b`, ⏭ ×5 skips, `next-track` is asked 10 times instead of 6).
- Not covered by a scenario yet: the lease takeover, the lists and their filters, the History tab, the tabs (checked by hand earlier). **Covered since the sixth session** (2026-09-30, see its section below).

**2. The three small JS fixes** (`youtube-autopilot.js`; each scenario was run against the unfixed script first and failed as expected):
- (a) `DOUBLE_PRESS_MS` = 20 s; tooltip `dashboard.player.previous.title` PL and EN by script (`add`-style byte replace, CRLF kept, one-line
  diffs), `PlayerControlsFragmentTest`, `PROJECT_CONTEXT.md` 5.4 and 14. Scenario `double-press-window` (19 s vs 21 s, and reads the tooltip).
- (b) "Wznów" while a track loads: `isPlayingOrLoading()` = playing, or `isLoadingSong` and not paused and less than 10 s since the load,
  used for the button of the window that plays and for `playing` in its lease reports. **A deviation from the prompt, on purpose:** the
  prompt said "count `isLoadingSong` as playing", plain; I added the 10 s limit and the "not paused" condition because a browser that
  refuses sound in a page nobody touched leaves the player at CUED for good and `isLoadingSong` never clears — the button would say
  "Pauza" for ever and the DJ could not press "Wznów" to start it. Easy to drop (one condition). Scenario `pause-while-loading` (held UNSTARTED and
  CUED: the report says `playing=true`, the button "Pauza"; a load stuck for 11 s: `false` / "Wznów", and "Wznów" starts it; a real pause).
- (c) ⏭ after a playlist change: `notePlaylist` (the lease answer names another playlist than the last one ⇒ `playingFromHistory =
  false`, only from a report sent after the track came back — `trackLoadedAtLeaseSeq`, like `dropStaleBackgroundTrack`), and
  `updateFallbackSource` / `stopFallback` clear it in the window where the DJ acted (so also on **Stop**, and when the **same playlist
  is saved again** — my reading of "the playlist changes"). Five scenarios (Section 5.4); `retrace-survives-a-change-in-flight` turns
  red when the `trackLoadedAtLeaseSeq` condition is removed (tried).

**3. Polish** (messages PL and EN by script, `CLAUDE.md`):
- **Import result:** `showFallbackImportResult` in `dashboard.js` + `#fallbackImportStatus` in `dashboard.html` — green "Playlista zapisana.
  Utworów w kolejce: N." / red with the reason (`NO_API_KEY`, `INVALID_PLAYLIST`, `API_ERROR`, `NO_PLAYABLE_TRACKS`, other), the Save
  button ✓ green or ✗ red, hidden after Stop. The failure texts say the link *is* saved but nothing will play from it for now (true:
  the queue is empty until a later lazy import). Scenario `import-result` (fails with the feature disconnected — tried).
- **"Czeka N piosenek gości":** `updateGuestsWaiting`, counts playable rows (`?v=` + 11 characters) of the polled queue table; the plural
  form by `Intl.PluralRules` — **`dashboard.html` had `lang="en"` hard-coded** (found here; fixed in the sixth session), so the language travels in `data-lang`.
  A client-side count instead of a server endpoint was my choice (no new query, follows the 3 s poll). Scenario `guests-waiting`.
- **Skipping a track (this round):** `V8`, `FallbackTrackStatus.SKIPPED`, `FallbackTrackCommandService.skipTrack`,
  `POST /dj/dashboard/fallback-queue/skip`, ✕ on every row, "Skipped this round: M" in the caption. Design points I decided: the status
  is new because `PLAYED` with a `played_at` would have broken the anchor of "shuffle off" (`ORDER BY played_at DESC` puts NULL first),
  and `V2`'s check constraint had to be widened anyway; `requeuePlayedTracks` re-queues `PLAYED` and `SKIPPED` (one statement); skipping the
  **last queued** track ends the round and starts the next at once (there is always a "next"), the skipped track at its end; skipping
  writes nothing to the play log. Checked on a real PostgreSQL 18 (`s2p_skip`, `s2p_skip_up`, dropped): see 5.4 — including **V7 → V8
  on existing data** and 159 skips + 360 hand-outs + moves/drops/shuffle flips/purge from three parties at once, no deadlock.
  Scenario `skip-track`.

**4. Retention of `song_requests`: 30 days from `requested_at`** (the owner's decision): `SongRequestRetentionService.purgeStaleRequests`
(04:45), a bounded native delete (`SongRequestRepository.deleteRequestedBefore`: batches of 1000, each its own transaction, at most 200
batches a night), `SongRequestEntity.MAX_AGE_DAYS`; "Data Retention" of `privacy.html` / `privacy_pl.html` corrected (**and I added a
bullet for the playlist tracks and the play log — 30 days, true since V2/V7 and not on the page**). Checked on a real PostgreSQL
(`s2p_purge`, dropped): 29 d 23 h kept / 30 d 1 h deleted, every decision, another party's rows and a NULL date left alone; 250 000 old
rows: the first night deletes 200 000 (834 ms), the second the rest; a night with nothing to delete 4 ms; while requests are inserted and
the queue fingerprint is read: no error, nothing recent lost; the account deletion still works. **No new index:** PostgreSQL 18 answers
with a skip scan of `idx_party_decision_time` (1.9 ms for nothing to delete among 300 000 rows), and a forced sequential scan of 300 000
rows took 155 ms. If production runs an older PostgreSQL it is the sequential scan — fine for this table.

**5. NOT built — the Auto-Pilot hint and the IFrame-API message** (the prompt: only after the friend's steps are reproduced on a fresh
party on the owner's phone; the owner was not there). Instead the harness has **three scenarios that state what each suspected state
looks like today** (`scenarios/silent-states.js`; they pass and are meant to be changed when something is built): `silent-autopilot-off`
(a new party: nothing is asked, nothing plays, no word about it — and switching Auto-Pilot on starts the music at once),
`silent-iframe-api-blocked` (no player is made, nothing is asked, ⏭ silently does nothing, no message), `silent-lease-elsewhere` (the banner
shows; nothing is asked). **A question for the owner is in "Next", 2** — asked at the end of the session; the owner's answer: wait for the
phone test, build nothing yet.

**6. Added afterwards, the same day — two back buttons in a window that does not play** (the owner, after trying ⏮ from the phone:
"wstecz nie jest intuicyjne; czy możemy mieć 2 przyciski na odtwarzaczu niegrającym? Wstecz i od początku"). Read as: in the window
that **does not play** (the phone as a remote) the single ⏮ with its rules (restart first, back on a second press within 20 s) is replaced
by **⏮ Wstecz** (always the previous track) and **↺ Od początku** (the current track from the start); the window that plays keeps the
single ⏮ — **my reading of "niegrającym"; if the owner meant every window, it is one line: `renderBackButtons` in `youtube-autopilot.js`
(`remote = isPlayerDevice === false`) and the single button could go** (with `DOUBLE_PRESS_MS`, `restartedByBackJustNow` and their scenario).
New commands `PlayerCommand.PREVIOUS_TRACK` and `RESTART` (`PREVIOUS` stays, accepted, for a page opened before); the script: `goToPreviousTrack`,
`restartTrack`, `runCommandHere` (one dispatch for a press here and a command from another window — the old local code ended in
`else resumeHere()`), `renderBackButtons`; `fragments/player-controls.html` has the two buttons hidden (`d-none`) until the window learns it does not
play; messages `dashboard.player.back.title`, `dashboard.player.restart`, `dashboard.player.restart.title` (by script) and the tooltip of the
single ⏮ lost its last sentence ("in a window that does not play the command is sent on"). Decisions: RESTART of a **paused** track keeps it
paused; RESTART when the track **ended** with Auto-Pilot off plays it again from the timeline; "Wstecz" with nothing older restarts the
track (like the single ⏮). Four scenarios (`scenarios/back-and-restart.js`; two mutations tried: without `renderBackButtons` the buttons do not swap, and with
`PREVIOUS_TRACK` routed through the single ⏮'s rules it restarts after 30 s instead of going back) and nine unit tests (`PlayerControlsFragmentTest`,
`DjPlayerLeaseControllerTest`, `PlayerLeaseServiceTest`); the stand-in got `commandStatus`. Tried by the owner on the phone: "działa" (2026-09-30). The suite is now 429 tests
(1 skipped) and 19 browser scenarios, all passing.

**7. After the commit pair, from the owner's screenshots (committed in the sixth session's pair, `50558eb` and the docs after it).** The owner tried the skip: "X działa na playliście" (the
✕ works on a playlist). Two remarks: (a) **a plain video link can be saved as the "playlist"** (it becomes one track, playlist id `V:<id>`) and then ✕
does nothing visible — the round starts over with the same video; (b) **on the phone a row with the badge "Następny" squeezed its title to three letters a
line** (the fifth button, ✕, which I had added, took the room). Done: (a) `FallbackQueueView.singleVideo` (from the `V:` prefix; a 6-argument
constructor and the 5-argument one are kept) and the ✕ of a single video is **disabled** with the tooltip `dashboard.fallback.queue.skip.single` ("…nothing to skip
to. Stop removes it"); a real playlist with one track left in the round still skips (visible: the next round starts) — my choice, the owner only described
what happens; (b) the row of `fragments/fallback-queue.html` **wraps**: the title has `flex: 1 1 10rem` and the badge, the ↗ link and the buttons are one
group (`ms-auto`) that drops under the title when the row is narrow. **Looked at in the built-in browser** (the stand-in server on the real rendered
dashboard, Bootstrap from its CDN) at 375 × 812 and 1280 × 800 with the titles of the owner's screenshots (the sample `fallback-queue.html` of
`DashboardPageRenderTest` has them now): on the phone the title has the whole line and the buttons sit under it on the right; on a wide screen a short title and its
buttons stay on one line. The hidden pane only scrolls when `scroll-behavior` is set to `auto` first. Four unit tests (`FallbackQueueFragmentTest` ×3,
`FallbackQueueServiceTest`), 429 tests in all (1 skipped), 19 scenarios pass. Not tried on the phone yet. Files: `FallbackQueueView`, `FallbackQueueService`,
`fragments/fallback-queue.html`, `messages*.properties`, `DashboardPageRenderTest`, `FallbackQueueFragmentTest`, `FallbackQueueServiceTest`, `PROJECT_CONTEXT.md` (5.4, 13), this file.

**Files changed** (all in the commit pair `8248f9c` + docs, except what item 7 lists): `AGENTS.md`, `.github/copilot-instructions.md`, `CLAUDE.md`, `PROJECT_CONTEXT.md`, this file; main:
`DjFallbackQueueController`, `SongRequestEntity` (a constant), `FallbackQueueView` (+ `skipped`, a 5-argument constructor kept),
`FallbackTrackStatus`, `FallbackTrackRepository`, `SongRequestRepository`, `FallbackQueueService`, `FallbackTrackCommandService`,
**new** `SongRequestRetentionService`, **new** `db/migration/V8__fallback_track_skipped.sql`, `messages*.properties`, `dashboard.js`,
`youtube-autopilot.js`, `dashboard.html`, `fragments/fallback-queue.html`, `fragments/player-controls.html`, `PlayerCommand`, `privacy.html`, `privacy_pl.html`; tests: `DjFallbackQueueControllerTest`,
`FallbackQueueServiceTest`, `FallbackTrackCommandServiceTest`, `FallbackQueueFragmentTest`, `PlayerControlsFragmentTest`, `DjPlayerLeaseControllerTest`, `PlayerLeaseServiceTest`, **new**
`SongRequestRetentionServiceTest`, `DashboardPageRenderTest`, `PlayLogFixtureRecorderTest`; **new** `src/test/browser/`. **`V8` is new: the
owner's local database applies it at the next restart** (after that the local `scan2play` has `fallback_track_status_check` with `SKIPPED`;
nothing else changes there). The first production deploy: the Flyway checklist of Section 10 is unchanged.

**What was checked and what was not.** Checked: 425 unit tests (`BUILD SUCCESS`, in a scratch copy); 19 browser scenarios (all pass; the
control fails as it must); V8, the skip and the purge against real PostgreSQL databases (all dropped, the owner's `scan2play` untouched —
the recorder and the scratch tests were pointed only at `s2p_*` databases); the recorder's recipe from the README run verbatim. **Not
checked:** anything on a real device or with the real YouTube player (sound, the real events between two videos, the autoplay policy);
the messages in the real dashboard behind the login (the render test and the scenarios use the real templates and bundles, not the
running app); the skip and the import result by the owner's hands; the smooth scroll; the browser tests in CI (there was none — a workflow was written in the sixth session); the first
production deploy with `V8`; the purge on the production database (it runs at 04:45 after the deploy).

**Two things that went wrong, for the record.** (1) A `Write` of a scratch file with a relative path (`..\..\Users\…`) resolved on drive
**D:** and created an empty directory tree `D:\Users\lasut\AppData\Local\Temp\claude\…` (the file in it was deleted at once; the tool refuses
to delete the protected directory `D:\Users`, so the empty skeleton is left — **delete `D:\Users` by hand**). (2) The command checker
misread a harmless `Remove-Item Env:\PGDATABASE` in a PowerShell command (a blocked "system path") — nothing ran; the call was repeated
without that line (every `psql` here passes `-d postgres`, so the variable need not be unset).

## The session of 2026-09-30, the sixth: more browser scenarios, the language of the page, one endpoint less, CI (done, committed, not pushed)

The state at the start was as the prompt said: `dev...origin/dev [ahead 10]`, tip `5f586f5` (docs) over `8248f9c` (code), and the ten uncommitted
files of item 7 of the fifth session (checked with `git status -sb` and `git log --oneline -6`). Nothing was pushed, reset or stashed; the work
was left uncommitted for review in IntelliJ and, on the owner's "ok, commituj", **committed as one pair — code `50558eb`, then the docs — with
item 7 of the fifth session in it** (its files are the same ones: the message bundles, `DashboardPageRenderTest`, this file, `PROJECT_CONTEXT.md`);
not pushed, `dev` is 12 ahead of `origin/dev`.

**The two questions asked at the start** (`AskUserQuestion`): (1) *were the friend's steps reproduced on a phone, and which state was it?* — the
owner: "zrobiłem to na innym laptopie i zadziałało. Musiałbym koleżankę jeszcze raz poprosić. Dam znać" — so **nothing was built** for the
Auto-Pilot hint or the IFrame message and the `silent-*` scenarios are untouched (see "Next", 2); (2) *what of the list's other side?* — the
owner chose **all four**: more scenarios, `lang`, the unused endpoint, browser tests in CI.

**1. Scenarios for what had none** (`scenarios/lease.js`, `lists.js`, `tabs.js`; 27 scenarios now, 19 before). What each checks is in
`PROJECT_CONTEXT.md` Section 5.4, "Testing"; in short: the lease (`lease-lost-and-taken-back`: the window stops, only watches, "play here"
asks first and a "no" sends nothing, a "yes" takes it and plays; `lease-free-takeover-asks-nothing`; `lease-released-on-leaving`: the beacon
of `pagehide`, and none from a window that does not play), the queue list (`queue-list`: "zolc" finds "Żółć", count, "nothing matches",
and the search, the scroll position and the column sort survive a poll), the History tab (`history-tab`: the real fragment, filters,
"Show more" with the chosen filter, the search text kept, a failed request puts the button back) and the tabs (`tabs`: a click lights the tab
and brings its part under the bar — the smooth scroll —, History loads once, Panel keeps the list that shows, the lit tab follows the scroll).
- **Harness changes to feed them:** `server.py` answers `GET /dj/history-view/fragment` with one rendered file per filter (`history-<filter>.html`,
  and `-more` for a request with a limit), has the new state key `historyStatus`, and its answer to the poll now has the song cell that the
  column sort reads **and the real "nothing matches" row** — found while writing `queue-list`: that row is *inside* the polled `<tbody>`
  (`dashboard :: songTableBody`), so an answer without it made the search box behave differently from the real page after the first poll;
  it is taken from the rendered page by a regular expression, not copied by hand. `DashboardPageRenderTest` renders the extra pages —
  `dashboard-en.html`, `history-<filter>[-more].html` (through the real `DjDashboardController.historyFragment`, ten sample entries, the real
  `HistoryFilter` deciding what a filter includes) — and checks more ids (`queueList`, `data-list-search`, `djTabBar`, `data-dj-tab=…`).
- **These scenarios could not be seen red the usual way** (the code worked), so each was **mutation-checked**: one line of the script taken out
  in a copy — 17 mutations, **all killed for the right step**. One mutation survived at first and was an *equivalent* one, not a hole:
  removing the `scroll` listener changes nothing, because Chrome's `scrollend` runs the same function; replaced by one that really stops the
  lit tab from following (killed). The mutation script is **not in the repo** (it lived in the session's scratch directory): it is ~100 lines
  that `import run, server`, `run.mirror` the repo into a *second* work directory (never the repo, never the usual work directory: the
  mutation must not reach the files IntelliJ watches), break one string of one script, and `run.run_scenario` the scenarios that must go red.
  The README says how to do it by hand.
- **Two things a run taught:** in a *Polish* Chrome `ż` sorts after `z`, in an English one it does not, so my first expectation ("Zulu more
  first") was wrong on this machine and would have been wrong in another way in CI — the sort assertion now names no exact neighbour; and
  `tabs` is the first scenario that looks at scrolling, so it first checks that the browser does **not** ask for reduced motion (the script
  scrolls instantly then, and the smooth path — the one the DJ gets — would go untested without a word).
- **The lease is scripted, not simulated:** who holds it is a config of the stand-in (`lease: {holder, free}`), so "another window took over" is a
  change of that config; the rules of the server (who gets it, the 10 s timeout) stay unit-tested. No scenario runs two real windows.
- Timing: each of the eight new scenarios was run four times (first, in the full run, twice more) — no flake.

**2. `<html lang>` of `dashboard.html`.** `th:lang="#{html.lang}"` and the key `html.lang` (`en` / `pl`) at the top of both bundles (ASCII, so no
escapes; added by a script that keeps the CRLF). **Not `${#locale.language}`** — my choice, and the reason: this app has no `messages_en`, so a
German (or any other) browser gets the *default* bundle, the English texts, while `#locale.language` says `de`; the bundle that wrote the texts
is the only thing that always agrees with them. `DashboardPageRenderTest` pins it (Polish, English, and German → English texts and `lang="en"`),
`page-language` was red before the fix (it said `en` over Polish texts) and `page-language-en` covers the other side. **The eight other templates
still have `lang="en"`** (`landing`, `index`, `history`, `result`, `error`, `party_ended`, `terms`, `privacy`; the `_pl` legal pages have `pl`):
the owner named only the dashboard — see "Next", 2. `data-lang` on the guests line was left alone (it works, and both say the same).

**3. `GET /dj/dashboard/next-guest-track` removed**: the method of `DjDashboardController` (and its now unused import), and
`DjDashboardControllerNextGuestTrackTest` (six tests — the file is deleted from the working tree, so it shows as ` D`; it stays in git
history). `DjService.findNextPlayableGuestTrack` and `NextGuestTrackResponse` **stay** — `NextTrackService` uses them; their comments (which
still spoke of a client polling the endpoint) were corrected. Section 12 and Section 14 of `PROJECT_CONTEXT.md` say so.

**4. Browser tests in CI: `.github/workflows/browser-tests.yml`** — `ubuntu-latest`, Java 21 (Temurin, Maven cache), Python 3.12, the Chrome the
runner has, `python src/test/browser/run.py --no-sandbox --work "$RUNNER_TEMP/scan2play-browser-tests"`; on every push to `dev` / `main`, every
pull request and by hand; a failed run keeps `results/*.json` as the artifact `browser-test-verdicts`. Only the browser tests — the unit tests
are not in it (a second job would be four lines; nobody asked). Changes in `run.py` for it: `mvnw` is started with `sh` on Linux (it is committed
with mode 100644, so a checkout there could not run it directly), a new `--no-sandbox` option, and a Chrome that exits before the scenario finishes
ends the wait after a few seconds (before: the whole 90 s timeout, for each of 27 scenarios).
**It has never run on GitHub.** What was checked here: its YAML parses (SnakeYAML from `~/.m2`, through `jshell` — PyYAML is not installed),
`sh mvnw -v` works in Git Bash, `run.py --no-sandbox` passes on Windows. What was not: the Linux branch of `run.py` as a whole, the runner's Chrome,
the action versions (`checkout@v4`, `setup-java@v4`, `setup-python@v5`, `upload-artifact@v4` — written from memory, not looked up).

**What was checked and what was not.** Checked: **426 unit tests, `BUILD SUCCESS`** (a scratch copy, `%TEMP%\scan2play-unit`; 425 run, 1 skipped);
**27 browser scenarios all pass** (a full run, then `tabs` again after its last step was added, and the eight new ones twice more); 17 mutations
killed; `page-language` red before green. **Not checked:** the workflow on GitHub (above); anything on a real device; the look of anything in
the built-in browser (nothing changed visibly; `tabs` measures positions and scrolling, not how anything looks); the running app's dashboard
(the render test and the scenarios use the real template and bundles, not the app behind the login — so `lang` was not seen in a real
response); no SQL, entity or migration was touched, so no PostgreSQL run was needed (`V8` is still the newest, the next would be `V9`).

**Three things that went wrong, for the record.** (1) I removed the endpoint's test file with `git rm --cached`, which staged the deletion and
left the file on disk; I caught it in the next `git status`, undid it (`git reset -q HEAD -- <that file>`) and deleted the file normally — the index
is as before. (2) I first added lines about CI and the mutation check to `CLAUDE.md`, `AGENTS.md` and the copy in `.github/` — standing
instructions the request did not name — and took them out again; they are unchanged (if the owner wants them: one sentence each, "a scenario for
code that already works is checked by breaking that code in a copy, never in the repo", and a pointer to the workflow). (3) PowerShell wraps
`stderr` of a native command as an error when it is redirected with `2>&1` (the Maven warnings looked like a failure): the runs here did not use it.

**Files changed in this session** (on top of the ten of item 7): `.github/workflows/browser-tests.yml` (new), `src/test/browser/`: `run.py`,
`server.py`, `README.md`, `scenarios/lease.js` `lists.js` `tabs.js` `page-language.js` (new); `src/main`: `DjDashboardController`, `DjService`,
`NextGuestTrackResponse` (comments and the removed endpoint), `templates/dashboard.html`, `messages.properties`, `messages_pl.properties`;
`src/test`: `DashboardPageRenderTest`, `DjDashboardControllerNextGuestTrackTest` (deleted); `PROJECT_CONTEXT.md` (5.4, 6.8, 12, 13, 14),
this file. No migration.

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

Updated 2026-09-30, at the end of the sixth session (the one of "The session of 2026-09-30, the sixth").

1. **The owner's own small steps**
   - **The work of the sixth session is committed** (code `50558eb`, then the docs — one pair, with item 7 of the fifth session in it: ✕
     disabled for a single video and the wrapping "up next" row, which the same files carried; the sixth session's section lists them).
     Of it the owner has tried nothing by hand yet: the phone check of item 7 is in "Try on the phone" below.
   - **The work of the fifth session is committed** (code `8248f9c`, then the docs — one pair, because the parts share files:
     `dashboard.js`, `youtube-autopilot.js`, the message bundles, `PROJECT_CONTEXT.md`). Of the things in it the owner has tried only the
     two back buttons so far ("działa"); the rest is in the "Try on the phone" item below.
   - **Push.** `dev` is 12 commits ahead of `origin/dev` (the two of "Follow-up", the two of "Follow-up 2", the two of "Follow-up 3", the wake lock and the docs of the session that followed, and the pairs of the fifth and the sixth session); they wait for the
     owner's word. **The first push that contains `.github/workflows/browser-tests.yml` starts the workflow on GitHub — the first time it
     runs anywhere but on a developer's machine** (it was written without being able to run it): look at that run; the verdicts of a
     failed one are kept as an artifact (`browser-test-verdicts`). If it is red on the runner and green here, the suspects are the Chrome
     flags (`--no-sandbox` is passed), the locale of the machine (a sort test must not depend on it; see the README) and timing.
   - **Try on the phone** — real devices are still not covered: ✕ on a row of "up next" (and what the list and the "Skipped this round"
     say afterwards); Save with a private playlist and with a wrong link (the red line); "Czeka N piosenek gości" while a guest song
     is queued; the pause button while ⏮ rewinds; ⏭ after saving another playlist; the two back buttons on the phone with the computer
     playing ("Wstecz", "Od początku") — **tried, "działa"** — and whether the single ⏮ on the computer still feels right; the 20 s window of the second ⏮ now matters only at the computer.
   - **`YOUTUBE_API_KEY`: rotated by the owner on 2026-09-29** (a new key put in the IntelliJ run configuration and in Railway). Still to do: **delete the old key** in Google
     Cloud Console → APIs & Services → Credentials → *API keys* once nothing uses it, and check that the new one is restricted to the YouTube Data API v3. A restriction alone would not have
     helped: a key that was pasted into a chat can still spend the YouTube quota. The page the owner showed on 2026-09-29 was a different thing — the *OAuth client secrets* (`GOOGLE_CLIENT_SECRET`; `****pfTe` enabled,
     created 10.03.2026, and `****IgiS` disabled, created 31.03.2026): nothing is known to have leaked there; the disabled one cannot authenticate, so it can be deleted
     to silence Google's "more than one secret" warning, but the enabled one must stay the one the app uses.
   - **Local `main` and the wake lock:** see "Open items", 1.
   - **Delete `D:\Users`** (an empty directory tree created by a mistaken relative path of the fifth session; the tool would not delete it).
2. **The next session with code**
   - **The Auto-Pilot hint and the IFrame-API message — STILL WAITING; do not build on the hypothesis.** *(The sixth session asked
     whether the friend's steps had been reproduced: the owner tried them on another laptop and it worked — no reproduction, no state
     to name. The owner will ask her again and say which state it was, or that it did not repeat. Until then nothing is built and the
     `silent-*` scenarios stay as they are.)* The
     hypothesis (from the friend's screenshot: the first playlist track still "Następny" with 120 left, i.e. the window never asked
     `next-track`): a new party starts with Auto-Pilot off and with it off nothing starts by itself; the YouTube IFrame API may be blocked
     (an ad blocker, Brave shields) and that failure is silent; another window may hold the player lease (that one has a banner).
     The harness now shows what each state looks like **today** (`silent-autopilot-off`, `silent-iframe-api-blocked`,
     `silent-lease-elsewhere` — Section "The session of 2026-09-29/30", 5): all three are silent except the lease one. What is
     needed from the owner: **try the friend's steps on a fresh party on the phone (`https://dev.scan2play.com.pl`) — with Auto-Pilot
     off, with the IFrame API blocked, and with a second window holding the lease — and say which one it was** (or that none of them
     reproduces it). Proposed, unchanged: a hint next to the player while Auto-Pilot is off, with a button that switches it on, and a
     message when the IFrame API does not load (the script has no handler for the failed load of `iframe_api`; the fake can imitate it:
     `fake.blockApi`). Not proposed: making AUTO the default — it also applies to Spotify parties, where AUTO puts accepted songs
     straight into the Spotify queue. When something is built, change the last step of the matching `silent-*` scenario.
   - ~~More scenarios (the lease takeover, the lists and their filters, the tabs, the History tab), the browser tests in CI, `lang="en"`
     of `dashboard.html`, the unused `GET /dj/dashboard/next-guest-track`~~ — **all four done in the sixth session** ("The session of
     2026-09-30, the sixth", above). What they leave: the first run of the workflow on GitHub (item 1, "Push"); the eight other
     templates that still have `lang="en"` written in (`landing`, `index`, `history`, `result`, `error`, `party_ended`, `terms`,
     `privacy` — the same one-attribute fix with the `html.lang` key, and a render test each; `PROJECT_CONTEXT.md` Section 13 lists them);
     `data-lang` on the guests line could now be read from `document.documentElement.lang` instead of its own attribute (no need to, both
     say the same and a scenario pins that); no scenario runs **two** real windows (the lease scenarios script the server's answers);
     the unit tests run in no CI (the workflow could get a second job with `mvnw test "-Dtest=!Scan2playApplicationTests"`, no database
     needed).
3. **Polish — done** in the fifth session (the import result, "Czeka N piosenek gości", skipping a track for this round): see its section.
4. **Production.** The owner: at the next go-live. Then the Flyway checklist ("Open items", 3) comes first — with `V8` in it (it replaces the check constraint of `fallback_track`; the first production deploy applies V2 to V8 in one go) — and `dev` → `main` is the next real decision — the owner does **not** want to merge yet (2026-09-29).
5. **Parked idea: a "music only" checkbox that skips the intro of a music video** (asked 2026-09-29, checked, the owner decided not to build it now). Feasible, not free: no library derives it from the
   audio — the IFrame API gives no access to it and downloading it is against the API policy (III.I.7) — but **SponsorBlock** has a category `music_offtopic` ("Non-Music Section", music videos only)
   and a public API: `GET https://sponsor.ajay.app/api/skipSegments?videoID=<id>&category=music_offtopic` answers a list of `{segment: [start, end], votes, locked, ...}` and 404 for a video without data. Six
   well-known videos were tried: four had a segment at the start (Despacito 0–21.8 s, Gangnam Style 0–4.0 s, Shape of You 0–6.05 s, Bohemian Rhapsody 0–1.9 s), two returned 404 — most intros are a few seconds,
   coverage is patchy, and 404 means "nobody marked it", not "no intro". The player can start at a second: `loadVideoById({videoId, startSeconds})` (documented; all loads go through
   `loadIntoPlayer`). **The blocker is the licence:** the API and the database are CC BY-NC-SA 4.0 — attribution, and no commercial use without the maintainer's permission (who says they may grant another
   licence on request). Scan2Play is used only by the owner for now; if it grows or earns money, ask first. If it is ever built: one client class behind a property such as
   `scan2play.sponsorblock.enabled`, a short timeout and a cache (also for 404) so that `next-track` is never slowed, always fall back to starting at 0, `startSeconds` in the `next-track` answer and in
   `recent-tracks` (⏮ and the restart of ⏮ must use it too), attribution, a line in the privacy page (video IDs go to a third party), and only the intro through `startSeconds` — no `endSeconds` (its
   effect on `ENDED`, which Auto-Pilot relies on, is not documented), no seek loops, no audio-only mode (III.I.6 and III.I.7). The wiki of SponsorBlock is behind bot protection, so its rate limits were not read.

## Open items for the owner

1. **Local `main` (tidied up 2026-09-29).** `origin/main` is still the old pre-session state (`5314006`). Local `main` holds two commits of the owner's from
   2026-04-06/07 that exist only on this computer; what was done with them:
   - `33eef2a` **Screen Wake Lock** (`wake-lock.js`, four lines in `dashboard.js`, a `<script>` in `dashboard.html`; keeps the display on while Auto-Pilot is on). It cherry-picks onto `dev`
     without conflicts (the hook it uses, `submitAutoPilotToggle`, still exists), so it was **cherry-picked onto `dev` and committed** on the owner's word (2026-09-29; `git cherry-pick --no-commit`, then `git commit -C 33eef2a`, so the original author,
     date and message are kept). Not exercised in a browser (no Node here to even syntax-check it; the hidden browser pane
     never reports the page as visible, and the API refuses a lock for a hidden page) — try it on the phone: Auto-Pilot on, leave the screen alone, it should not dim.
   - `b3870d6` **environment-agnostic configuration** was **not** brought over: its `{baseUrl}` redirects for the Spotify and Google login are on `dev` already, and its remaining change,
     `scan2play.guest-url=${BASE_URL:http://localhost:8080/}`, is another design than `dev`'s (default `https://www.scan2play.com.pl/`, overridden by `SCAN2PLAY_GUEST_URL`, `PROJECT_CONTEXT.md` Section 10)
     — with it the QR code would point at `localhost` in production wherever `BASE_URL` is not set.
   - **Done on the owner's OK (2026-09-29):** both commits were pushed to the branch `origin/backup/local-main-2026-04` (checked on the remote: it contains `33eef2a` and `b3870d6`), and
     local `main` was set back to `origin/main` (`5314006`), so nothing is left only on this computer and `main` cannot publish `b3870d6` by accident. Do **not** push `main` from that branch:
     it would publish `b3870d6` on the production branch. The backup branch can be deleted once the wake lock is committed on `dev` and the owner has decided about the `guest-url` design.
2. **`YOUTUBE_API_KEY`**: the production key was pasted into a chat/screenshot on 2026-09-28. **Rotated by the owner on 2026-09-29** (IntelliJ and Railway); the old key is still to be deleted
   in Google Cloud Console (see "Next", 1).
   Local run configuration: the variable name must have no stray characters (it had a trailing `:`, which silently disabled the key).
3. **First production deploy** of Flyway: backup, compare `pg_dump --schema-only` with `V1__baseline.sql`
   (checklist in `PROJECT_CONTEXT.md`, Section 10). The production schema has never been checked against `V1`. The owner: when going to production again.
4. Spotify locally: see the stash above; the Spotify Developer Dashboard also needs the redirect URIs.
5. **`song_requests` had no retention by age — fixed, awaiting review** (found 2026-09-29, while deciding the retention of the play log). Only the account
   deletion removed them, while `privacy.html` said "Song requests: stored for the duration of the party session" — the text and the code
   disagreed. And `song_requests.track_url` holds YouTube URLs with video IDs that came from the search API (YouTube API data, at most 30
   calendar days by the API Services Developer Policies III.E.4.d, see "Follow-up 3"). **Decided by the owner (2026-09-29): purge them — BUILT
   2026-09-30** (30 days from `requested_at`, `SongRequestRetentionService`, the privacy pages corrected; see "The session of 2026-09-29/30", 4 —
   committed, not pushed). What the owner may still want to look at: the privacy pages now also say that the playlist
   tracks and the play log are deleted after 30 days (a bullet added, true since V2 / V7 and not on the page before).

## History — first session (2026-09-28, remote Claude Code)

That session attached the repo, committed the three previously local-only docs (`PROJECT_CONTEXT.md`, `AGENTS.md`,
`.github/copilot-instructions.md`) to `dev`, fixed a stale Section 5.4, wrote the "Master Queue" roadmap (Section 14),
found that its `PENDING`/`APPROVED` guest-approval gate never existed in the code, shipped Phase 1 (server-side
`next-guest-track`, `e811ba4`) and replaced the hardcoded production OAuth2 login redirect URIs with
`{baseUrl}` templates. Its Phase 1 change had not been build-verified; that was done in the second session.
