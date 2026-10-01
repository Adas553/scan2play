# Scan2Play — Project Context

> The application as it is on branch `dev` (2026-10-01). Read it before an architectural change; `AGENTS.md` says how to work,
> `SESSION_HANDOFF.md` what is going on now, `REVIEW.md` the review of 2026-09-30 and what was fixed.
> **History** — the decisions, the owner's reports, what was tried and measured, how every stage was verified — is in
> `docs/history/project-context-2026-10-01.md` (this file word for word before it was cut down, review 7.1) and
> `docs/history/session-handoff-2026-09.md`. Look there for "why" questions; keep this file to what is true now.
> Section numbers are referred to from the code (5.1, 5.3, 5.4, 7.3, 10, 13, 14) — keep them.

---

## 1. Product Overview

**Scan2Play** is an AI-powered music request and virtual DJ platform for events. Guests scan a QR code and send song requests
from a phone; Google Gemini decides whether each one fits the party's vibe. Accepted songs play on the DJ's side: through the
Spotify API (server side) or in the YouTube IFrame Player embedded in the DJ's dashboard. When no guest song waits, a YouTube party
plays the DJ's background ("fallback") playlist, served track by track by the server.

**Production URL:** `https://www.scan2play.com.pl` (Railway, behind Cloudflare; paused at the moment — `main` is not live).

---

## 2. Technology Stack

| Layer            | Technology |
|------------------|------------|
| Language         | Java 21 |
| Framework        | Spring Boot 4.0.3 (Spring MVC, Thymeleaf, Spring Security + OAuth2 Client, Spring Data JPA) |
| Front end        | Thymeleaf pages, Bootstrap 5 (CDN), plain JavaScript — the dashboard's scripts are ES modules, no bundler, no framework |
| Database         | PostgreSQL 18 (Railway `postgres-ssl:18`), schema by **Flyway** (Section 10) |
| AI               | Google Gemini (`google-genai` 1.38.0), model `gemini-2.5-flash` |
| Music            | Spotify Web API (`spotify-web-api-java` 8.4.1); YouTube Data API v3 (`RestClient`) + IFrame Player API |
| Other            | ZXing 3.5.3 (QR codes), Caffeine (caches), Maven |
| i18n             | `messages.properties` (EN), `messages_pl.properties` (PL) — non-ASCII as `\uXXXX` escapes |

---

## 3. Architecture

A monolithic Spring Boot application, layered:

```
controller/   HTTP: Thymeleaf views, HTML fragments for AJAX, a few JSON endpoints
service/      business logic
repository/   Spring Data JPA (+ native SQL for the queue)
entity/       JPA entities        model/   enums, records, views
integration/  MusicProvider, Spotify constants      config/  Spring beans      util/  CodeGenerator, YouTubeUrls
```

Server-rendered pages with AJAX: the DJ dashboard polls the guest queue every 3 s (ETag / 304), sends its forms by `fetch` at a
YouTube party (a page reload would stop the embedded player), and each dashboard window reports to the server every 3 s which
window plays (the player lease, Section 5.4). **Single instance by design** (Section 13): the player lease, the caches and the guest
limits are in memory. The HTTP sessions are in PostgreSQL (Spring Session JDBC, `V11`; review 2.3): a deploy or a restart does not
log the DJs out.

---

## 4. Domain Model

### 4.1 Entities (database tables)

**Every moment is a `timestamptz` column and an `Instant` in Java** (`V12`, review 1.8) — the same on a machine in Poland and on a
server in UTC; the pages show them in Polish time (`util/Times`: `display`, and a fixed-width UTC `sortKey` for the lists' `data-val`).

**`PartySettingsEntity` → `party_settings`** — one party, owned by one DJ (`owner_id` UNIQUE: one party per DJ).
`partyCode` (5 characters of `[A-Z0-9]`, unique — in the QR code), `ownerId` (the OAuth2 subject), `active` (accepting requests),
`globalVibe` (`VibeType`; `ANY` = the guests choose), `activeProvider` (`SPOTIFY` / `YOUTUBE`, set at creation by the login used,
never changed), `playbackMode` (`MANUAL` / `AUTO` = Auto-Pilot), `requestLimit` / `cooldownMinutes` (the guest's own limit, 1–100 /
1–1440), `duplicateCheckWindow` (0–50 recent songs the AI must not repeat), `fallbackPlaylistUrl` (≤ 500), `fallbackShuffle`,
`spotifyAccessToken` / `spotifyRefreshToken` (plain text, 2048) / `spotifyTokenExpiresAt`.

**`SongRequestEntity` → `song_requests`** — a guest's request or a DJ pick. `partyCode`, `songName` (255; for a YouTube party the
found video's cleaned title, Section 7.1), `style` (the vibe it was judged against, or "DJ Pick"), `decision` (`accepted` /
`rejected` / `played`), `djComment` (500), `energyLevel`, `requestedAt`, `trackUrl` (500; a YouTube watch URL, a YouTube search
link, or a Spotify URI), `playedAt` (V6; set only by `DjService.markPlayed`). Index `idx_party_decision_time (party_code, decision,
requested_at DESC)`. `@PrePersist` truncates the long fields. **Retention: 30 days from `requestedAt`** —
`SongRequestRetentionService` deletes nightly at 04:45 in batches of 1000, at most 200 batches a night; and with the account.

