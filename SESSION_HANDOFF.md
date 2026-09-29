# Session Handoff — 2026-09-29

Where the work stands, for whoever continues (a new Claude Code session or a person). The history of the
first session (2026-09-28, remote) is summarised at the bottom.

## Start here (written at the end of the 2026-09-29 session)

- Phase 3 (the DJ sees and reorders the playlist queue — buttons **and drag and drop**) is finished, was tried by the owner
  on a real phone (Galaxy S25, Chrome; "everything worked", 2026-09-29) and is committed on `dev` as two local commits
  (code `fc7252d`, docs `808bf4c`). Phase 4 stage 0 is committed on top as two more (below). **Nothing is pushed yet** —
  six local commits are ahead of `origin/dev` (Phase 2 stages 4–5, Phase 3, Phase 4 stage 0); push only when the owner
  says so.
- The phone login problem is solved: see **"Trying the DJ dashboard on a phone"** below (the owner reaches the local app on
  `https://dev.scan2play.com.pl`, which is registered in the Google OAuth client).
- The owner's next requests (2026-09-29): previous/next buttons for the DJ, like a normal player, and a way to make the
  active queue and the history readable when they hold many songs. Agreed plan = Section 14, **Phase 4**, three stages.
  **Stage 0 ("one window plays") is done and committed** (the owner tried it on the computer and the phone: "works well") —
  see "Phase 4, stage 0" below. **Stages 1 and 2 are not started**; the owner has answered the open questions of stage 1
  (2026-09-29): *Next* is a **remote control** — pressed in a window that does not play it makes the window that plays skip
  (via the lease reports), not a takeover and not a hidden button; and the queue/history lists may have their own scroll
  box on a phone. Ask the owner "shall I start stage 1?" before building it — it was not started because the last
  message asked for a commit, not for stage 1.
- Working agreements are in `CLAUDE.md` (leave changes uncommitted until the owner has reviewed them, never touch the
  `scan2play` database, test in a copy of the repo, CRLF, secrets).

## Where things stand

- Branch `dev`: `origin/dev` = `0519d78` (the 8 commits below plus one docs commit that added `CLAUDE.md` and this
  file). **Phase 2, stages 4 and 5 and Phase 3 are committed on top of it as four local commits and NOT pushed yet** —
  Phase 2: first the client code (`youtube-autopilot.js` rewritten, `dashboard.js`, `dashboard.html`), then the docs
  (`PROJECT_CONTEXT.md` with Section 5.4 rewritten and Sections 5.1/13/14 updated, `AGENTS.md`,
  `.github/copilot-instructions.md`, this file, and three stale comments in `DjPartySettingsController` /
  `DjDashboardController` — comments only). Phase 3 (below): first the code and tests, then the docs.
  `git status -sb` shows whether they have gone out. `main` is untouched (`5314006`).
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
- One stash, deliberately parked: *"Spotify playback redirect-uri as {baseUrl} template (parked: Spotify rejects
  http://localhost)"*. It makes `spotify.oauth.redirect-uri` follow the request host like the login flow does.
  Spotify only accepts HTTPS or a loopback IP (`127.0.0.1`) redirect URI, so it does not help local testing
  until the app is opened via `127.0.0.1`/HTTPS. `git stash pop` restores it.
- Tests: 266 tests pass (`.\mvnw.cmd -B test "-Dtest=!Scan2playApplicationTests"`, see `CLAUDE.md` for how to
  run them without disturbing the app running from IntelliJ) — run on 2026-09-29 in a scratch copy of the working
  tree with Phase 3 and Phase 4 stage 0 (`BUILD SUCCESS`; 232 before stage 0, 146 before Phase 3). Nothing in `youtube-autopilot.js` / `dashboard.js`
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

1. **Push** the six local commits when the owner says so (Phase 2 stages 4–5, Phase 3, Phase 4 stage 0 — each as code
   and docs). Phase 3 and stage 0 were tried on real devices by the owner and committed on their word.
2. **Phase 4, stages 1 and 2 (Section 14)** — the owner's requests (2026-09-29); ask "shall I start stage 1?" first:
   - *Stage 1:* ⏭ *Next* as a **remote control** (owner's decision): in the window that plays it acts at once; in a window
     that does not play it sends a NEXT command that the playing window collects with its next lease report (≤ 3 s) and
     carries out. Work with Auto-Pilot off too. Sketch: `PlayerLeaseService` keeps a pending command per party (set by any
     window of that party, consumed by the holder's report, which returns it in the answer — e.g. a `command` field next to
     `holder`/`free`/`fallbackPlaylistId`); the client runs `NEXT` as "stop what plays, ask `next-track`, load it" whatever
     the player state; think about a command pressed while nobody holds the lease (drop it, or tell the DJ), and about two
     presses in one interval (one skip or two? the DJ pressed twice). And the active queue (`#song-list` in `dashboard.html`)
     and history (`history.html`) in a fixed-height list with its own scrollbar (fine on a phone, owner's answer), count,
     search, history filter and "load more", compact rows on a phone.
   - *Stage 2:* `V6` `song_requests.played_at`, history ordered by play time and merged with the background tracks (their
     `fallback_track.played_at` and titles exist), then ⏮ *Previous* on top of that, through the same command channel.
3. Follow-ups the owner may also want: skip/remove a track from the "up next" list, one line in the panel
   saying how many guest songs wait ("Czeka 2 piosenki gości" — they play first; the owner asked about showing both lists
   together and agreed to keep the guest table separate), and a note in the panel when Auto-Pilot is off (nothing plays
   by itself then).
4. Optional (Section 14): show the import result on the dashboard (`X-Fallback-Import: ok|failed`,
   `X-Fallback-Import-Reason`) — the new panel is a natural place — instead of always flashing the Save button green;
   remove `GET /dj/dashboard/next-guest-track` and its tests, which nothing calls any more; browser-level tests for the JS.
5. `dev` → `main` is the next real decision (open items 1 and 4 below), but the owner said on 2026-09-29 that they do
   **not** want to merge yet — they want a polished `dev` first. Leave `main` alone until they bring it up.

## Open items for the owner

1. **`main`** is still the old pre-session state. Merging `dev` into `main` is a deliberate step (see `AGENTS.md`): the
   production database has never been checked against `V1` (item 4) and the client still has no browser-level tests.
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
