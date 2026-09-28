# Session Handoff — 2026-09-29

Where the work stands, for whoever continues (a new Claude Code session or a person). The history of the
first session (2026-09-28, remote) is summarised at the bottom.

## Where things stand

- Branch `dev` is pushed: `origin/dev` = `c0d1e2b` (pushed 2026-09-29; the 8 commits below went out together, from
  `5f51e7d`). `main` is untouched (`5314006`).
- One stash, deliberately parked: *"Spotify playback redirect-uri as {baseUrl} template (parked: Spotify rejects
  http://localhost)"*. It makes `spotify.oauth.redirect-uri` follow the request host like the login flow does.
  Spotify only accepts HTTPS or a loopback IP (`127.0.0.1`) redirect URI, so it does not help local testing
  until the app is opened via `127.0.0.1`/HTTPS. `git stash pop` restores it.
- Tests: 146 unit tests pass (`.\mvnw.cmd -B test "-Dtest=!Scan2playApplicationTests"`, see `CLAUDE.md` for how to
  run them without disturbing the app running from IntelliJ). Nothing in `youtube-autopilot.js` has automated tests.

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

The Section 14 stage table in `PROJECT_CONTEXT.md` is the authoritative progress list.

## Next: Phase 2, stage 4 — make the client use the server

`youtube-autopilot.js` still plays the fallback playlist itself (`loadPlaylist`) and only asks the server for guest
songs. Stage 4 turns it into the "dumb player" of the roadmap. Design notes from stage 3:

- **Ask only when a track is about to be loaded**: Auto-Pilot on and the player idle, on `ENDED`, and on a player
  error. `POST /dj/dashboard/next-track` is **not read-only** — it marks a background track `PLAYED` as it hands it
  out. Do not poll it. For "is a guest waiting?" peeks keep using the read-only `GET /dj/dashboard/next-guest-track`.
- **Guest songs** are still confirmed by the client with `POST /dj/dashboard/play` once the video reaches `PLAYING`
  (`source: GUEST`, `id` = song request id). Background tracks need no confirmation and no error report: a track that
  fails to play is already `PLAYED`, just ask for the next one. Keep the client-side `erroredSongIds` for guest songs
  (sent as `exclude`).
- **Drop**: `loadPlaylist`/`setShuffle`/`setLoop`, `fallbackTrackIndex`, `lastFallbackIndex`, `fallbackVideoId`,
  `fallbackTrackChanged`, `guestSongPending`, `pendingPlaylistSetup`, the BUFFERING early-detection. A single-video
  fallback (`V:<id>`) is one server row that loops, so it needs no special case either.
- **Decide with the owner**: (1) a guest arriving while a background track is *paused* — today `tryAutoPlay` switches
  immediately; (2) a guest arriving while a background track *plays* waits for it to end (same as the playlist
  boundary today) — acceptable? (3) the dashboard could show the result of the import (`X-Fallback-Import: ok|failed`,
  `X-Fallback-Import-Reason`) instead of always flashing the button green.
- **Expect** a short gap between background tracks (each is a fresh `loadVideoById`; the native playlist prefetch is
  gone). Measure before adding pre-fetching (Section 14).
- After stage 4 the `data-fallback-playlist` / `data-fallback-shuffle` attributes in `dashboard.html` and
  `window.updateFallbackSource` / `stopFallback` / `updateFallbackShuffle` in the JS are likely dead — the server reads
  the shuffle flag on every call.

**How the client was tested against the real YouTube player (no login needed):** a throw-away static page containing
the real `youtube-autopilot.js`, with `window.fetch` stubbed for `/dj/dashboard/*`, `YT.Player` wrapped to log every
`onStateChange` together with `getVideoData().video_id`, served by `python -m http.server` and opened in the built-in
browser. Gotchas: the player pauses itself after a few seconds unless it is muted (`__p.mute()`); `seekTo` from the
API triggers the same BUFFERING/PLAYING events as scrubbing; under shuffle `getPlaylistIndex()` can change for the same
video (the bug fixed in `1afdecf`).

## Open items for the owner

1. **`main`** is still the old pre-session state. Merging `dev` into `main` is a deliberate step (see `AGENTS.md`): the
   production database has never been checked against `V1` (item 4) and the client still has no browser-level tests.
2. **`YOUTUBE_API_KEY`**: the production key was pasted into a chat/screenshot on 2026-09-28 — rotate or restrict it
   (Google Cloud Console → Credentials: restrict to the YouTube Data API v3). Local run configuration: the variable
   name must have no stray characters (it had a trailing `:`, which silently disabled the key).
3. **Restart the app locally** so Flyway applies `V3` to the local `scan2play` database (`V1`/`V2` are applied).
4. **First production deploy** of Flyway: backup, compare `pg_dump --schema-only` with `V1__baseline.sql`
   (checklist in `PROJECT_CONTEXT.md`, Section 10). The production schema has never been checked against `V1`.
5. Spotify locally: see the stash above; the Spotify Developer Dashboard also needs the redirect URIs.
6. Stage 5: docs cleanup (Section 14, Section 5.4).

## History — first session (2026-09-28, remote Claude Code)

That session attached the repo, committed the three previously local-only docs (`PROJECT_CONTEXT.md`, `AGENTS.md`,
`.github/copilot-instructions.md`) to `dev`, fixed a stale Section 5.4, wrote the "Master Queue" roadmap (Section 14),
found that its `PENDING`/`APPROVED` guest-approval gate never existed in the code, shipped Phase 1 (server-side
`next-guest-track`, `e811ba4`) and replaced the hardcoded production OAuth2 login redirect URIs with
`{baseUrl}` templates. Its Phase 1 change had not been build-verified; that was done in the second session.