**`FallbackTrackEntity` → `fallback_track`** (V2, V4, V5, V8) — the server's copy of a YouTube party's background playlist.
`partyCode`, `playlistId` (playlist id, or `V:<videoId>` for a single video), `videoId`, `title`, `playlistPosition`, `playOrder`
(the lowest QUEUED one plays next — playlist order or a random order fixed in advance), `manualMove` (the DJ moved it in this
order), `status` (`QUEUED` → `PLAYED`; `SKIPPED` = the DJ's ✕ for this round; `CANCELLED` = the playlist was replaced or cleared —
never deleted), `fetchedAt`, `playedAt` (in the current round only). A new round re-queues the PLAYED and SKIPPED tracks of the
party's newest import. Indexes (V9): `(party_code, playlist_id, status, play_order, playlist_position)` — every queue query, no sort
— and `(party_code, fetched_at)`. An import is one `INSERT … SELECT FROM unnest(…) WITH ORDINALITY`. **Retention: 30 days from
`fetchedAt`** (YouTube API data, III.E.4.d), purged nightly at 04:30, and with the account.

**`FallbackPlayEntity` → `fallback_play`** (V7) — the play log: one row each time the player takes a background track (written by
`takeNextTrack` in the same transaction and under the same lock as the claim); never changed afterwards. `id` is the identity of
**one play** — the client's key `B:<id>`, so a video played in two rounds is two entries. `videoId`, `title`, `fetchedAt` (copied
from the track: the retention), `playedAt`. Index `(party_code, played_at DESC, id DESC)`. Purged with `fallback_track`; replacing
or clearing the playlist does not clear it.

**`FeedbackEntity` → `feedback`** — the DJ's bug reports and ideas (`message` ≤ 2000).
**`YoutubeCacheEntity` → `youtube_cache`** — L2 of the YouTube search cache (Section 7.3), unique `searchQuery`, 30-day TTL.
**`youtube_search_budget`** (V10, no entity) — the day's count of YouTube API searches (Section 7.3).
**`spring_session`, `spring_session_attributes`** (V11, Spring Session's own schema, no entity) — the HTTP sessions; expired ones are
deleted every minute.

### 4.2 Enums

`MusicProviderType` SPOTIFY / YOUTUBE · `PlaybackMode` MANUAL / AUTO · `RequestMode` SONG / MOOD · `FallbackTrackStatus`
QUEUED / PLAYED / CANCELLED / SKIPPED · `PlayerLeaseMode` CLAIM / WATCH / TAKE_OVER · `PlayerCommand` NEXT / PREVIOUS /
PREVIOUS_TRACK / RESTART / PAUSE / RESUME · `MoveDirection` UP / DOWN / TOP · `HistoryFilter` all / guest / background / played /
rejected · `VibeType` ANY and 13 genres.

### 4.3 Records

`DjResponse` (the AI's answer: `decision`, `comment`, `songName`, `energyLevel`, `requestKind` title / artist / lyrics / mood,
`requestId`), `NextTrackResponse` (`source` GUEST / BACKGROUND, `id`, `videoId`, `playlistId`), `PlayerLeaseResponse`,
`RecentTrack`, `HistoryEntry`, `FallbackQueueView`, `PlaylistTrack`, `NextGuestTrackResponse` (internal to `DjService`).

---

## 5. User Flows

### 5.1 DJ Flow

Landing page `/` → "Start with Spotify" (`/oauth2/authorization/spotify`) or "with YouTube" (`/oauth2/authorization/google`) →
`/dj/dashboard`; the party is created on the first login with the provider of that login. On the dashboard the DJ can: see the
guest queue (polled every 3 s; sort, search), set the vibe and the guest limits, switch Auto-Pilot, add a DJ pick (YouTube; the AI
only normalises the name), set the background playlist (YouTube) and see / reorder / skip its "up next" list, play / pause / skip
/ go back (the player controls), see the history (one timeline of what played and was rejected), print the QR code
(`/dj/qr-print`: an A4 poster or eight table cards, Polish and English), connect Spotify for playback (Section 5.3), send feedback,
end / resume the party, delete the account, log out.

Every dashboard window follows the party's state within one poll or one lease report: Auto-Pilot (`playbackMode` in the lease
answer), the party open or closed (`X-Party-Active` of the queue poll — the "party closed" banner and the "end party" button), the
guest limits (`X-Guest-Limits`, `X-Guest-Limits-Use`), the "up next" list (`queueVersion`). A change the DJ makes in a window shows
there at once; an answer to a request sent before that click is ignored.

### 5.2 Guest Flow

`/p/{partyCode}` (no login) → the form: two tiles **🎵 Konkretna piosenka** (a title, an artist or a line of the lyrics; song
suggestions from iTunes) and **✨ Nastrój** (the AI picks a song); the vibe list when the DJ has not forced one → `POST
/p/{partyCode}/request` (an async `Callable`) → the result page (decision, the AI's comment, where the song waits). Under the form:
"🔊 Teraz gra", the next 5 guest songs in play order, and the guest's own song with its place (`GuestQueueService`; the guest's
requests are remembered in the session) — fetched again when the guest comes back to the page and on "↻ Odśwież", no timer. A
request the AI reads as a mood in the song mode is not saved: the guest is back at the form in the mood mode. An ended party shows
"DJ nie przyjmuje teraz próśb" with "↻ Sprawdź ponownie" (the party's link) — the landing page is for DJs.

**What reaches the AI:** the guest's text as one line ≤ 150 characters, `"` made `'` (`SongEvaluationService.forPrompt`); the style
decided by the server (`GuestController.styleOf`): the DJ's vibe when set, else the guest's pick from `VibeType`, else `ANY`.

**Limits** — each counted **before** the AI is asked:
1. the guest's own: the DJ's `requestLimit` per `cooldownMinutes`, counted by the session's id in memory (`GuestSessionService.tryAcquire`,
   one atomic `compute` — not in the session: with the sessions in the database every request works on its own copy, so parallel
   requests could not see each other's count there) — `guest.error.rate_limit`;
2. client IP + party: `guest.limit.per-ip-party` (30) per `guest.limit.per-ip-window-minutes` (10) — loose, a venue's Wi-Fi is one
   address — `guest.error.too_many_requests`;
3. party: `guest.limit.per-party-daily` (300) per 24 h — `guest.error.party_daily_limit`.

2 and 3 are `GuestRequestLimiter` (Caffeine, in memory); 0 switches a limit off. The address is `getRemoteAddr()` or the header named
by `guest.client-ip-header` (`CF-Connecting-IP` — set on Railway). The DJ sees the use of 2 and 3 under the limits form (badges:
grey, yellow from 80 %, red) and a warning above the queue while a limit or the YouTube search fuse stops guest songs.

### 5.3 Spotify Playback Auth (a second OAuth2)

`/dj/spotify/login` → Spotify (playback scopes) → `/dj/spotify/callback`; the tokens go to the party (`SpotifyAuthService`, refreshed
5 minutes before they expire). Both endpoints need the DJ's login. The OAuth `state` is 32 random bytes kept in the session and
accepted once (review 5.2). The Spotify redirect URI must be registered in the Spotify Developer Dashboard.

### 5.4 YouTube Auto-Pilot — the server decides what plays, the browser plays it

`youtube-autopilot.js` is a "dumb player": it knows nothing about playlists, their order or shuffle. **The track in the player is one
object, `current`** (kind GUEST / BACKGROUND / HISTORY / MANUAL, guest song id, timeline key, playlist, the lease report seq at
load, load number, start time, whether ⏭ retraces, phase LOADING → RUNNING → OVER), created only by `startTrack`.

**When it asks** `POST /dj/dashboard/next-track` (`partyCode`, `deviceId`, optional `exclude` = guest song ids the player failed on) —
only in the window that holds the player lease, with Auto-Pilot on, and only when a track is about to be loaded: the player is idle
(page load, Auto-Pilot switched on, a poll), a track ENDED, a player error (the first 5 in a row at once, then one per lease
report), or an earlier ask failed (`askAgain`: again with each lease report until the server answers). **`next-track` is not
read-only** (a background track is consumed when handed out), so it is never used to poll. Answers: 200 `{source, id, videoId,
playlistId}`, 204 nothing to play, 409 another window holds the lease.

**What the server answers** (`NextTrackService`): 1) the oldest accepted guest song with a video id, minus `exclude`
(`DjService.findNextPlayableGuestTrack`, read only — the client confirms it with `POST /dj/dashboard/play` on PLAYING); 2) else the
next QUEUED track of the current background playlist (`FallbackTrackCommandService.takeNextTrack`: claimed PLAYED and written to the
play log; the next round is prepared as soon as the last track is taken); 3) else 204. It imports the playlist lazily when there is
nothing to play or the tracks are ≥ 29 days old (single flight per party + playlist, 5 minutes' pause after a failure).

**Rules of the player** (agreed with the owner):
- A running track is never interrupted by Auto-Pilot; a guest song that arrives waits for it. A paused player is left alone.
- Auto-Pilot **off**: nothing starts by itself; the DJ plays tracks by hand (the ▶ links). A hand-picked track is not overridden;
  Auto-Pilot carries on when it ends. **▶ on a guest song of the queue plays it to the party** like a song Auto-Pilot handed out:
  confirmed played on PLAYING, so it leaves the queue (before, it stayed and Auto-Pilot played it again when it ended); **↗** beside
  it only opens the video on YouTube in a new tab, to see what the song is. What a track does when it ends follows the **current** setting.
- An answer that arrives after the DJ started a track or switched Auto-Pilot off is dropped.
- **Resume after a reload:** the page that plays notes on `pagehide` the track it interrupts (`sessionStorage`
  `scan2play.interruptedTrack` = `{key, paused}`). After the reload only that track comes back, from its start — when it is still the
  newest entry of `recent-tracks` and started ≤ 10 min ago; with Auto-Pilot off it waits until Auto-Pilot is switched on or "resume"
  is pressed; a track the DJ had paused is held until "resume". A track that ended by itself is not played again. **"Play on this
  device"** (a takeover) brings back the track the other device was playing, likewise.

**One window plays — the player lease** (`PlayerLeaseService`, in memory). Every window has a random id (`sessionStorage`) and
reports `POST /dj/dashboard/player-lease` (`CLAIM` / `WATCH` / `TAKE_OVER`) every 3 s and at once on PLAYING / PAUSED; a lease
not renewed for 10 s is free. The answer `{holder, free, fallbackPlaylistId, queueVersion, command, playing, playbackMode}` tells the
window whether it plays, the current playlist (the window that plays stops a background track of a replaced playlist), the version
of the "up next" list, a command waiting for it, whether the holder's player makes sound, and the Auto-Pilot setting. `next-track`
answers 409 to a window that is not the holder; with nobody holding the lease the asking window takes it in the same step
(`claimToPlay`). A window that does not play shows a banner ("playback runs on another device" + *Play on this device*, which asks
first when another window plays), does not start sound from a ▶ link and stops its own YouTube player if someone presses play in
it. The holder gives the lease up on `pagehide` (`sendBeacon` to `.../player-lease/release`, CSRF token in the body).

**The player controls** (`fragments/player-controls.html`), from any window — in the window that plays they act at once, in another
one they are a command (`POST /dj/dashboard/player-command`, kept per party until the holder's next report, one command per party —
the last one wins, 409 when nobody plays, the button says "Wysłano…"):
- **⏭ Next** — whatever `next-track` hands out now, also with Auto-Pilot off; adds the running guest song to `exclude`. After ⏮ it
  **retraces the steps** forward through the timeline (`recent-tracks`) and asks the queue only at the newest entry; a change of the
  playlist ends the retracing.
- **⏮ Back** (the window that plays) — a track that has run > 3 s starts again; a second press within 20 s of such a restart goes
  to the track before; otherwise the previous track of the timeline. A track that comes back is not confirmed again, and when it
  ends Auto-Pilot goes on with the queue.
- **⏮ Wstecz / ↺ Od początku** (in a window that does not play, instead of the single ⏮) — `PREVIOUS_TRACK`: always the previous
  track; `RESTART`: the running track from its start (a paused one stays paused).
- **⏯ Pause / Resume** — explicit `PAUSE` / `RESUME`, never a toggle; the label follows the holder's `playing`; a loading track counts
  as playing for at most 10 s.

**The timeline** (`PlayHistoryService`): guest requests that played or were rejected (`song_requests`, by `COALESCE(played_at,
requested_at)`) merged with the play log (`fallback_play`), newest first, each side one bounded query (`limit + 1`). Keys: `G:<request
id>`, `B:<play id>`. `GET /dj/dashboard/recent-tracks` = the 30 newest playable entries (with `secondsAgo`) for ⏮ and the resume. The
History tab / page: filters All / Guests / Playlist / Played / Rejected applied by the server, "Show more" (50 at a time, ≤ 300).

**The "up next" list** of the background playlist (`GET /dj/dashboard/fallback-queue`, `fragments/fallback-queue.html`): every queued
track of the round (≤ 500) in play order, "Next" on the first, the counts left / skipped; per row ⇑ play next, ↑, ↓ (`.../move`), ✕
skip for this round (`.../skip`; disabled for a single video), and drag and drop (`.../place`; mouse, or press and hold on touch).
Moves last until the next new order (import, shuffle switch, new round); the caption then says "changed by hand" and the shuffle
switch asks first. Shuffle on → a new random order; off → playlist order continuing after the last played track. The window that made
a change refreshes its list at once; the others when the lease answer brings another `queueVersion`.

**Saving the playlist** (`POST /dj/dashboard/fallback-playlist`): the URL is saved and imported at once (best effort); the answer says
how in headers (`X-Fallback-Id`, `X-Fallback-Import: ok|failed`, `X-Fallback-Tracks`, `X-Fallback-Import-Reason`) and the dashboard
shows it under the form. A YouTube Mix (`list=RD…`) and a link over 500 characters are refused before saving
(`X-Fallback-Saved: false`). URL formats (`YouTubeUrls.extractPlaylistId`, server side only): playlist URL, watch URL with `list=`,
watch URL or `youtu.be` link (a single video, `V:<id>`), a raw playlist or video id.

**Testing** — browser tests in `src/test/browser` (`python src/test/browser/run.py`; guide: its `README.md`): the real scripts on the
real rendered dashboard (`DashboardPageRenderTest` writes it) in a headless Chrome, with a fake YouTube player and a Python stand-in
server that scenarios configure (or that replays a fixture of real answers recorded by `PlayLogFixtureRecorderTest`). 60 scenarios;
GitHub runs them (`browser-tests.yml`). They do **not** cover the real YouTube player (sound, autoplay policy), two real devices, how
a page looks, and the guest's page. Gotchas of the real player: it needs a real click first, its methods exist only after `onReady`,
many videos have embedding disabled (error 150).

---

## 6. File Inventory

### 6.1 Controllers

| Class | Purpose |
|-------|---------|
| `HomeController` | `/`: the landing page, or the dashboard for a logged-in DJ |
| `DjDashboardController` | the dashboard, the queue poll (`/dj/dashboard/updates`), `next-track`, the history page and fragment, the QR print page |
| `DjPartySettingsController` | start / end party, vibe, limits (bounded), Auto-Pilot, background playlist + shuffle, account deletion |
| `DjFallbackQueueController` | the "up next" fragment, move / place / skip |
| `DjPlayerLeaseController` | the player lease, its release, the player commands, `recent-tracks` |
| `DjSongController` | mark played, push to Spotify, DJ pick |
| `DjSessionHelper` | the party of the logged-in DJ (cached in the session) and **`validateOwnership`** (IDOR) |
| `GuestController` | the guest's page, its list, the request (limits, style, evaluation) |
| `FeedbackController` | `POST /dj/feedback` (JSON) |
| `CspReportController` | `POST /csp-report` — the browsers' CSP reports, logged once an hour per violation |
| `LegalController` | `/privacy`, `/terms` (a file per language) |
| `SpotifyAuthController` | `/dj/spotify/login`, `/dj/spotify/callback` |
| `ViewAttributes` | names of the model attributes |

### 6.2 Services

| Class | Purpose |
|-------|---------|
| `SongEvaluationService` | the guest's request: Gemini (`askAi`, prompts per mode and language) → track search → the video's title as the name → save → Spotify auto-queue; DJ pick name normalisation |
| `DjService` | the guest queue (`dashboardQueue` cache), its fingerprint (ETag), mark played (`markPlayed`), push to Spotify, DJ picks, the next playable guest song |
| `NextTrackService` | "what plays next" (Section 5.4) |
| `FallbackPlaylistService` | imports the background playlist (API first, the database only after a complete result) |
| `FallbackTrackCommandService` | every write of the background queue — replace, cancel, shuffle, move, place, skip, `takeNextTrack`, the nightly purge — each under the party's advisory lock (Section 14) |
| `FallbackQueueService` | the "up next" view, its version, and the DJ's changes resolved to the current playlist |
| `PlayHistoryService` | the timeline (Section 5.4) |
| `PlayerLeaseService` | the player lease and the commands (in memory; a `Clock` for tests) |
| `GuestQueueService` | what the guest sees under the form |
| `GuestSessionService` / `GuestRequestLimiter` | the guest limits (Section 5.2) |
| `PartySettingsQueryService` / `PartySettingsCommandService` | read (cached copy) / write (evicts after commit) of the party |
| `QueueService` | delegates to the `MusicProvider` of the party |
| `YouTubeMusicProvider` / `YouTubeSearchBudget` | the YouTube search with its two caches / the daily search fuse (Section 7.3) |
| `YouTubePlaylistClient` | reads a playlist (≤ 500 items, titles), a single video's title |
| `SpotifyMusicProvider` / `SpotifyAuthService` | Spotify search (client credentials) and queue (the DJ's token) / the playback tokens |
| `QrCodeService` | QR codes (ZXing, cached) |
| `AccountDeletionService` | deletes all of a DJ's data and evicts the caches after the commit |
| `SongRequestRetentionService` | the nightly purge of song requests (Section 4.1) |

### 6.3 Configuration

`SecurityConfig` (routes, OAuth2 login, logout, CSRF, the CSP — Section 8), `AppConfig` (caching, scheduling, async, the Caffeine
caches, `RestClient` with 5 s / 10 s timeouts), `GeminiConfig` (the Gemini client, 10 s timeout; the `ObjectMapper` bean),
`OAuth2DebugConfig` (`@Profile("dev")` only).

### 6.4 Templates

`landing.html`, `dashboard.html`, `history.html` (its `historyTableContent` fragment is also the dashboard's History tab),
`qr-print.html`, `index.html` (the guest's page), `result.html`, `party_ended.html`, `error.html`, `privacy[_pl].html`,
`terms[_pl].html`; `fragments/`: `components.html` (`dj-nav`: the account buttons, the sticky tabs Panel / Kolejka / Historia, the
feedback modal; `scroll-restore-script`; `footer`), `fallback-queue.html`, `guest-queue.html`, `player-controls.html`,
`player-lease-banner.html`. Texts the scripts need travel in `data-*` attributes; so does a song's YouTube video id
(`data-video-id` on a queue row and its ▶ link, read by the server with `YouTubeUrls.extractVideoId` — the scripts parse no URL). **No inline script and no `on…=` handler** on any
page (`DashboardPageRenderTest.assertNothingInline`).

### 6.5 Static assets

| File | Purpose |
|------|---------|
| `js/dashboard/*.js` | the dashboard as ES modules: `main.js` imports `list-tools.js` (sort, search, filters, "Show more" — the history page loads it alone), `fallback-queue.js`, `forms.js` (AJAX forms, Auto-Pilot switch, `data-auto-submit`), `tabs.js`, `polling.js` (the queue every 3 s and its headers), `common.js`; they and the player talk only through the `s2p:*` events of `events.js`, never through `window` |
| `js/youtube-autopilot.js` | the player (Section 5.4), an ES module; its only global is `onYouTubeIframeAPIReady` |
| `js/dj-nav.js` | `form[data-confirm]` (capture phase, before `forms.js`) and the feedback form |
| `js/scroll-restore.js` | the scroll memory of the DJ pages (in `<head>`) |
| `js/guest-party.js` | the guest's page: the mode tiles (focus the field only with a mouse), the list refresh, "sending…" |
| `js/song-autocomplete.js` | song suggestions from the iTunes Search API (debounced, client side) |
| `js/wake-lock.js` | keeps the screen on while Auto-Pilot is on (`s2p:playback-mode`) |
| `js/qr-print.js`, `css/qr-print.css`, `css/app.css` | the print page; the shared styles |

### 6.6 Resources

`application.properties` (all configuration, env overrides — Section 10), the message bundles, `prompts/` (`prompt-template_{en,pl}`
for a song, `prompt-mood_{en,pl}`, `prompt-duplicate-rule_{en,pl}`, `prompt-normalize`), `db/migration/V1..V10`.

---

## 7. External API Integrations

### 7.1 Google Gemini

- Evaluates guests' requests and normalises DJ picks. Model `gemini-2.5-flash` (pinned; env `GOOGLE_AI_MODEL`); a request may think
  up to `google.ai.thinking-budget` tokens (1024), normalisation never thinks and runs at temperature 0. Timeout 10 s per call.
- Prompts per mode (song / mood) and language (PL / EN by the guest's locale, else EN). The answer is JSON (`DjResponse`). The song
  prompt: a request may be a title, an artist or a line of lyrics; never replace it by another song; not knowing a song (a new one)
  is no reason to reject it; on a rejection `songName` is what the guest asked for (the code also falls back to the guest's text).
- A YouTube party searches a `lyrics` request by the guest's own words, everything else by the AI's name; the stored name is then
  the found video's own title, cleaned of "(Official Video)" and the like (`YouTubeUrls.cleanVideoTitle`, one `videos.list` call).
- AI down → the request is rejected with "AI offline" (no retry). Duplicates: the last `duplicateCheckWindow` accepted / played
  songs go into the prompt.

### 7.2 Spotify Web API

Login (Spring Security OAuth2) identifies the DJ; the playback OAuth2 (Section 5.3) controls the DJ's player; client credentials
(cached token) search tracks. A song counts as played only once Spotify has taken it into the queue (`whenComplete`); otherwise it
stays in the queue with a note. Spotify's API allows only a few users for this app — the Spotify party is secondary.

### 7.3 YouTube Data API v3

- **Search** (`YouTubeMusicProvider`): L1 Caffeine `youtubeSearch` (24 h) → L2 `youtube_cache` (30 days, refreshed on access when
  expired, cleaned daily at 04:00) → the API (`search.list`, key in the `X-goog-api-key` header). No key or a failure → a YouTube
  search link (the DJ plays it by hand; Auto-Pilot skips it). A search that found nothing is remembered 10 minutes.
- **Quota:** `search.list` has its own limit of 100 calls a day per Google project, shared by every party; everything else shares
  10 000 units (`playlistItems.list`, `videos.list` = 1 unit). **The search fuse** `YouTubeSearchBudget`: at most
  `youtube.search.daily-budget` (80) API searches per Google day (midnight Pacific), counted in the database (`youtube_search_budget`,
  one atomic `INSERT … ON CONFLICT … RETURNING` per search — a restart or a second instance cannot exceed it); a 403
  `quotaExceeded` trips it at once. Raise the budget together with the quota.
- **Playlist import** (`YouTubePlaylistClient`): ≤ 500 items, ≤ 20 units; private, deleted and non-embeddable videos dropped; titles
  from the same calls. Failures (`NO_API_KEY`, `INVALID_PLAYLIST`, `API_ERROR`, `NO_PLAYABLE_TRACKS`) are thrown before any write,
  without the key in the message. A single video needs no API call to play.
- **Retention:** every video id and title from the API is kept ≤ 30 days (Section 4.1).

---

## 8. Security Model

Public: `/`, `/p/**`, `/privacy`, `/terms`, `/oauth2/**`, `/login/**`, `/css/**`, `/js/**`, `/images/**`, `/error`,
`POST /csp-report`. Everything else needs the DJ's login; `/dj/**` validates the party's ownership (`DjSessionHelper.validateOwnership`
— IDOR). CSRF on (tokens in `<meta>` for AJAX, `_csrf` in the body of the release beacon; `/csp-report` is exempt). Logout `POST
/dj/logout`. `th:utext` only for texts of our own bundles; titles from YouTube / iTunes are escaped.

**Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`): scripts from the app, `cdn.jsdelivr.net` (Bootstrap) and
YouTube (`www.youtube.com`, `s.ytimg.com`) — **no `'unsafe-inline'` for scripts**; styles also inline (`style="…"` attributes
remain); images from the app and `data:`; `connect-src` the app and `itunes.apple.com`; frames only YouTube; `object-src 'none'`,
`base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; `report-uri /csp-report`. **Report-only** while
`security.csp.enforce=false` (env `CSP_ENFORCE`): the log says `CSP violation: …` for what it would block. Switch it on once real use
leaves the log quiet.

---

## 9. Caching Strategy

| Cache | Key | TTL / size | Usage |
|-------|-----|------------|-------|
| `partySettings` | partyCode | 24 h / 500 | `PartySettingsQueryService.getSettings` — every caller gets a **copy**; `updateSettings` evicts after its commit |
| `qr-codes` | text + size | 24 h / 1000 | `QrCodeService` |
| `youtubeSearch` | query | 24 h / 1000 | L1 of the YouTube search (search links are never cached) |
| `dashboardQueue` | partyCode | 3 s / 200 | `DjService.getDashboardQueue` — also read by `next-track`; evicted when a song is confirmed played |

The DJ's party code is cached in the `HttpSession`. Account deletion evicts the party from the caches after its commit.

---

## 10. Production Configuration

### Environment variables

`SPOTIFY_CLIENT_ID` / `_SECRET`, `GOOGLE_CLIENT_ID` / `_SECRET`, `GOOGLE_AI_API_KEY`, `YOUTUBE_API_KEY` (optional: without it songs
get search links and playlists cannot be imported; no trailing characters in the variable's name), the database (`PGHOST`, `PGPORT`,
`PGDATABASE`, and **`PGUSER` / `PGPASSWORD` required** — no defaults since review 5.5; Railway sets all five), `SCAN2PLAY_GUEST_URL` (the base URL in the QR code; locally the LAN address, so a phone on the
same Wi-Fi can open it), `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` (Railway), and the optional overrides below. The DJ's login works
only on `localhost` or a public HTTPS address (Google refuses a LAN IP as a redirect URI); the guest side works through the LAN IP.

### Key application properties

| Property | Value |
|----------|-------|
| `google.ai.model-name` / `google.ai.thinking-budget` | `gemini-2.5-flash` / 1024 |
| `spring.jpa.hibernate.ddl-auto` | `validate` (Flyway owns the schema) |
| `server.forward-headers-strategy` | `FRAMEWORK` |
| `server.compression.*` | gzip for HTML / CSS / JS / JSON from 2 KB |
| `server.tomcat.max-http-form-post-size` | 10 KB |
| `server.shutdown` | graceful, 30 s |
| `spring.task.execution.pool.*` | 16 core / 32 max threads, queue 50 (env `ASYNC_POOL_*`) |
| `spring.datasource.hikari.*` | 15 max, 5 idle, 5 s connect timeout |
| `youtube.search.daily-budget` | 80 (env `YOUTUBE_SEARCH_DAILY_BUDGET`) |
| `guest.limit.*` | 30 per 10 min per address + party, 300 per 24 h per party (env `GUEST_LIMIT_*`) |
| `guest.client-ip-header` | empty (env `GUEST_CLIENT_IP_HEADER`) |
| `security.csp.enforce` | `false` = report only (env `CSP_ENFORCE`) |
| `spring.session.jdbc.initialize-schema` | `never` (the tables are Flyway's, `V11`); a session lives 30 min without a request |

**The profile `local`** (`application-local.properties`): the developer's defaults `PGUSER=postgres`, `PGPASSWORD=1111`. IntelliJ's run
configuration has *Active profiles: local* (or `SPRING_PROFILES_ACTIVE=local`); without it and without the variables the application
does not start (`Could not resolve placeholder 'PGUSER'`).

### Database migrations (Flyway)

The schema is a sequence of files `src/main/resources/db/migration/V<n>__<what>.sql`, applied in order at startup before Hibernate
validates; **never edit an applied one** — add the next. An entity change that touches the schema needs its migration in the same
change, or the application does not start. `spring.flyway.baseline-on-migrate=true`: a database that had tables before Flyway
(production) is recorded as V1 without running it.
**`SPRING_JPA_HIBERNATE_DDL_AUTO` is set on Railway** (seen 2026-10-01, value not read): it overrides `validate` — check it is
`validate` or remove it before the next deploy; `update` would let Hibernate change the schema behind Flyway's back.

| Version | What |
|---------|------|
| V1 | baseline (the schema of 2026-09-28) |
| V2 | `fallback_track` |
| V3 | data fix: request limits 0 → 2 / 3 |
| V4 | `fallback_track.play_order`, `title` |
| V5 | `fallback_track.manual_move` |
| V6 | `song_requests.played_at` (no back-fill) |
| V7 | `fallback_play` (copies the PLAYED tracks of the moment) |
| V8 | status `SKIPPED` (the check constraint) |
| V9 | the queue indexes; three redundant indexes dropped |
| V10 | `youtube_search_budget` |
| V11 | `spring_session`, `spring_session_attributes` (Spring Session JDBC's schema, word for word) |
| V12 | every `timestamp` → `timestamptz`; the old values read in the session's zone = the JVM's that wrote them (Polish time locally, UTC on Railway) |

Checked by `MigrationIT` (`mvnw verify -Pit`, Section 13) on an empty PostgreSQL 18, locally and on GitHub.

**First production deploy checklist** (the next deploy applies V2..V12 at once): (1) back up the database; (2) dump the production
schema (`pg_dump --schema-only --no-owner`) and compare it with `V1__baseline.sql` — the same tables and columns, or Hibernate's
validation refuses to start; (3) deploy — Flyway creates `flyway_schema_history`, baselines, and applies the rest.

---

## 11. Main Dependencies

```
GuestController            → SongEvaluationService, GuestQueueService, GuestSessionService, GuestRequestLimiter, PartySettingsQueryService
DjDashboardController      → DjService, NextTrackService, PlayerLeaseService, PlayHistoryService, QrCodeService,
                             GuestRequestLimiter, YouTubeSearchBudget, PartySettingsQueryService, DjSessionHelper
DjPlayerLeaseController    → PlayerLeaseService, FallbackQueueService, PlayHistoryService, PartySettingsQueryService, DjSessionHelper
DjPartySettingsController  → PartySettingsCommandService, FallbackPlaylistService, AccountDeletionService, DjSessionHelper
DjFallbackQueueController  → FallbackQueueService, DjSessionHelper
NextTrackService           → DjService, FallbackPlaylistService, FallbackTrackCommandService, FallbackTrackRepository
SongEvaluationService      → Gemini Client, QueueService, YouTubePlaylistClient, SongRequestRepository, PartySettingsQueryService
QueueService               → SpotifyMusicProvider (→ SpotifyAuthService), YouTubeMusicProvider (→ YouTubeSearchBudget)
GuestQueueService          → DjService, PlayHistoryService
```

---

## 12. HTTP Endpoints

### Public

| Method | Path | Handler / notes |
|--------|------|-----------------|
| GET | `/` | `HomeController.home` |
| GET | `/p/{partyCode}` | the guest's page (`party_ended` when closed) |
| GET | `/p/{partyCode}/queue` | the guest's list alone (empty when closed, 404 for an unknown party) |
| POST | `/p/{partyCode}/request` | a request: `songName`, `style`, `requestMode` = SONG / MOOD |
| GET | `/privacy`, `/terms` | legal pages |
| POST | `/csp-report` | a browser's CSP report (no CSRF; 204) |

### DJ (logged in; every endpoint with a `partyCode` checks ownership)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/dj/dashboard` | the dashboard |
| GET | `/dj/dashboard/updates` | the guest queue `<tbody>` (ETag / 304); every answer, 304 too, carries `X-Guest-Limits`, `X-Guest-Limits-Use`, `X-Party-Active` |
| POST | `/dj/dashboard/next-track` | `deviceId`, `exclude` → 200 / 204 / 409 (Section 5.4); **not read-only** |
| POST | `/dj/dashboard/player-lease` | `deviceId`, `mode`, `playing` → the lease answer (Section 5.4) |
| POST | `/dj/dashboard/player-lease/release` | the beacon of a window that leaves |
| POST | `/dj/dashboard/player-command` | `command` → 204, 409 when nobody plays |
| GET | `/dj/dashboard/recent-tracks` | the 30 newest playable entries of the timeline |
| POST | `/dj/dashboard/play` | a guest song confirmed played |
| GET | `/dj/dashboard/fallback-queue` | the "up next" fragment; `X-Queue-Version` |
| POST | `/dj/dashboard/fallback-queue/move`, `/place`, `/skip` | 204, 409 when the track is no longer queued in the current playlist |
| POST | `/dj/dashboard/fallback-playlist` | save + import (headers, Section 5.4) |
| POST | `/dj/dashboard/fallback-shuffle` | toggle + re-order; `X-Fallback-Shuffle` |
| POST | `/dj/dashboard/vibe`, `/limits`, `/playback-mode` | settings (`mode` = AUTO / MANUAL; without it a toggle) |
| POST | `/dj/dashboard/dj-pick` | YouTube only, no AI judgement |
| POST | `/dj/requests/{id}/push-to-spotify` | |
| GET | `/dj/history-view`, `/dj/history-view/fragment` | `limit` (50..300), `filter` |
| GET | `/dj/qr-print` | `layout` = poster / cards |
| POST | `/dj/start-party`, `/dj/end-party`, `/dj/delete-account`, `/dj/logout`, `/dj/feedback` | |
| GET | `/dj/spotify/login`, `/dj/spotify/callback` | Section 5.3 |

---

## 13. Known Limitations & Technical Debt

### Architecture
- No user table: the DJ is the `owner_id` of the party; one party per DJ. Old parties are never cleaned up (their requests and
  tracks are, after 30 days).
- **Single instance:** the player lease, the caches and the guest limits (except the YouTube search count) are in memory — a second
  instance would need them shared. The HTTP sessions are in the database (review 2.3); each request with a session reads it and
  writes its last access time (the dashboard: two requests per window every 3 s).

### Security
- Spotify tokens in plain text in the database (review 5.3).
- The CSP is report-only until switched on; inline styles stay allowed until the `style="…"` attributes become classes.
- `REVIEW.md` lists what else is open.

### Front end
- Polling every 3 s (ETag / 304), no WebSockets. A window that does not play still polls at full speed while hidden (review 3.4).
- `<html lang>` follows the bundle that wrote the texts (`th:lang="#{html.lang}"`; `HtmlLangDeclarationTest`).

### Testing
- **Unit tests** (`mvnw test "-Dtest=!Scan2playApplicationTests,!*IT"`, no database): 540, 1 skipped (the fixture recorder,
  `PlayLogFixtureRecorderTest`, runs only with `S2P_FIXTURE_OUT` and a throw-away `s2p_*` database). Pure Mockito, plus template
  rendering with the real bundles (`DashboardPageRenderTest`, `GuestPageRenderTest`, fragment tests) and `SmokeTest` (`@WebMvcTest`
  with the real security chain).
- **Database tests** (`mvnw verify -Pit`): 27 `*IT` on a real PostgreSQL — `PostgresIntegrationTest` creates and drops its own
  `s2p_it_*` database and starts the whole application on it: `MigrationIT`, `FallbackQueueIT`, `FallbackQueueConcurrencyIT` (fails
  with "deadlock detected" without the advisory lock), `SongRequestRepositoryIT`, `YouTubeSearchBudgetIT`, `ApplicationSetupIT`, `SessionStoreIT` (what the app keeps in a session survives
  the database and another repository; the cleanup of expired sessions), `TimestampMigrationIT` (its own database: V11, rows the old
  way, then V12 — the same moments; red when the old values are read as UTC).
  New queue SQL gets a test there.
- **Browser tests**: 60 scenarios (Section 5.4, "Testing").
- **CI** (GitHub Actions, every push to `dev` / `main` and every PR): `unit-tests.yml` (also checks that
  `.github/copilot-instructions.md` is `AGENTS.md`), `db-tests.yml` (`postgres:18`), `browser-tests.yml`. `gh` is not installed
  locally; the public API shows the runs, and failed tests are written as public annotations. **Dependabot**
  (`.github/dependabot.yml`): security fixes only (version updates off by `open-pull-requests-limit: 0`); it works from the default
  branch `main` and needs "Dependabot alerts" and "security updates" switched on in the repository's settings.
- Not covered: the real YouTube player, real devices, the guest's page in a browser, Spotify end to end.

### AI
- No retry when Gemini is down; no server-side check that a song exists (the search is the check).

---

## 14. Roadmap — V2.0 "Master Queue" (done)

The browser used to decide what to play (a scan of the queue table, then `loadPlaylist()` of the background playlist inside the
YouTube player). Now **the server decides and the browser plays** — "dumb client, smart server":

| Phase | What | Done |
|-------|------|------|
| 1 | the next guest song chosen by the server | 2026-09 |
| 2 | Flyway; the background playlist imported into `fallback_track`; `POST next-track`; `youtube-autopilot.js` reduced to "when idle / ended / failed, ask and `loadVideoById`" (stage 4) | 2026-09-29 |
| 3 | the "up next" list: an order fixed in advance, titles, moves and drags, the queue's advisory lock | 2026-09-29 |
| 4 | the player lease (one window plays), ⏭ / ⏮ / ⏯ from any window, long lists, one timeline of what played, the play log (V7), the tabs | 2026-09-29/30 |

**Rules that came out of it and still hold:** guest songs stay in `song_requests` (there is no unified queue table and no approval
gate — Auto-Pilot only decides whether the next track starts by itself); a background track is consumed when it is handed out, a
guest song when the player confirms it; no pre-fetching (ENDED → next PLAYING measured 230–250 ms, YouTube's own load time).
**Concurrency:** every method of `FallbackTrackCommandService` that changes the queue first takes `pg_advisory_xact_lock` for the
party (`FallbackTrackRepository.lockQueue`) — a stress test deadlocked without it; the key is "S2PQ" in the upper 32 bits and the
party code read in base 36 in the lower ones, so two parties never share it. `takeNextTrack` reads and claims the head of the queue
under that lock in one attempt.

The full record of the phases, their decisions and how each was verified: `docs/history/project-context-2026-10-01.md`, Section 14.
