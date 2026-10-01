# Scan2Play — Project Context

> Last updated: 2026-09-28 (dev branch created; content reviewed against `main` @ 5314006)
> This document describes the full state of the application as of the latest commit on branch main.
> AI agents and developers should read this file before implementing new features or making architectural decisions.
> See also `AGENTS.md` for coding-style/role instructions, and Section 14 for the planned (not yet implemented) V2.0 backend-driven queue architecture.

---

## 1. Product Overview

**Scan2Play** is an AI-powered music request and virtual DJ platform for events.

Guests scan a QR code, submit song requests through a simple mobile web interface, and the AI (Google Gemini) evaluates whether each request fits the party vibe. Accepted songs are auto-queued to the DJ's playback — either server-side via Spotify API or client-side via the embedded YouTube IFrame Player.

The platform targets parties, clubs, weddings, corporate events, and any scenario where a single person controls the music.

**Production URL:** `https://www.scan2play.com.pl`

---

## 2. Technology Stack

| Layer            | Technology                                       |
|------------------|--------------------------------------------------|
| Language         | Java 21                                          |
| Framework        | Spring Boot 4.0.3                                |
| Web              | Spring MVC + Thymeleaf + Bootstrap 5             |
| Security         | Spring Security + OAuth2 Client                  |
| Database         | PostgreSQL (Spring Data JPA / Hibernate)          |
| DB migrations    | Flyway (`src/main/resources/db/migration/V<n>__*.sql`) — see "Database migrations" in Section 10 |
| AI               | Google Gemini API (`google-genai` SDK 1.38.0)    |
| Music: Spotify   | `spotify-web-api-java` 8.4.1 + custom OAuth2    |
| Music: YouTube   | YouTube Data API v3 (REST via RestClient) + IFrame Player API (client-side) |
| QR Codes         | ZXing 3.5.3                                      |
| Build            | Maven                                            |
| Caching          | Spring Cache + Caffeine (per-cache TTL)          |
| Async            | `@Async` (Spring)                                |
| i18n             | `messages.properties` (EN) + `messages_pl.properties` (PL) |

---

## 3. Architecture

Monolithic Spring Boot application with a simple layered architecture:

```
controller/     → HTTP request handlers (Thymeleaf views + REST for feedback)
service/        → Business logic
repository/     → Spring Data JPA interfaces
entity/         → JPA entities (database tables)
model/          → Enums, records, DTOs
integration/    → External API contracts (interfaces, constants)
config/         → Spring configuration beans
util/           → Utility classes
```

The UI is server-side rendered via Thymeleaf with:
- AJAX polling (every 3s) for live dashboard queue updates, with **ETag / 304 Not Modified** to skip DOM replacement when queue unchanged
- AJAX form submissions (YouTube mode) to preserve the embedded player state
- AJAX tab switching (Queue ↔ History) without full page reload
- Client-side **table sorting** (persistent across polling refreshes) on queue and history tables
- REST endpoint for feedback (JSON request/response)
- Bootstrap 5 Modal/Toast for feedback UI
- **Song autocomplete** (iTunes Search API, client-side, debounced) on guest and DJ pick forms

---

## 4. Domain Model

### 4.1 Entities (Database Tables)

#### `PartySettingsEntity` → table: `party_settings`

Represents a single party/event session owned by one DJ.

| Field                  | Type                | Notes                                      |
|------------------------|---------------------|--------------------------------------------|
| `id`                   | Long (PK, auto)     |                                            |
| `partyCode`            | String(5), unique   | Random alphanumeric code (e.g., `X7B9Q`)   |
| `ownerId`              | String, unique      | OAuth2 user ID (Spotify ID or Google sub)  |
| `active`               | boolean             | `true` = accepting requests                |
| `globalVibe`           | VibeType (enum)     | Forced vibe for the event                  |
| `activeProvider`       | MusicProviderType   | **Immutable after creation** (SPOTIFY/YOUTUBE) |
| `playbackMode`         | PlaybackMode        | MANUAL or AUTO                             |
| `requestLimit`         | int (default 2)     | Max requests per guest per cooldown window |
| `cooldownMinutes`      | int (default 3)     | Cooldown window in minutes                 |
| `duplicateCheckWindow` | int (default 15)    | Recent songs to check for duplicates       |
| `fallbackPlaylistUrl`  | String(500)         | YouTube playlist URL for background music when queue is empty (nullable) |
| `fallbackShuffle`      | boolean (default true) | Whether the fallback playlist plays in shuffled order |
| `spotifyAccessToken`   | String(2048)        | Spotify playback token (nullable)          |
| `spotifyRefreshToken`  | String(2048)        | Spotify refresh token (nullable)           |
| `spotifyTokenExpiresAt`| LocalDateTime       | Token expiration timestamp                 |

**Indexes:** `idx_owner_id` on `ownerId`.

**Key constraint:** `ownerId` is UNIQUE — one DJ = one party at a time.

**Key behavior:** `activeProvider` is set once at party creation time based on which OAuth2 provider the DJ used to log in. It cannot be changed via the dashboard (immutable session state).

#### `SongRequestEntity` → table: `song_requests`

Represents a single song request submitted by a guest or manually by the DJ.

| Field         | Type            | Notes                                    |
|---------------|-----------------|------------------------------------------|
| `id`          | Long (PK, auto) |                                          |
| `partyCode`   | String(5)       | FK-like reference to party               |
| `songName`    | String          | Song identified by AI or entered by DJ   |
| `style`       | String          | Guest-selected vibe/style or "DJ Pick"   |
| `decision`    | String          | `"accepted"`, `"rejected"`, or `"played"` |
| `djComment`   | String(500)     | AI-generated witty comment or "DJ's Choice 🎧" |
| `energyLevel` | int             | 1–10 energy rating (0 for DJ picks)      |
| `requestedAt` | LocalDateTime   | Timestamp of request                     |
| `trackUrl`    | String(500)     | Spotify/YouTube URL (nullable)           |
| `playedAt`    | LocalDateTime   | When the request became `"played"` (V6; nullable — not yet played, rejected, or played before V6). Set in one place, `DjService.markPlayed`, and kept when it is already set |

**Indexes:** `idx_party_code` on `partyCode`, `idx_party_decision_time` on `(partyCode, decision, requestedAt DESC)`.

**Retention (owner's decision 2026-09-29, built 2026-09-30):** a request is deleted **30 days after `requestedAt`**
(`SongRequestEntity.MAX_AGE_DAYS`) by a nightly job, `SongRequestRetentionService.purgeStaleRequests` (04:45, after the cache
cleanup at 04:00 and the playlist purge at 04:30), and with the account (`AccountDeletionService`). Any decision, played or not, is
purged; a row without `requestedAt` (the code always sets it) has no age and is left alone. The reason: `trackUrl` holds YouTube video
IDs that came from the search API — API data, at most 30 calendar days (III.E.4.d) — and the privacy pages (`privacy.html`,
`privacy_pl.html`, "Data Retention") say 30 days; before this only the account deletion removed requests while the pages said "for
the duration of the party session". The purge is **bounded**: batches of 1000 rows (`DELETE ... WHERE id IN (SELECT id ... WHERE
requested_at < :cutoff LIMIT :batch)`, `SongRequestRepository.deleteRequestedBefore`, each batch its own transaction) and at most
200 batches a night, the rest waits for the next night. No index of its own: PostgreSQL 18 answers the statement with a skip scan of
`idx_party_decision_time` (1.9 ms for nothing to delete in 300 000 rows), and even a sequential scan of 300 000 rows took 155 ms
(checked on a throw-away database). The guests' part of the DJ history therefore reaches back 30 days at most, like the playlist's.

**Defensive truncation:** `@PrePersist` / `@PreUpdate` callback automatically truncates `songName` (255), `djComment` (500), and `trackUrl` (500) before every save to prevent `DataIntegrityViolationException` from AI-generated content.

#### `FeedbackEntity` → table: `feedback`

Stores bug reports and feature ideas submitted by DJs from the dashboard.

| Field         | Type            | Notes                                    |
|---------------|-----------------|------------------------------------------|
| `id`          | Long (PK, auto) |                                          |
| `partyCode`   | String(5)       | Party context of the submitting DJ       |
| `ownerId`     | String          | OAuth2 owner ID of the DJ                |
| `message`     | String(2000)    | Feedback message body                    |
| `submittedAt` | LocalDateTime   | Timestamp of submission                  |

**Indexes:** `idx_feedback_submitted_at` on `submittedAt`, `idx_feedback_owner_id` on `ownerId`.

#### `FallbackTrackEntity` → table: `fallback_track` (Flyway `V2`, `V4`, `V5`, `V8`)

Server-side copy of a party's fallback ("background music") playlist — Section 14, Phase 2. Written when the DJ
sets the playlist. Served by `POST /dj/dashboard/next-track` (stage 3), which `youtube-autopilot.js` has called since
stage 4 — the client no longer plays the playlist itself (no `loadPlaylist()`).

| Field              | Type                    | Notes                                                             |
|--------------------|-------------------------|-------------------------------------------------------------------|
| `id`               | Long (PK, auto)         |                                                                   |
| `partyCode`        | String(5)               | Owning party (no FK, like `song_requests`)                        |
| `playlistId`       | String(64)              | Source: playlist ID, or `V:<videoId>` for a single video          |
| `videoId`          | String(20)              | 11-char YouTube video ID                                          |
| `title`            | String(255, nullable)   | Video title shown to the DJ (V4); same API call and 30-day retention as the row. `null` for rows imported before V4 and for a single video whose title could not be looked up |
| `playlistPosition` | int                     | 0-based order within the source playlist                          |
| `playOrder`        | int                     | When it plays (V4): among `QUEUED` tracks of a playlist the lowest value goes first, ties by `playlistPosition`. Playlist order, a random order (shuffle), or playlist order continuing after the last played track |
| `manualMove`       | boolean                 | True while the DJ has moved this track by hand within the current order (V5); cleared by every statement that gives the queued tracks a new order (import, shuffle, a new round, the shuffle switch) |
| `status`           | FallbackTrackStatus     | `QUEUED` → `PLAYED`, or `CANCELLED` (playlist changed/cleared), or `SKIPPED` (V8: the DJ skipped it for this round — it goes back to `QUEUED` with the next round) |
| `fetchedAt`        | LocalDateTime           | When fetched from the YouTube API — basis of the 30-day retention |
| `playedAt`         | LocalDateTime (nullable)| When the player took the track **in the current round** — cleared when the playlist starts a new round, so it is not the history (that is `fallback_play`, below) |

**Soft invalidation:** changing/clearing the playlist flips still-`QUEUED` rows to `CANCELLED` (never deletes); `PLAYED`
rows stay until the round ends (a new round puts them back in the queue) — the history of what played is no longer kept
here but in `fallback_play`. `SKIPPED` rows (the DJ's ✕, Section 5.4 "Skipping a track") are treated like `PLAYED` ones: they wait
out the round and the next round re-queues them (`requeuePlayedTracks` takes both, and only those of the party's newest import — a
row skipped from a playlist that was since replaced or imported again stays `SKIPPED` for good, until the 30-day purge). The
`status` column has a check constraint (`fallback_track_status_check`, created by `V2`, widened by `V8` for `SKIPPED`). **Retention:** rows older than 30 days are purged daily at 04:30
(`FallbackTrackCommandService.purgeStaleTracks`) and on account deletion. **Indexes (`V9`, 2026-10-01):**
`idx_fallback_track_queue` on `(party_code, playlist_id, status, play_order, playlist_position)` — every queue query, read in play
order without a sort (`MigrationIT` checks the plan) — and `idx_fallback_track_party_fetched` on `(party_code, fetched_at)` — the
newest import of a party, and the purge by a skip scan. (`V2`'s `(party_code, status)` was dropped.) **An import is one statement:**
`FallbackTrackRepository.insertTracks` — `INSERT … SELECT FROM unnest(videoIds, titles) WITH ORDINALITY` (the position and the play
order are the ordinal − 1) — instead of one INSERT per track under the queue's lock (identity ids cannot be batched; review 1.4).

#### `FallbackPlayEntity` → table: `fallback_play` (Flyway `V7`)

The play log: one row each time the player takes a track from the party's background playlist — what the DJ history's
"Playlist" rows and ⏮ / ⏭ read (Section 5.4, "The history is one timeline"). It exists because `fallback_track` cannot
be the history: when the playlist starts a new round its played tracks go back in the queue and lose `playedAt`, so a
short playlist that looped during a party used to lose its older history. A row is written by
`FallbackTrackCommandService.takeNextTrack` in the same transaction — and under the same per-party advisory lock — as the
claim of the track, and never changes afterwards; rounds, the queue, moves and a replaced or cleared playlist do not touch
it.

| Field       | Type                     | Notes                                                                                        |
|-------------|--------------------------|----------------------------------------------------------------------------------------------|
| `id`        | Long (PK, identity)      | The identity of **one play**, not of the track: `next-track` answers with it for a background track and the dashboard knows the track as `B:<id>` (`HistoryEntry.key()`) — the same video in two rounds of a playlist is two rows, two ids, two history entries |
| `partyCode` | String(5)                | Owning party (no FK, like `song_requests`)                                                   |
| `videoId`   | String(20)               | 11-char YouTube video ID (a snapshot — the row does not depend on `fallback_track`)          |
| `title`     | String(255, nullable)    | The title at import; `null` when it was unknown (the history then shows `youtu.be/<id>`)     |
| `fetchedAt` | LocalDateTime            | When the track was fetched from the YouTube API, copied from the track — basis of the 30-day retention |
| `playedAt`  | LocalDateTime            | When the player took the track (the same instant as the track's own `playedAt`)              |

**Index:** `idx_fallback_play_party_played` on `(partyCode, playedAt DESC, id DESC)` — serves exactly the bounded history
read (`FallbackPlayRepository.findRecent`; checked with `EXPLAIN` on 60 000 rows: an index scan, no sort).
**Retention:** the video ID and title are YouTube API data obtained with the API key alone (*Non-Authorized Data*), which
the API Services Developer Policies (III.E.4.d) allow to be stored for at most 30 calendar days — the owner asked whether
titles in a history are covered, and the policy text says nothing that exempts them — so the log is purged **by
`fetchedAt`**, in the same daily job as `fallback_track` (`purgeStaleTracks`), and removed with the account
(`AccountDeletionService`). A row therefore never lives longer than the track it copied; a party that keeps one playlist for
more than ~30 days loses the plays of the older import all at once (the same thing happened to the `PLAYED` tracks before).
Changing or clearing the playlist does **not** clear the log (owner's decision 2026-09-29): what played stays in the
history. The migration copies the tracks that were `PLAYED` with a `played_at` at that moment; plays of earlier rounds
were already lost and cannot be recovered.

### 4.2 Enums

| Enum                | Values |
|---------------------|--------|
| `MusicProviderType` | `SPOTIFY`, `YOUTUBE` |
| `PlaybackMode`      | `MANUAL`, `AUTO` |
| `FallbackTrackStatus` | `QUEUED`, `PLAYED`, `CANCELLED`, `SKIPPED` |
| `VibeType`          | `ANY`, `BACHATA_AND_KIZOMBA`, `CLASSICAL_MUSIC`, `CHILLOUT_AND_LOUNGE`, `CLUB_AND_EDM`, `DISCO_POLO`, `HIP_HOP_AND_RAP`, `JAZZ`, `REGGAETON_AND_DANCEHALL`, `POP_AND_DANCE`, `RETRO_80S_90S`, `ROCK_AND_METAL`, `SALSA_AND_TIMBA`, `WEDDING_CLASSICS` |

### 4.3 Records

| Record       | Fields | Purpose |
|-------------|--------|---------|
| `DjResponse` | `decision`, `comment`, `songName`, `energyLevel` | AI evaluation result |

---

## 5. User Flows

### 5.1 DJ Flow (Party Creation & Management)

```
Landing Page (/)
    ├── [Start Party with Spotify] → /oauth2/authorization/spotify
    │       → Spotify OAuth2 → /dj/dashboard (party created with SPOTIFY provider)
    │
    └── [Start Party with YouTube] → /oauth2/authorization/google
            → Google OAuth2 → /dj/dashboard (party created with YOUTUBE provider)
```

On the dashboard, the DJ can:
- View active song queue (auto-refreshes every 3s via AJAX polling with ETag/304 optimization)
- **Sort queue and history tables** by column (Time, Song, Energy) — persists across poll refreshes
- Change global vibe (VibeType dropdown)
- Configure rate limits (max requests, cooldown, duplicate window)
- **Manually add songs to queue** ("DJ Pick" — bypasses AI, YouTube only; AI normalizes song name to canonical "ARTIST - TITLE" format for consistent YouTube cache keys)
- Connect Spotify for playback control (separate OAuth2 flow via `/dj/spotify/login`)
- Toggle Auto-Pilot mode (auto-queues accepted songs)
  - **Spotify:** Server-side — songs pushed to Spotify queue via API
  - **YouTube:** the embedded IFrame Player plays whatever the server says is next (guest song first, else a background track — Section 5.4)
- **Set fallback playlist** (YouTube only) — background music from a YouTube playlist or single video when queue is empty; saves without page reload, includes Stop button to clear playback; **shuffle toggle** to randomize playlist order
- Manually push songs to Spotify queue
- Mark songs as "played"
- View history of played/rejected songs (AJAX tab, preserves YouTube player)
- **Submit feedback** (bug reports / feature ideas via modal, AJAX, no page reload)
- End/resume the party
- **Delete account** (removes all data — required by Google API Services User Data Policy)
- Logout

**Provider is immutable:** The music provider (Spotify/YouTube) is determined at login time based on which OAuth2 provider the DJ chose. There is no dropdown to change it on the dashboard.

### 5.2 Guest Flow (Song Requests)

```
QR Code Scan → /p/{partyCode}
    → Guest sees request form + what plays now, the next 5 guest songs in play order, and where their own song waits
    → Submits song request (name + optional style)
    → AI evaluates request via Gemini (async Callable — releases Tomcat thread)
    → Result page shows decision + DJ comment
```

**What reaches the AI (2026-10-01, review 4.6):** the guest's text as one line of at most 150 characters, its double quotes made single
(`SongEvaluationService.forPrompt` — the prompt puts the text in quotes; the form takes up to 10 KB). The style is decided by the server,
not by the form (`GuestController.styleOf`): the DJ's vibe when one is set (its name in the guest's language, as the hidden field sent
it — a guest could change that field), otherwise the guest's pick from the list (a `VibeType` name), anything else `ANY`. A rejected
request is saved under the guest's text when the AI leaves `songName` empty (the Polish prompt used to allow it: rows of April 2026
in the history had no song name).

**What the guest sees of the music (2026-09-30, the owner's decision):** under the form (`fragments/guest-queue.html`,
`GuestQueueService`) — "Twoja piosenka „…” — N. w kolejce" for the guest's first song still waiting (its position in the whole queue),
"🔊 Teraz gra" (the newest entry of the play timeline, if it started at most 8 minutes ago — the server knows when a track started,
not when it ended; the timeline covers what the embedded YouTube player played), and "Następne w kolejce": the first 5 accepted guest
songs in the order they play (`DjService.getDashboardQueue`, oldest first, the 3 s cache), the guest's own marked "Twoja". The
guest's requests are remembered in the session (`GuestSessionService.rememberRequest`, the newest 20 per party); the result page shows
the position too. Background playlist tracks are not listed: they play only when no guest song waits. Before, the page listed the 5
newest accepted requests newest first (`getPublicQueue`, removed with its query and the `publicQueue` cache) — while they play oldest
first. **Refreshing without a timer** (the owner's choice): `GET /p/{partyCode}/queue` renders the fragment alone (empty for an ended
party, 404 for an unknown one), and the page (`index.html`, `#guestQueueBox`) fetches it again when the guest comes back to it
(`visibilitychange` to visible, at most once per 5 s; `pageshow` from the back-forward cache) and on its "↻ Odśwież" button — so a room
of phones asks only when someone looks. "Teraz gra" is in the same fragment.

**Two request modes (2026-09-30, the owner's decision):** above the field, under "Czego chcesz? Wybierz:", two tiles (`.mode-tile`
in `app.css`: a line under each title, the chosen one green with a ✓, one under the other on a phone — a segmented bar did not read as
a choice, the owner's report) — **🎵 Konkretna piosenka** (the default) and **✨ Nastrój** (form field `requestMode` = `SONG` | `MOOD`, `RequestMode`; a missing one is `SONG`). Each has its own prompt
(`prompt-template_*.txt` for a song — title, artist or a line of the lyrics, never replaced by another song "in a similar vibe";
`prompt-mood_*.txt` for a mood — the AI picks one song). The help text under the field follows the mode, and the song suggestions
(`song-autocomplete.js`) are off in the mood mode (`data-autocomplete-off`). A request the AI reads as a mood in the song mode is not
saved: the guest is back at the form with the text kept, the mood mode chosen and `guest.error.mood_in_song_mode`. Before, the AI
guessed which of the two a request was and took a line of lyrics for a mood.

No guest authentication required. A request passes three limits, each counted **before** the AI evaluates it (REVIEW.md 4.1,
2026-09-30 — before, the session limit was checked before and recorded after the 2–4 s evaluation, and a request without the
cookie had no limit at all):
1. **the guest's own** — the DJ's `requestLimit` per `cooldownMinutes`, in the `HttpSession` (`GuestSessionService.tryAcquire`:
   check and record in one step under the session mutex); message `guest.error.rate_limit`;
2. **client IP + party** — `guest.limit.per-ip-party` (30) per `guest.limit.per-ip-window-minutes` (10), loose because the guests
   on a venue's Wi-Fi share one address; message `guest.error.too_many_requests` with the seconds to wait;
3. **party** — `guest.limit.per-party-daily` (300) per 24 h whatever the address, the most one party (or a script faking addresses)
   can spend; message `guest.error.party_daily_limit`.

2 and 3 are `GuestRequestLimiter` (Caffeine, fixed windows, `asMap().compute`, in memory like the sessions); 0 switches a limit off.
The client address is `getRemoteAddr()` — with the `FRAMEWORK` forward-headers strategy the *first* `X-Forwarded-For` entry,
which a client can fake — unless `guest.client-ip-header` names a header a proxy overwrites (`CF-Connecting-IP` behind Cloudflare;
set it in production when the traffic goes through Cloudflare — **set on Railway on 2026-09-30**, the production domain is proxied by
Cloudflare and the service has no `*.up.railway.app` domain that would bypass it). A faked address gets past 2 only; 3 and the YouTube
search fuse (Section 7.3) still hold.

**The DJ sees them** (the owner's wish, 2026-09-30): under the limits form of the dashboard a line per server limit
(`serverLimitsInfo`; rendered with the page and kept current by every queue poll — header `X-Guest-Limits-Use:
<busiest network>,<party>`, 304 too, `dashboard.js` `applyGuestLimitsUse`) — 2 with how much the busiest network has used of its window
(`GuestRequestLimiter.busiestClientRequestsUsed`: the address closest to its limit, usually the venue's Wi-Fi), 3 with how much the
party has used; each use is a badge, grey, yellow from 80 %, red at the limit — and above the queue a warning shows while a
limit stops guest songs — today's YouTube searches spent (YouTube parties only) or the party's 24-hour limit reached
(`guestLimitWarnings`). Every answer of the queue poll `GET /dj/dashboard/updates`, 304 included, carries
`X-Guest-Limits: none | search-spent | party-full | search-spent,party-full` (not part of the ETag), and `dashboard.js`
(`applyGuestLimits`) shows or hides the warnings by it — so they appear within a poll (3 s) without a reload.

### 5.3 Spotify Playback Auth (Secondary OAuth2)

```
Dashboard → [Connect Spotify for Auto-Pilot]
    → /dj/spotify/login?partyCode=XXXXX  (authenticated + ownership validated)
    → Spotify Authorization (playback scopes)
    → /dj/spotify/callback (authenticated + ownership validated, tokens stored in PartySettingsEntity)
```

This is a **separate** OAuth2 flow from the login. The login OAuth2 identifies the DJ. The Spotify playback OAuth2 grants permission to control the DJ's Spotify player.

**Security:** Both endpoints require DJ authentication; the login validates partyCode ownership (IDOR protection). The OAuth
`state` is a random value (32 bytes, hex) that the login keeps in the DJ's session (`spotifyOAuthState`); the callback accepts it
once and stores the tokens for the party of the logged-in DJ. (Until 2026-09-30 the `state` was the party code — public, it is in
the QR code — so a DJ could be sent a callback link with another person's authorization code; REVIEW.md 5.2.)

### 5.4 YouTube Auto-Pilot (the server decides what plays next, the browser plays it)

> **History — do not reintroduce:** a 500 ms polling watcher (`setInterval` comparing
> `getCurrentTime()`/`getDuration()`) was removed on 2026-04-06 (`68e84c9`) in favour of purely event-driven
> transitions. Until 2026-09 the browser also decided *what* to play (a DOM scan of the queue table, then
> `loadPlaylist()` for the background playlist). Since Section 14, Phase 2 stage 4, `youtube-autopilot.js` is a
> "dumb player" and knows nothing about playlists, their order or shuffle.

```
Dashboard (YouTube provider)
    → youtube-autopilot.js loads the YouTube IFrame Player API
    → dashboard.js polls #song-list every 3s (ETag/304) — the DJ's visual queue; each poll also calls
       checkYouTubeAutoPlay()

The client asks the server only when a track is about to be loaded — Auto-Pilot ON, this window holds the
player lease (see "One window plays" below), and:
    → the player is idle (UNSTARTED / ENDED / CUED): page load, Auto-Pilot switched on, a poll
      — except the first track after a page load (a reload) and after "play on this device" (a TAKE_OVER that
        succeeded): the window brings back the newest entry of GET recent-tracks, from its start, when it started at most
        10 min ago (`secondsAgo`, by the server's clock), and asks nothing (`resumeLastTrack`; the owner's decisions
        2026-09-30 — asking at load used up a track nobody heard, as a browser refuses sound in an untouched page, and a
        takeover skipped the track the other device was playing). Like a track ⏮ brought back: not confirmed again, its
        end and ⏭ go on from the queue. Any track loaded first (a ▶ link) ends it. **After a reload only the track the reload
        interrupted comes back (2026-10-01, the owner's report):** the page that plays notes on `pagehide` the key of the track
        that plays, is paused or is loading (`sessionStorage` `scan2play.interruptedTrack`, kept across a reload of the tab); a
        track that ended by itself, a track picked by hand and a window that does not play leave no note, and a page that loaded
        nothing passes the note on. The resume needs the newest entry of `recent-tracks` to be that key — so a track that had
        ended is not played again, nor one that another window has played past. With Auto-Pilot off at the reload the resume
        waits until Auto-Pilot is switched on (before, the note-less rule dropped it and the next track played — the owner's
        case: a track playing, Auto-Pilot off, a reload, Auto-Pilot on). **"Resume" (⏯) right after a reload** brings the
        interrupted track back too — the player is empty then, and the button did nothing (`resumeAfterReload`); without a
        note it still does nothing (Auto-Pilot on: the queue goes on). **A track the DJ had paused** (the note says
        `paused`) is held after the reload like a paused player — nothing starts, not even the queue with Auto-Pilot on,
        until "resume" (`isHeldByPause`; the owner's choice "A", 2026-10-01: a pause is the DJ's choice). A takeover needs no note. After the press
        the intent waits in `takeOverPending` until *any* lease answer makes the window the holder: a WATCH report sent while
        the TAKE_OVER was on its way can be answered first (the server has moved the lease by then), and the TAKE_OVER's own
        answer is then dropped as the older one — setting the resume only on that answer made it work "sometimes".
    → on ENDED
    → after a player error (the first 5 in a row at once, then one ask per lease report, every 3 s)
    → after an ask that failed (network error, 5xx, a redirect to the login page): again with every lease report until
      the server answers (`askAgain`; REVIEW.md 3.1 — the dashboard's poll asks only when the guest queue changed, so
      before this a failed ask at the end of a track left the party silent)
  → POST /dj/dashboard/next-track?partyCode=…&deviceId=…[&exclude=<guest song ids the player failed on>]
       200 {source: GUEST | BACKGROUND, id, videoId}     204 = nothing to play
           id = the request id (GUEST), or the id of the play log row that this hand-out wrote (BACKGROUND, table
           fallback_play) — not the queue's track id: the client keeps it as its key B:<id> in the history
       409 = another window holds the player lease (nothing was handed out)
  → player.loadVideoById(videoId)

The server (NextTrackService) answers with, in this order:
    1. the oldest accepted guest song with a resolvable video ID, minus `exclude`
       (DjService.findNextPlayableGuestTrack — reads only; the client confirms it)
    2. else the next QUEUED track of the DJ's current fallback playlist — the one with the lowest `playOrder`
       (fixed in advance: playlist order or a random order, so the DJ can be shown what comes next); marked
       PLAYED and written to the play log as it is handed out; the playlist loops (the next round is prepared as soon as
       the last track is taken)
    3. else 204 (no fallback playlist, or nothing could be imported)

Confirmation:
    → GUEST: on PLAYING the client calls POST /dj/dashboard/play (once per song) — the song leaves the queue
    → BACKGROUND: nothing to confirm; a track that fails to play is already PLAYED, the client just asks again
```

**Rules of the player** (deliberate, agreed with the owner 2026-09-29):
- A running track is never interrupted: a guest song that arrives while a background track plays waits for it
  to end. No request is sent while a track plays or is paused — `POST next-track` is **not read-only**, so it is
  never used to poll.
- A paused player is left alone — a pause is the DJ's choice.
- Auto-Pilot **off**: nothing starts by itself when a track ends; the DJ picks tracks by hand (the ▶ links call
  `playInEmbeddedPlayer`). A hand-picked track is not overridden even with Auto-Pilot on; Auto-Pilot carries on
  when it ends.
- **Auto-Pilot is one setting of the party, followed by every window** (2026-09-30, the owner's report: Auto-Pilot on
  one device, a second one that still showed it off took the playback over and its player stayed empty). A window used
  to know only the value it was loaded with — the queue poll's `<tbody>` carries `data-playback-mode`, but its ETag is a
  fingerprint of the guest queue, so a change of the setting alone never reached another window. Now every lease answer
  carries `playbackMode` and `dashboard.js` (`applyPlaybackMode`) sets the attribute, the switch and the wake lock by
  it (a change reaches every window within one report; an answer to a report sent before the DJ flipped the switch in
  that window is ignored). The switch sends the state it shows (`mode=AUTO|MANUAL`), not a toggle — a click on a stale
  switch used to invert the setting; without `mode` the endpoint still toggles (old pages, the Spotify form).
  **What a track does when it ends follows the current setting**: on — the next one from the queue; off — the player
  stops. "Play on this device" brings the last track back with Auto-Pilot off too (the DJ asked for music there;
  `resumeWithoutAutoPilot`); it then stops at its end.
- If the answer to a lookup arrives after the DJ has started a track or switched Auto-Pilot off, it is dropped
  (a background track handed out for it is skipped for that round; a guest song is not consumed until it plays).

**One window plays** (Section 14, Phase 4 stage 0; decided with the owner 2026-09-29): every open dashboard has its
own YouTube player, and each one used to ask `next-track` while Auto-Pilot was on — a second window (the DJ peeking
from a phone, or a second tab) took a track off the queue that the first window never played (a background track is
consumed when it is handed out; a guest song would have played on the phone). So a party has one **player lease**
(`PlayerLeaseService`, in memory like the caches — single instance): the window that holds it plays, the others only
show the queue.
- Every window has a random id (`sessionStorage`, so a reload keeps it) and reports to
  `POST /dj/dashboard/player-lease` (`partyCode`, `deviceId`, `mode`) every 3 s; the answer is `{holder, free}`. A lease
  that is not renewed for 10 s is free (tab closed, computer asleep). Modes: `CLAIM` — renew if held, take only when
  nobody holds it (what a freshly opened or a playing window sends); `WATCH` — never take, only report (what a window
  that has been told another one plays keeps sending, so it never starts playing by itself later); `TAKE_OVER` — the
  DJ pressed "play on this device".
- A window asks for tracks only while it is the holder (`isPlayerDevice` in the script; unknown until the first
  answer). The server backs that up: `next-track` answers **409** to a window that is not the holder (no id counts as
  another window) and hands nothing out. With nobody holding a live lease anyone may ask.
- A window that is not the holder shows a banner in the YouTube Player card (`fragments/player-lease-banner.html`,
  hidden until needed) — "playback runs on another device" with a *Play on this device* button, or "no device is playing"
  when the lease is free. Taking the lease from a window that still plays asks first (a stray tap on a phone) and stops
  the old window's player at its next report (≤ 3 s); when the lease is free it needs no confirmation. Such a window
  also does not start sound from a ▶ link (`playInEmbeddedPlayer` returns false, the link just opens YouTube), nor from the
  play button of its own YouTube player: PLAYING / BUFFERING in a window that does not hold the lease is stopped at once
  (`onPlayerStateChange`; the owner could still play the old device's player after a takeover, 2026-09-30). Queue,
  history, the "up next" list, moves, drags, *Mark Played* and the settings work in every window.
- The holder gives the lease up on `pagehide` (`sendBeacon` to `.../player-lease/release`, CSRF token in the body as
  `_csrf`), so a window opened right after another was closed is not kept waiting for the timeout; if the beacon does
  not arrive the timeout does the same.
- A failed report (server error, login redirect) changes nothing — the window that plays does not go silent. A `409`
  from `next-track` makes the window a non-holder at once (it stops what it plays and shows the banner).
- **A playlist saved or cleared in a window that does not play** (the *Save* / *Stop* buttons call `updateFallbackSource()` /
  `stopFallback()`, which only reach the player of the window where the DJ clicked): the answer to a lease report names
  the party's current fallback playlist (`fallbackPlaylistId`, `V:<id>` for a single video, null when there is none) and
  every background track that `next-track` hands out names the playlist it came from (`playlistId`). The window that
  plays compares the two on every report; when the running background track comes from another playlist than the
  current one it stops that track and asks for the next (≤ 3 s after the change). A guest song or a track the DJ picked
  by hand is never stopped by this, and the check is idempotent — after the change the new track carries the new id.
  A report that was sent *before* the running track was loaded is ignored (`trackLoadedAtLeaseSeq`): with a slow
  network its answer can still describe the old playlist and would stop the track that was just started for the new one.
  The shuffle switch, moves and drags need nothing of the kind — they change the queue on the server, and the player
  simply takes the next track from it.
- Consequences to know: taking over interrupts the track playing in the old window (a guest song is already marked
  played by then); a duplicated tab copies `sessionStorage`, so both tabs share an id and both count as the holder; a
  server restart frees every lease and the window that plays takes it back with its next report (if a second window
  reports first it gets it — press *Play on this device* in the right one).

**Next ⏭ — from any window** (Phase 4 stage 1; owner's decisions 2026-09-29): a button under the video
(`fragments/player-controls.html`, handled by `youtube-autopilot.js`). It skips to whatever `next-track` hands out now —
a waiting guest song first, else the head of the background queue — **whatever the player is doing and with Auto-Pilot
off too**: it is a deliberate act of the DJ, so neither the "player must be idle" nor the "Auto-Pilot must be on" rule of
`tryAutoPlay` applies (the track that ends later still does not start another by itself while Auto-Pilot is off). If
there is nothing to play (204) the track that plays now carries on. Only the window that holds the lease can start a
track, so:
- in **the window that plays** it acts at once (`skipToNext`; a press while a lookup is in flight is ignored). Its ask adds the
  guest song that runs now to `exclude` (REVIEW.md 1.3): a guest song leaves the queue only when the server has its confirmation
  (`POST /play`, sent on PLAYING) and next-track reads the queue through a 3 s cache, so a ⏭ while the song loaded, or in its first
  seconds, got the same song back. `DjService.markSongAsPlayed` also evicts `dashboardQueue` after its commit. A guest song skipped
  while still loading was never confirmed, so it stays in the queue and comes back after the track ⏭ started (scenarios
  `next-right-after-guest-song-started`, `next-while-guest-song-loads`);
- in **another window** (the phone as a remote control) it sends `POST /dj/dashboard/player-command` (`command=NEXT`) and
  stays disabled ("Sent…") for 3.5 s. The server keeps the command per party until the window that plays collects it with
  its next lease report (the answer has a `command` field, handed out once), so it is carried out within about 3 s.
  Only one command waits per party: a second press within the interval skips once (the disabled button says so). It is
  refused with **409** when no window holds a live lease (nobody would ever carry it out — the banner then says "no device
  is playing"), and a waiting command never outlives the lease it was given for: it is dropped when the lease changes
  hands, is given up or expires. A command that reaches the window while it is in the middle of a lookup is lost (the
  DJ presses again).

**Back ⏮ — like a normal player** (Phase 4 stage 2; the button before ⏭ in `fragments/player-controls.html`, the same
channel: in the window that plays it acts at once, in another window it was the command `PREVIOUS` (since 2026-09-30 a window
that does not play has two buttons of its own instead — see "Two buttons in a window that does not play", below; `PREVIOUS` is
still accepted, for a page opened before that), one command per party,
the last press wins). `skipToPrevious` in `youtube-autopilot.js`:
- a track that is playing (or paused, or buffering) and has been running for more than **3 seconds**
  (`RESTART_AFTER_SECONDS`; the button's tooltip in the bundles says "3 seconds" — keep them equal) starts again (`seekTo(0)`);
- **a second press soon after such a restart goes back a track** (the owner's decision 2026-09-29, "option B"): when ⏮
  restarts a track `skipToPrevious` notes `{trackLoads, time}` (`lastRestart`; `trackLoads` counts every track loaded into the
  player — all loads go through `loadIntoPlayer` — so the note holds only for the track that was restarted, and any other
  track makes it stale), and a ⏮ within `DOUBLE_PRESS_MS` = **20 seconds** of it (10 s at first; the owner asked for 20 s after
  trying it on the phone, 2026-09-29) skips the restart and goes to the track
  before, however long the restarted track has played by then. It is needed for another window: a remote press is disabled for
  3.5 s (`COMMAND_PENDING_MS`) and reaches the window that plays with its next lease report (every 3 s), so two presses are
  always more than 3 s apart, the track has played longer than `RESTART_AFTER_SECONDS` each time, and without this the previous
  track could never be reached from the phone. A single press is unchanged, locally and remotely (the tooltip says
  "20 seconds" — keep it equal to `DOUBLE_PRESS_MS`; the browser scenario `double-press-window` presses 19 s and 21 s after the
  restart and reads the tooltip);
- otherwise the track that played **before** it comes back. The list is the server's timeline of what played
  (`GET /dj/dashboard/recent-tracks`, below — the same whichever window played the tracks, so a reload or a switch of device
  loses nothing). The running track is found in it by its **key** (`G:<request id>` for a guest song, `B:<play id>` for a
  background track — the id of the play log row, so a video that plays in two rounds of a short playlist has two keys and
  "back" cannot land on the wrong one; `nowPlayingKey` is set when the server hands a track out and when a track comes
  back, and is `null` for a track the DJ picked by hand with a ▶ link): the entry after it (older) is played; a track not in the list gets the newest
  entry; when nothing plays (the track ended) and the track is in the list, that same track plays again ("the track that
  ended is what back goes to"); with nothing older the track starts again. Pressed again, back walks further into the past;
- a track that comes back is **not** marked as played again (no `POST /dj/dashboard/play`, so it does not move in the
  history), is not a "background track" (the playlist check of the lease does not touch it) and, when it ends, Auto-Pilot
  carries on with the **queue** (`next-track`), so that a party does not hear the tracks in between twice by accident;
  **⏭ pressed on a track that came back retraces the steps** (`playingFromHistory`, set by `replayTrack`; the owner's question
  2026-09-29 — before, ⏭ after ⏮ asked `next-track`, and a guest song that had played was never handed out again, so ⏮ then ⏭
  seemed to lose it, while ⏮ again found it): `skipToNext` fetches `recent-tracks` and plays the entry that is one **newer** than
  the running track (no confirmation, no new `played_at`), and only at the newest entry — or for a track not in the list, such as
  one picked by hand — asks `next-track` as usual. Any track the server hands out (`playTrack`), a hand-picked ▶ track and losing
  the lease clear the flag, so the natural end of a track that came back still goes to the queue. Like ⏮ it does nothing when the
  server cannot say what played, and the remote ⏭ behaves the same (it runs the same function in the window that plays). For
  example `[G, B2, B1]` (G a guest song that played after B2): ⏮ → B2, ⏭ → G, ⏭ → the next track of the queue. Repeated ⏮
  goes further back, repeated ⏭ further forward;
- **a change of the playlist ends the retracing** (the owner noticed it on the phone, decided 2026-09-29, built 2026-09-30): with
  one playlist, ⏮ several tracks back and then a new playlist saved, ⏭ used to have to be pressed through every old track before
  the new playlist began. Retracing makes sense for a guest song that was never re-queued, not for the tracks of a playlist the DJ has
  just left. So when the playlist changes, `playingFromHistory` is cleared and ⏭ asks `next-track` at once: **in the window where the
  DJ saved or cleared it** `window.updateFallbackSource` / `window.stopFallback` do it (the Save button, Stop — also when the same
  playlist is saved again: the DJ has just told the player to start from the playlist); **in another window** the lease answer names the
  party's current playlist, and `notePlaylist` clears the flag when an answer names another one than the last answer did (the first
  answer only sets the reference). The track that plays now — one that came back through ⏮ — is **not** interrupted, like a guest song
  (it is not a background track, so the stale-playlist check of the lease leaves it alone); the old playlist's plays stay in the
  timeline, so ⏮ from the new playlist still goes back into the old one. Like `dropStaleBackgroundTrack`, `notePlaylist` trusts only a
  lease report that was sent *after* the track came back (`trackLoadedAtLeaseSeq`): a report sent before it may be about a change that
  came *before* the DJ went back to look at the old tracks, which ⏭ should then retrace. Browser scenarios: `next-after-playlist-saved`,
  `-cleared`, `-changed-elsewhere`, `back-into-the-old-playlist`, `retrace-survives-a-change-in-flight` (the last one turns red if the
  `trackLoadedAtLeaseSeq` condition is left out — checked);
- a guest song joins the timeline when the player confirms it (`played_at` is set then), a background track when the server
  hands it out — so a track that comes back can be found again straight away; the window that is told to go back by another
  window does exactly the same, and answers 409 to the sender when nobody plays.

**Two buttons in a window that does not play — "Wstecz" and "Od początku"** (the owner's request 2026-09-30: "wstecz nie jest
intuicyjne"; built the same day). The single ⏮ has rules — a track that has played for more than 3 s starts again, a second press
within 20 s goes back a track — that work at the computer, where two presses are a moment apart, but are hard to use from a remote:
a press reaches the window that plays only with its next lease report, the button waits 3.5 s, and the DJ cannot tell which of the
two things will happen (this is what `DOUBLE_PRESS_MS` was a workaround for). So, in a window that does not play, `renderBackButtons`
(called by `applyLease`, i.e. by every lease answer and every 409) **hides the single ⏮ and shows two plain buttons**; the window
that plays — and one that does not know yet, before the first answer — shows the single ⏮ exactly as before. The markup has all
three (`fragments/player-controls.html`: `playerPreviousBtn`, and `playerBackBtn` / `playerRestartBtn` with `d-none`):
- **⏮ Wstecz** (`playerBackBtn`; command `PREVIOUS_TRACK`) — the previous track, **always**: no restart first, however long the track
  has played (`goToPreviousTrack`, the walk back of the single ⏮ without its rules: the track found by its key, "the track that ended
  is what back goes to", a replayed track is not confirmed again, ⏭ then retraces). With nothing older the track starts again.
- **↺ Od początku** (`playerRestartBtn`; command `RESTART`) — the track that runs starts again from the beginning (`restartTrack`):
  playing, paused (it stays paused — nothing calls `playVideo`) or still loading; repeated presses just restart again. When nothing
  runs because the track ended (Auto-Pilot off) that track plays again from the timeline, like back does; a track the DJ picked by hand
  is not in it, so then there is nothing to do. It never goes back a track and does not count for the double press of the single ⏮.
- Both travel like ⏭ (`POST /dj/dashboard/player-command`, one command per party — the last press wins, so Wstecz then Od początku
  within one report interval carries out only the second —, 409 when nobody plays, "Wysłano…" for 3.5 s, each button waiting for
  itself). `runCommandHere` carries a command out in the window that plays, whether it was pressed there or arrived with a lease
  answer. Messages: `dashboard.player.back.title`, `dashboard.player.restart`, `dashboard.player.restart.title`; the tooltip of the
  single ⏮ no longer says that in a window that does not play the command is sent on.
- The single ⏮ and its rules stay as they were for the window that plays (`double-press-window`). Browser scenarios:
  `back-buttons-by-role`, `remote-back-and-restart`, `play-window-carries-out-back-and-restart`, `restart-when-paused-or-ended` (two
  mutations were tried: without `renderBackButtons` the buttons do not swap; with `PREVIOUS_TRACK` routed through the single ⏮'s rules it
  restarts after 30 s instead of going back).

**Pause ⏯ — from any window** (Phase 4 stage 3, the owner's idea 2026-09-29): the middle button of
`fragments/player-controls.html`. In the window that plays it pauses or resumes its own player at once (`pauseVideo` /
`playVideo`; a paused player is the DJ's choice, so Auto-Pilot leaves it alone as before). In another window it sends the
command `PAUSE` or `RESUME` through the same channel as ⏭ / ⏮ (one command per party, the last press wins; 409 when nobody
plays). The commands are **explicit, not a toggle**, so a stale button cannot invert the state: a `PAUSE` for a player that
is paused already does nothing. To show the right label — "⏸ Pause" while the music plays, "▶ Resume" while it is paused —
the window that plays says in every lease report whether its player makes sound (`playing`: playing or buffering = true;
`PlayerLeaseService` keeps it with the lease and believes only the holder), every answer tells it back (`playing`, null when
nobody plays or nothing was said), and the button follows it; it also follows a pause made at the computer itself. The window
that plays reports a change **at once** (on PLAYING / PAUSED, an extra lease report) instead of at the next 3 s report, and a
window that has sent a pause or resume keeps saying "Sent…" until the window that plays says its player has changed (or 9 s
have passed) rather than showing the old label again for a moment — measured 4–5 s from the press to the new label. **A track that
is being loaded counts as playing** (the owner saw "Wznów" instead of "Pauza" when a rewind took a moment): after `loadVideoById` the
player is UNSTARTED or CUED for a while (or still shows the ENDED of the last video) and nothing plays yet, and both the button of the
window that plays and the `playing` of its lease reports said "not playing" — `isPlayingOrLoading()` now counts `isLoadingSong` too.
Only for `LOADING_COUNTS_AS_PLAYING_MS` = 10 s after the load, and never for a paused player: a browser that refuses to start sound in
a page nobody has touched leaves the player at CUED for good, and then "resume" (which calls `playVideo()` inside the DJ's click) is
the DJ's way out — without the limit the button would say "Pauza" for ever. Scenario `pause-while-loading` (the fake player can stay
in UNSTARTED / CUED: `fake.holdState`). A browser
may refuse to start sound in a window nobody has touched, so a remote *resume* can fail on a page that was never clicked
(the state then stays "paused" and the label tells the truth).

**Three tabs in a bar that stays in view — Panel DJ-a / Kolejka / Historia** (stage 3 made the History tab scroll its list
into view, because the owner found on a phone that "we have to go to the very end to even realise that something changed" —
the list is swapped in *below* the settings, the QR code and the player, on a phone 2194 px down a 3188 px page; the bar was
left behind at the top, so going back meant scrolling up. The owner's idea 2026-09-29, built after stage 3: three tabs in a bar
that sticks to the top of the screen.) The bar is `<nav id="djTabBar" class="dj-tabbar">` in the `dj-nav` fragment of
`fragments/components.html` (`position: sticky; top: 0`, opaque, above the sticky headers of the lists). It must be a direct
child of the page's container — `sticky` works only inside a parent as tall as the page — so the fragment's root is a
`th:block`, and the account buttons (feedback, end party, logout, delete account) form a row of their own above the bar and
scroll away with the page. Each tab is a link with `data-dj-tab="panel|queue|history"`; `initTabs` in `dashboard.js` handles
them without leaving the page, so the player keeps playing:
- **Panel DJ-a** scrolls to the top of the page (the settings, the QR code, the player and its controls). It does not change
  which list shows.
- **Kolejka** shows the queue (swapping the history away, with YouTube) and scrolls to it; **Historia** loads the history into
  `#history-content` (once — pressed again while it shows, it only scrolls there) and scrolls to it. `revealContent` puts the
  top of the list just under the bar (bar height + 8 px), unless it is already in the upper 40 % of the screen (a wide
  screen). Smooth; instant for "reduce motion" — explicitly `instant`, because Bootstrap sets `scroll-behavior: smooth` on
  the page and `auto` would inherit it.
- **The lit tab follows the page.** Panel while the page is at its top or the list's top is still below the middle of the
  screen; otherwise the list that shows (Kolejka / Historia); at the very bottom of the page the list wins, so a short page
  cannot leave Panel lit. A click lights its tab at once and holds the highlight for 900 ms (and until `scrollend`, where the
  browser has it), so that the smooth scroll passing other parts of the page does not make it flicker.
- **Plain links** — without JavaScript, for a Spotify party and on the standalone history page (`activeTab='history'`): `/dj/dashboard#top`,
  `/dj/dashboard#queue-content` and `/dj/history-view`. The anchors work because `scroll-margin-top` keeps the top of the
  target out from under the bar and the scroll-restore script in `<head>` (`fragments/components.html`) does not restore the
  saved position when the URL has a hash. On a Spotify dashboard Panel and Kolejka scroll within the page and Historia is a
  page of its own (`reloadHistory` is defined only with YouTube). The standalone page has neither list, so the script does
  nothing there. (Arriving by such a link at a YouTube dashboard, the up-next list is filled in after the jump and may push
  the queue down by its own height; the tabs of the dashboard itself do not go through a page load.)
- Messages: `dashboard.nav.panel` ("DJ Panel" / "Panel DJ-a") and `dashboard.nav.queue` ("Queue" / "Kolejka") — the old
  "Queue (Dashboard)" was one tab that was both. On a 375 px phone the three Polish tabs take 249 px of the 343 available and do
  not wrap (below 400 px the padding of a tab is smaller).

The History fragment gets a heading of its own when it arrives as the tab (`historyHeading`, set by `historyFragment`; the
standalone page has its own h1), using the existing `history.title`, so the DJ sees at once what has appeared.

**The history is one timeline** (stage 2): `PlayHistoryService` merges the guests' requests that played or were rejected
(`song_requests`, ordered by `COALESCE(played_at, requested_at)` — a rejected request and one played before V6 are placed by
when they were requested) with the tracks the player took from the background playlist (the **play log**, `fallback_play`,
by `played_at` — Section 4.1), newest event first. Each side is read with its own bounded query (`limit + 1`, one party,
one page — the `n` newest of the union are among the `n` newest of each side) and merged in Java; one entry more than asked
for tells "Show more" whether older ones exist. A background track counts as played when the player *takes* it (that is when
its log row is written), so a track that was handed out but never sounded (the answer arrived after the DJ had picked
something by hand, or the video would not play) is in the history too. The rows are `HistoryEntry` records: a background row has a
"🎶 Playlist" badge in the vibe column (and a 🎶 before the title, because a phone hides that column), a link to the video,
"—" instead of the energy and no comment; a track without a stored title reads `youtu.be/<id>`. The "Time" column is the
time of the event (played / rejected), not of the request, and sorts by it. **The filter buttons are applied by the
server** (`HistoryFilter`, `?filter=all|guest|background|played|rejected`; `PlayHistoryService.getHistory(partyCode, limit,
filter)`): only the tables the filter needs are read — Guests: the requests (played and rejected), Playlist: the tracks,
Played: the requests that played and the tracks, Rejected: the rejected requests — each with its own bound, so the limit
counts entries **of the chosen kind**: with a 120-track playlist between the guests' songs, "Guests" still shows the last 50
guests' requests, which a filter applied to the 50 rows on the page could not (those hold a handful of them). The Played
filter includes the background tracks.

**The history of the playlist does not start over when the playlist loops** (V7, owner's decision 2026-09-29). Until then
the background rows were read from `fallback_track` (status PLAYED, by `played_at`), and when the last queued track is handed
out `requeuePlayedTracks` puts the played ones back in the queue and clears their `played_at`
(`FallbackTrackCommandService.startNewRound`) — so the history of the playlist was at most one round (≤ 500 tracks): a short
playlist that looped during a party lost its older rows, ⏮ / ⏭ had nothing to walk along right after a new round began, and
the track that was handed out *at* the boundary — re-queued in the same transaction — was in the history nowhere. Now every
hand-out is also written to `fallback_play` (`takeNextTrack`, inside the party's advisory lock and the same transaction as the
claim), the history and `recent-tracks` read that log, and rounds do not touch it. What the log keeps, and for how long: Section 4.1
(30 days from the fetch of the data, removed with the account; **replacing or clearing the playlist does not clear it**).
- **Keys** (`HistoryEntry.key()`, and `nowPlayingKey` in `youtube-autopilot.js`): `B:<id of the log row>`. `next-track` answers with
  that same id for a background track (`FallbackTrackCommandService.takeNextTrack` returns the log row), so the key the player
  keeps for what it plays is the key of that very entry in `recent-tracks`, and the same video playing in two rounds is two
  entries with two keys. The obvious alternative — the log row remembers the queue's track id and the key stays `B:<track id>` —
  is **wrong**: a playlist A B C that has looped gives `[A, C, B, A]` newest first, with the same key on both A's; ⏮ from the older A
  finds the newer one (`findIndex` takes the first match), goes to C and round again, never reaching "nothing older", and ⏭
  skips entries. That was reproduced in the browser with the old-style keys (below) and is why the id of the play is what the
  client is told. The script itself needed no change (`'B:' + track.id` already did the right thing once the id is the play's).
  A dashboard window that was open across the deployment and plays a track handed out before it still holds an old-style key: ⏮ from
  that one track may go to the wrong entry once (it finds no key, or a colliding one); the next track is fine.
- **Existing data:** `V7` copies the tracks that are `PLAYED` with a `played_at` into the log, ordered by play time, so the history of
  the current round survives the deploy; earlier rounds were lost by the old design and are not recoverable.
- **Verification:** Section 5.4, "Testing", and `SESSION_HANDOFF.md`.

A Spotify party has no background tracks, so its history is just its guests' songs, by time played. Not built: an
expression index for the ordering (`party_code, COALESCE(played_at, requested_at)`) — the query sorts one party's played
and rejected rows, which is cheap next to the bound; add it if a party ever has tens of thousands of requests.

**The "up next" list in a window that did not change it** (stage 1): the list used to be refreshed only by events in its
own window (page load, its own buttons, its own player taking a track), so after the DJ shuffled or moved tracks on the
phone the computer's list stayed old until its next track, and the phone's list never noticed the computer taking
tracks. The playback was right all along (the queue is on the server); only the display was stale. Now the lease answer
carries `queueVersion` (`FallbackQueueService.getVersion`) — since 2026-10-01 (review 2.1) one aggregate query: the database
hashes the queued track ids in play order with their "moved by hand" flags and the skipped count
(`FallbackTrackRepository.queueFingerprint`, md5, over `idx_fallback_track_queue`; no row is read into the application), and the
service adds the playlist and the shuffle setting; before, every report read the whole list (≤ 500 entities, 4 queries) to hash
it — and `GET /dj/dashboard/fallback-queue` sends `X-Queue-Version`, read **before** the list (a change between the two reads
makes the window fetch once more instead of missing it). A window remembers the version of the list it shows (`setKnownQueueVersion`, called by
`refreshFallbackQueue`) and fetches the list again when a report brings another one; the window that made the change
already holds the new version, so nothing is fetched twice. The version does not survive a restart (one extra fetch).

**Long lists** (stage 1): the active queue and the history no longer stretch the page. Each is a heading with a count, a
search box and a list of fixed height (`.list-scroll`, 60 % of the viewport) with its own scrollbar and a sticky header;
the scroll box is the wrapper, outside the polled `<tbody>`, so a poll (every 3 s) keeps the scroll position, and the
search is applied again after each refresh (`applyListFilters`, called from the poll). The search ignores case and
accents ("zolc" finds "Żółć"); while searching the count reads "3 / 60" (the search works on the rows that are loaded).
The history also has five **filter buttons** — All / Guests / Playlist / Played / Rejected (the server filters, above) — and
**"Show more"**: `GET /dj/history-view[/fragment]?limit=&filter=` (50 at first, +50 each time, at most 300 — the query
is always bounded, and reads one row more than asked for to know whether older ones exist; the count shows "50+" while
they do; a filter or a limit that is not understood is the default, a limit that is not a number is a 400). A click on a
filter button asks for the list again: in the dashboard's History tab `reloadHistory(limit, filter)` replaces the fragment
in place (the button lights at once and goes back if the request fails; only the newest answer is used; the search text
stays, the scroll position stays for "Show more" and goes to the top for another filter), on the standalone history page it
is a page load (`/dj/history-view?filter=`). "Show more" carries the chosen filter. Opening the History tab always starts at
All. The cost of a long party: nothing grows with the number of songs a party has had — every read is bounded by the limit
(≤ 301 rows a side), one row of the fragment is ≈ 1.8 KB (HTML, not compressed: ≤ 300 rows ≈ 0.5 MB, only when the DJ opens the
tab or asks for more — the history is not polled) and the browser holds at most 300 rows. `data-list*` attributes
tie a list to its search box, filter buttons, count and "nothing matches" row (`initListTools` in `dashboard.js`, delegated
listeners, so an AJAX-loaded list needs no set-up). Rows are `table-sm` — a little more compact on a phone.

**DJ actions on the fallback playlist**
- *Save:* AJAX `POST /dj/dashboard/fallback-playlist` → the server saves the URL and imports the tracks (best
  effort; response headers `X-Fallback-Id`, `X-Fallback-Import: ok|failed`, `X-Fallback-Tracks` /
  `X-Fallback-Import-Reason`) → `updateFallbackSource()` drops a running *background* track and Auto-Pilot starts
  from the new playlist. When there is nothing to play (import failed, playlist set before server-side import
  existed, tracks close to the 30-day limit), `next-track` imports lazily; after a failed attempt it does not try
  again for 5 min per party + playlist. **The dashboard says how the import went** (built 2026-09-30; the headers were sent all
  along and ignored, so a private or wrong playlist ended in an empty "up next" list without a word, and the Save button flashed
  green whatever happened): `showFallbackImportResult` in `dashboard.js` writes a line under the form (`#fallbackImportStatus`) —
  in green "Playlist saved. Tracks in the queue: N." for `ok`, in red the reason for `failed` (`NO_API_KEY`: the server has no
  YouTube API key, so only a link to a single video works; `INVALID_PLAYLIST`; `API_ERROR`: private / unknown playlist or the
  quota; `NO_PLAYABLE_TRACKS`; anything else: a general sentence) — and the Save button flashes ✓ green or ✗ red. Every failure
  sentence ends with what it means: the link *is* saved, but nothing will play from the playlist for now. The texts travel in
  `data-text-*` attributes of the box (the script has no message bundle), PL and EN. The line stays until the next Save or Stop;
  clearing the playlist (Stop, or Save with an empty field) hides it. Scenario `import-result`.
- *Stop:* `dashboard.js` posts an empty URL; only after the server has cleared it does `stopFallback()` stop a
  running background track (a playing guest song keeps playing).
- *Shuffle:* `POST /dj/dashboard/fallback-shuffle` toggles the flag **and re-orders the tracks still queued**, so the
  "up next" list changes at once: switched **on** → a fresh random order; switched **off** → playlist order
  *continuing after the last track played* (music carries on from where it is instead of jumping back to the start;
  tracks already played in this round do not come back). The track that is playing is not affected. The order
  caption above the list ("Random order" / "Playlist order") always says which one is active; once tracks were moved by hand it says so ("..., changed by hand") and
  switching shuffle first asks for confirmation, because the new order replaces those moves.

**"Up next" panel** (Phase 3, step 1): in the YouTube Player card, to the right of the video (on a narrow screen it
wraps below it), a list of **all** background tracks still queued in this round (up to 500) with their titles, the
first one marked "Next", and how many are left; the list has a fixed height (17rem, about the height of the 640 px
video) and its own scrollbar, and keeps its scroll position when it is refreshed. A guest song still plays before
them (the hint under the heading says so — and, since 2026-09-30, a line under it says **how many guest songs wait**: "Czeka N
piosenek gości — zagrają jako pierwsze", hidden when none does; `updateGuestsWaiting` in `dashboard.js` counts the rows of the
queue table — the polled `#song-list`, accepted songs — that have a YouTube video ID, i.e. the ones Auto-Pilot can play, by the same
`?v=<11 characters>` rule as `extractVideoId` in `youtube-autopilot.js` and `YouTubeUrls.extractVideoId`; a search link is for the
DJ to play by hand and is not counted. It follows the 3 s poll, needs no endpoint, and picks the plural form in the browser —
`Intl.PluralRules` of `data-lang`, four texts in `data-text-one|few|many|other`, Polish has three forms; scenario `guests-waiting`).
It is `GET /dj/dashboard/fallback-queue`, an HTML fragment that `dashboard.js`
(`refreshFallbackQueue`) drops into `#fallbackQueue` on page load, after the playlist is saved or cleared, after the
shuffle switch, and — from `youtube-autopilot.js` — each time the player takes a background track. Only the answer
of the newest request is shown, and an error or a redirect to the login page leaves the list as it is.

**Moving tracks** (Phase 3, step 2): every row has three small buttons — ⇑ *play next* (in front of everything),
↑ / ↓ *one place*; they are disabled where they cannot do anything (the first row cannot go up, the last cannot go down).
A click is `POST /dj/dashboard/fallback-queue/move` (`trackId`, `direction`), then the list is refreshed, the moved
track is scrolled into view and flashes green for a moment, and clicks are ignored while a move is in flight. If the
player has just taken that track the server answers 409 and the refreshed list shows what really is queued. The
moves apply to the **current round** only: a new order — an import, the shuffle switch, the start of the next round —
replaces them, and the list says "changed by hand" for as long as they exist.

**Skipping a track** (built 2026-09-30; owner's decision: **for this round only**, not for good): every row has a fourth button, ✕
(`data-skip`, title "Skip this round — the track comes back when the playlist starts over"), apart from the three moves and enabled
on every row, the first and the last too. A click is `POST /dj/dashboard/fallback-queue/skip` (`trackId`; 204 done, 409 the track can
no longer be skipped — the player took it a moment ago, or it is not of this party's current playlist), then the list is refreshed —
also after a 409 — and, like the moves, further clicks are ignored while a change is in flight; a press on it never starts a drag.
On the server `FallbackTrackCommandService.skipTrack` (under the party's queue lock, like every change of the queue) flips the
`QUEUED` track to the new status `SKIPPED` (`V8`, Section 10) with one conditional UPDATE, so the track is not queued — the list, the
count "N left in this round" and the order skip it — and does not play in this round; it writes **nothing to the play log** (it was
never handed out). The next round re-queues it together with the tracks that played (`requeuePlayedTracks` takes `PLAYED` and
`SKIPPED`). The caption shows "Skipped this round: M" as a part of its own beside the count while there are some (no " · " joins them
since 2026-09-30 — see "The caption wraps" below; `FallbackQueueView.skipped`, part of the
version hash, so the other windows refresh their list). If the skip empties the queue (the skipped track was the last one) the round
is over at once: the next one starts on the spot, as when the player takes the last track — so there is always a "next" — and the
skipped track comes back at its end, not at its front (`keepOutOfFirstPlace`; a shuffled round does that by itself). A one-track
playlist simply starts its round over — so **for a single video** (the DJ pasted a video link, not a playlist: the playlist id starts
with `V:`, `FallbackQueueView.singleVideo`) the ✕ is **disabled** and its tooltip says "This is the only track, so there is nothing to
skip to. Stop removes it" (the owner's remark, 2026-09-30: with one video ✕ did nothing visible, just as the move buttons are disabled
for a single track); a real playlist with one track left in the round can still skip it (the next round starts, and that is visible).
**A row wraps** (2026-09-30, the owner's phone screenshot): with the badge "Następny" and the fifth button the title of a row was squeezed
to three letters a line on a 375 px screen; now the title keeps at least 10rem (`flex: 1 1 10rem`) and the badge, the ↗ link and the
buttons form one group (`ms-auto`) that drops under the title when the row is too narrow — looked at in the built-in browser at 375 and
1280 px with long real-looking titles (the sample `fallback-queue.html` of `DashboardPageRenderTest` has them now): on the phone the title
has the whole line and the buttons sit under it on the right, on a wide screen a short title and its buttons stay on one line.
**The caption wraps** (2026-09-30, the owner's phone screenshot: "te teksty są osobno, ale bardzo blisko siebie"): the row above the list holds the order
text ("Kolejność z playlisty") and the counts ("Zostało w tej rundzie: 80", "Pominięte w tej rundzie: 3"); on a 375 px screen they touched (0 px
between them, both squeezed to two lines, and the number of a count could be left alone on a line). The row is now `flex-wrap` with a gap
(`column-gap-3 row-gap-1`), the counts are a group of their own whose two parts do not break inside (`text-nowrap`, `column-gap-3`): side by side
on a wide screen (order left, counts right), on a phone the order text first and the counts under it — three lines at 320 and 375 px, two at
414 px (the counts share the second), one from 768 px. Measured in the built-in browser at those widths, on the real rendered fragment (the
counts' `column-gap-3` was measured on that render with one class patched by hand); the fragment test
`shouldLetTheCaptionWrap_soThatItsPartsNeverTouch` pins the markup.
Checked on a real PostgreSQL (throw-away database `s2p_skip`): V1–V8 on an empty database with
Hibernate validation; **V7 → V8 on existing data** (one migration executed, rows and statuses kept, `SKIPPED` refused before and
accepted after, an unknown status still refused); the skip in the middle of a round (the track leaves, the round goes on without it, the
next round has all of them, the play log has no row for a skip); the last queued track skipped in playlist order (with the skipped
track first in playlist order) and shuffled (40 times, never first); a one-track playlist; another party's, an unknown, a wrong-playlist
and an already-skipped track refused; a track skipped from a replaced playlist, and from one that was imported again, is never
revived; and **159 skips, 360 hand-outs, moves, drops, shuffle flips and the purge from three parties at the same time — no error, no
deadlock, no track lost or duplicated, one log row per hand-out, always a next track** (the whole class, and the purge tests of Section 4.1,
were run three times on fresh databases). Scenario `skip-track` (the real list fragment).

**Dragging** (Phase 3, step 2, added after the first review): a row can also be dragged to any place — press on it and
drag with the mouse, or press and hold about 0.4 s on a touch screen and then drag (a finger that moves earlier is
scrolling). The row itself moves through the list while the pointer passes the middle of the other rows, the list
scrolls when the pointer is near its top or bottom edge, Esc (or a cancelled touch) puts the row back, and dropping it
where it started sends nothing. On drop, `POST /dj/dashboard/fallback-queue/place` tells the server which track now
follows the dragged one (`trackId`, `beforeTrackId`; no `beforeTrackId` = the end of the queue). While a row is being
dragged the list is not refreshed (a refresh asked for meanwhile is done afterwards). The title is plain text so that a
press on it starts the drag; the link to the YouTube video is the small ↗ beside the buttons, and pressing a button or
that link never starts a drag. The buttons stay for those who prefer them (and for the keyboard).

**Timing:** ENDED → next track PLAYING took 230–250 ms with the server stubbed (2026-09-29) — that is YouTube's
own load time (each track is a fresh `loadVideoById`). No pre-fetching was built (Section 14).

Supported fallback URL formats — parsed **server-side only** (`YouTubeUrls.extractPlaylistId`; the browser just
reads the result from the `X-Fallback-Id` header to show/hide the Stop button):
- `https://youtube.com/playlist?list=PLxxx` → playlist ID
- `https://youtube.com/watch?v=abc&list=PLxxx` → playlist ID (prefers `list=`)
- `https://youtube.com/watch?v=KD5fLb-WgBU` → single video
- `https://youtu.be/KD5fLb-WgBU?si=...` → single video
- Raw playlist ID (`PLxxx`) or raw 11-char video ID → resolved automatically

A single video is stored as one `fallback_track` row (`playlist_id` = `V:<videoId>`; no API call and no API key are
needed to play it — only its title is looked up, best-effort); when it has been played the playlist loops, so it
simply repeats.

**Testing — the browser tests are in the repo** (`src/test/browser/`, since 2026-09-30; before that the same kind of harness was
built in a scratch directory three times and thrown away). They run the REAL `youtube-autopilot.js` and `dashboard.js` on the REAL
rendered `dashboard.html` in a headless Chrome, with a fake `YT.Player` and a stand-in server; `python src/test/browser/run.py`
runs all of them (or `run.py NAME`), and `src/test/browser/README.md` says how it works, how to write a scenario and how to record the
fixture again. Its parts:
- **The page.** `DashboardPageRenderTest` (an ordinary unit test) calls the real `DjDashboardController.dashboard()` with mocked
  services and renders the model it builds with the real Thymeleaf templates and message bundles, in Polish and English, and writes
  `target/browser-harness/dashboard.html` (Auto-Pilot on, a playlist saved, two songs in the queue), `dashboard-manual.html` (a
  brand-new party: Auto-Pilot off, no playlist), `dashboard-en.html` (the first party in English), `fallback-queue.html` (the "up next"
  fragment: four tracks, one skipped) and `history-<filter>.html` / `history-<filter>-more.html` (the History tab's fragment for each
  of the five filters, the first page and the longer list that "Show more" asks for — through the real `historyFragment` of the
  controller, from ten sample entries; the real `HistoryFilter` says which entries a filter includes). It also checks what the scripts
  depend on (the ids of the buttons, the party code, the CSRF meta tags, the order of the scripts, the queue's search box, the tab bar,
  no unresolved `??key??`) and that `<html lang>` is the language of the bundle that wrote the texts.
- **The stand-in server** (`server.py`, Python standard library only) serves those pages with the real `static/js` and `static/css`
  and answers the endpoints the scripts call; what it answers is told by the scenario (`POST /__config`: the lease answer, the
  commands waiting, the tracks `next-track` hands out, `recent-tracks`, the queue rows of the poll, the answer of Save, delays) or
  **replayed from a fixture of real answers** (`fixtures/play-log-boundary.json`: the JSON bodies that the real controllers,
  services and queries gave — ownership check, lease check, play log and Jackson all real — for a 3-track playlist that loops, ten
  hand-outs; recorded by `PlayLogFixtureRecorderTest`, which needs a throw-away PostgreSQL `s2p_*` database and does nothing unless
  `S2P_FIXTURE_OUT` is set — it is the one `@SpringBootTest` in the repo and it refuses any other database).
- **The fake player** (`fake-yt.js`) replaces the IFrame API (the real script never loads): it logs every call and a scenario controls
  the position, the end of a track, a *held* load (UNSTARTED / CUED until released), the clock (`Date.now()` moved by hand, so no
  scenario waits for real seconds) and a blocked API.
- **Scenarios** (`scenarios/*.js`) run inside the page (`?scenario=NAME`), click the real buttons and POST their verdict to the
  stand-in, which writes a file; `run.py` copies the repo (without `target/`, `.git`, `.idea` — never `mvnw` in the repo, the app runs
  from it) to a work directory, runs `DashboardPageRenderTest` there, starts the stand-in, opens each scenario in a fresh headless
  Chrome and reads the verdict. No new dependency, no Node; Bootstrap from its CDN is **not needed** (no script uses its API — only
  its CSS, so the run blocks it: the tests do not depend on the network).
- **What is covered today** (42 scenarios — five of them, `autopilot-sync.js`, the Auto-Pilot setting followed by every window,
  a takeover with Auto-Pilot switched on elsewhere and with it off everywhere, and what the track does at its end; the last eight — the language of the page, the lease, the lists, the History tab and the tabs —
  are listed after the first nineteen, and two of the review's first package, `recovery.js`, come on top: Auto-Pilot asks again after a
  failed `next-track` and after six player errors in a row, with the queue poll answering 304; and eight of `resume.js`: a reload brings back
  the last track, ⏮ from it, a takeover brings it back too — also when a WATCH answer comes before the TAKE_OVER's —, the old window
  cannot play its YouTube player after losing the lease, and the cases without a resume — too long ago (after a reload and after a
  takeover), loaded with Auto-Pilot off): ⏮ / ⏭ across the round boundary of a looping playlist with the real answers — and its
  **control**, the same walk with the keys of before the play log (the queue's track id), which must fail and does (⏮ goes round in
  circles, ⏭ skips entries); the 20 s window of the second ⏮; the pause button and the lease reports while a track loads; ⏭ after a
  playlist change (saved here, cleared, changed in another window, and the two cases where it must still retrace); the import result;
  the guest songs waiting line; the skip button; the two back buttons of a window that does not play (by role, the commands they send,
  what the window that plays does with them, restart of a paused / ended track); and — as facts, not as fixes — the three silent states of a window (Auto-Pilot off,
  IFrame API blocked, player lease held elsewhere; Section 14 says why). The scenarios of the three small fixes were run against
  the unfixed scripts first and failed for the expected reasons (the 10 s window, "Wznów" while loading, ⏭ retracing after a playlist
  change); `import-result` fails with the feature disconnected and `retrace-survives-a-change-in-flight` fails with the
  `trackLoadedAtLeaseSeq` condition left out (both tried); the scenarios of the guest songs line and the skip button were written
  together with the feature and not tried without it.
- **The eight scenarios of the sixth session** (2026-09-30: what had **no automated test** until then — the lease, the lists, the
  History tab, the tabs — and the language of the page). None of them could be seen red against an unfixed script (the code worked),
  so each was **mutation-checked**: one line of the script taken out in a copy, and the scenario had to go red for the right step —
  17 mutations, all killed (the first version of one, "no `scroll` listener", survived because Chrome's `scrollend` runs the same
  function; a mutation that really stops the lit tab from following was killed). `page-language` / `page-language-en`: `<html lang>` is
  `pl` / `en` and the same as the language of the texts and of the plural rules (`page-language` was red before `dashboard.html` was
  fixed: it said `en` over Polish texts). `lease-lost-and-taken-back`: another window takes the lease → this one stops its player and
  shows the banner, from then on reports `WATCH` and asks for no track; "play here" asks first, in the banner's words (a "no" sends
  nothing), a "yes" sends one `TAKE_OVER` and the window plays again. `lease-free-takeover-asks-nothing`: with nobody playing "play here"
  asks nothing. `lease-released-on-leaving`: `pagehide` of the window that plays sends the release beacon (party, the window's own id,
  the CSRF token in the body), `pagehide` of a window that does not play sends nothing. `queue-list`: the search finds "Żółć" by
  "zolc", the count says "shown / all", the "nothing matches" row, and after a poll the search, the scroll position and the column sort
  are all still there. `history-tab`: the tab loads the real fragment in place of the queue with a heading; the search works on the
  loaded rows; "Show more" asks for the limit its button carries — with the chosen filter — and keeps the search text; a filter button
  lights at once, asks the server, and the search text survives; the sort handlers work on rows that arrived by AJAX; a failed request
  puts the previous button back. `tabs`: a click lights the tab at once and brings the list under the bar (the smooth scroll — the
  scenario checks that the browser does not ask for reduced motion, so it is that path that runs), History loads once and a second
  click does not load it again, Panel scrolls to the top and leaves the list that shows, Queue hides the history, and the lit tab
  follows the scroll (the page is made taller first, or a short page could not scroll as far as a tab asks).
- **The lease is scripted here, not simulated:** who holds it is a config of the stand-in, so "another window took over" is a change
  of that config. The rules of the server (who gets the lease, the 10 s timeout) are unit-tested; these scenarios check what the
  *window* does with its answers. Two real windows are not run.
- **What they do not cover:** the real YouTube player (sound, autoplay policy, the real events between two videos — the fake
  imitates what the script relies on), the real Spring Security chain (the `_csrf` of a `sendBeacon`), two real devices, layout as the
  eye sees it (the `tabs` scenario measures positions and scrolling, no scenario looks at how a row or a button looks — the phone
  layout of the "up next" rows was looked at by hand, Section 5.4 "Skipping a track"), and the guest side. Real devices are still the
  owner's part.
- **In CI:** `.github/workflows/browser-tests.yml` runs `python src/test/browser/run.py --no-sandbox` on `ubuntu-latest` for every push
  to `dev` / `main`, every pull request and by hand (Java 21, Python 3.12, the runner's own Chrome; the verdicts of a failed run are
  kept as an artifact). `run.py` starts `mvnw` with `sh` on Linux (the wrapper is committed without the executable bit) and stops
  waiting for a browser that has exited. **Its first run on GitHub (2026-09-30, the push of `914f88a`) was green: all 27 scenarios, 4 min
  25 s of the 4 min 36 s of the job** — on the runner's Chrome, with `--no-sandbox`, English locale, UTC. Its annotations: 2 warnings — `checkout@v4`,
  `setup-java@v4` and `setup-python@v5` target Node 20, which is deprecated (they were forced to Node 24), and `setup-java@v4` itself is
  deprecated (use v5 or newer) — and 1 notice: `ubuntu-latest` becomes Ubuntu 26 from 2026-10-19. **Answered afterwards (not yet run on
  GitHub):** both workflows use the newest majors — `checkout@v7`, `setup-java@v6`, `setup-python@v7`, `upload-artifact@v7`, whose
  `action.yml` says `node24` and which still have the inputs the workflows use (looked up at the source, not from memory) — and run on the
  pinned `ubuntu-24.04`, the image the green run was on, so that the move to Ubuntu 26 is made on purpose (change the label, run by hand).
  `.github/workflows/unit-tests.yml` is the second workflow: `sh mvnw -B -ntp test '-Dtest=!Scan2playApplicationTests'` on the same
  triggers (the unit tests need no database and no secrets); a failed run keeps `target/surefire-reports/`. **Written after the first
  green run, as agreed with the owner; not run on GitHub yet.** Before it was written the 430 tests were run here with
  `JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US -Duser.timezone=UTC` (the machine is Polish, CET) and with the exact command under
  a POSIX shell: no test depends on the language or the time zone of the machine.
- **How the earlier stages were verified** (stage 0 to the play log, all with throw-away versions of the same harness): the
  lease — two browser tabs as two windows against a stand-in with the rules of `PlayerLeaseService` (first window plays, the second is
  refused and silent, takeover with confirmation, the old window stops, release on leaving, reload keeps the role, a 14 s outage
  changes nothing, a `409` silences the window, a playlist replaced or cleared in another window stops the old track within one
  report, a late answer does not stop the new track); ⏭ and ⏮ from the playing window and from the other one; the up-next list of both
  windows following the server; the lists, search, filters and "Show more"; the play log across a round boundary. They were run by
  hand in the built-in browser and are now the scenarios above (the lease takeover, the lists, the History tab and the tabs since the
  sixth session). Gotchas of the real player: it needs a real click first (user activation) or
  it stays on the play button, its methods exist only after `onReady`, it pauses itself after a few seconds unless muted, and many
  well-known videos have embedding disabled (error 150) — `M7lc1UVf-VE` and `aqz-KE-bpKQ` play fine.

---

## 6. File Inventory

### 6.1 Controllers

| Class                       | Mapping                 | Purpose |
|-----------------------------|-------------------------|---------|
| `HomeController`            | `GET /`                 | Landing page or redirect to dashboard if authenticated |
| `DjDashboardController`     | `/dj/dashboard`, `/dj/history-view` | DJ dashboard view, AJAX polling updates (ETag), history view/fragment (`limit`: 50 at first, up to 300, "Show more"; `filter`: all / guest / background / played / rejected); `extractPlaylistId()` resolves YouTube URLs to playlist/video IDs |
| `DjPartySettingsController` | `/dj/**`                | Start/end party, vibe, rate limits (bounded since 2026-10-01, review 5.4: requests 1–100, cooldown 1–1440 min, duplicate window 0–50), playback mode, fallback playlist (a link over 500 characters is refused like a Mix) and shuffle (a shuffle switch re-orders the queue), account deletion |
| `CspReportController`       | `POST /csp-report`      | The browsers' Content-Security-Policy reports (public, no CSRF): each distinct violation logged once an hour as `CSP violation: …` (Section 8) |
| `DjFallbackQueueController` | `/dj/dashboard/fallback-queue`, `POST .../move`, `POST .../place`, `POST .../skip` | The DJ's "up next" list of the fallback playlist as an HTML fragment (`fragments/fallback-queue.html`), and the DJ's changes to it (a track up / down / play next, dragged to a place, or skipped for this round) |
| `DjPlayerLeaseController`   | `POST /dj/dashboard/player-lease`, `POST .../release`, `POST /dj/dashboard/player-command`, `GET /dj/dashboard/recent-tracks` | Which dashboard window plays (Section 5.4, "One window plays"): a window reports in and learns whether it is the holder (and gets the current playlist, the version of the "up next" list and a waiting command); the holder gives the lease up when it leaves the page; any window can give the one that plays a command (⏭ Next, ⏮ Back, ⏯ pause / resume); the tracks that played recently, for ⏮ |
| `DjSongController`          | `/dj/**`                | Song queue actions: mark as played, push to Spotify, DJ picks |
| `DjSessionHelper`           | —                       | Shared component: resolves party settings from HTTP session + **validates partyCode ownership** (IDOR protection) |
| `FeedbackController`        | `POST /dj/feedback`     | REST endpoint for DJ bug reports / feature ideas |
| `GuestController`           | `/p/**`                 | Guest song request form and async submission |
| `LegalController`           | `/privacy`, `/terms`    | Legal pages (locale-aware: EN + PL) required for YouTube API compliance and Google OAuth verification |
| `SpotifyAuthController`     | `/dj/spotify/**`        | Spotify playback token exchange (authenticated, ownership-validated) |
| `ViewAttributes`            | —                       | Constants for Thymeleaf model attribute names |

### 6.2 Services

| Class                        | Lines | Purpose |
|------------------------------|-------|---------|
| `DjService`                  | 201   | Song queue queries (dashboard, public), queue fingerprint, and song actions (mark played, push to Spotify, DJ picks; a request becomes "played" only through `markPlayed`, which sets the decision and `playedAt`, and is also used by `SongEvaluationService` for the Spotify auto-queue) — all song actions validate partyCode ownership |
| `SongEvaluationService`      | 280   | AI-powered song evaluation pipeline: Gemini AI → track resolution → save → optional auto-queue; includes **song name normalization** (temperature 0.0 for deterministic output) |
| `PartySettingsCommandService`| ~100  | Create/update party settings via generic `updateSettings()` lambda (write side; evicts `partySettings` after the commit) |
| `PartySettingsQueryService`  | ~45   | Read party settings (read side, the `partySettings` cache; every caller gets its own copy) |
| `QueueService`               | 75    | Delegates to MusicProvider implementations (resolve track, add to queue) |
| `SpotifyMusicProvider`       | 194   | Spotify integration: search tracks (Client Credentials), add to queue (User Auth) |
| `YouTubeMusicProvider`       | ~220  | YouTube integration: Data API v3 with two-level cache (Caffeine L1 + PostgreSQL L2, 30-day TTL per YouTube API ToS) + daily scheduled cleanup; asks `YouTubeSearchBudget` before every API search (spent → the search link, not cached) |
| `SpotifyAuthService`         | 188   | Spotify OAuth2 token management (exchange, refresh, store) — null-safe refresh with explicit exception |
| `GuestSessionService`        | ~60   | The guest's own limit in the session (the DJ's `requestLimit` per `cooldownMinutes`): `tryAcquire` checks and records in one step under the session mutex, before the evaluation (Section 5.2) |
| `GuestRequestLimiter`        | ~130  | Server-side guest limits that need no cookie: client IP + party (30 / 10 min) and party (300 / 24 h), counted atomically before the evaluation; `clientIp` reads `guest.client-ip-header` or the remote address (Section 5.2) |
| `YouTubeSearchBudget`        | ~85   | The daily fuse of the shared `search.list` limit: 80 API searches per Google day (midnight Pacific), tripped at once by a 403 `quotaExceeded` (Section 7.3) |
| `QrCodeService`              | 50    | QR code generation (ZXing, `@Cacheable`) |
| `AccountDeletionService`     | 70    | Deletes all DJ data (songs, fallback tracks and their play log, feedback, settings) — required by Google API data deletion policy |
| `SongRequestRetentionService`| ~60   | The nightly purge (04:45) of the guests' song requests 30 days after `requested_at` (Section 4.1): batches of 1000 (`SongRequestRepository.deleteRequestedBefore`, each its own transaction), at most 200 batches a night |
| `YouTubePlaylistClient`      | ~190  | Reads a playlist via YouTube Data API (`playlistItems.list` + `videos.list`): max 500 items, drops private/deleted/non-embeddable videos, returns each video's title too (same `videos.list` call); `findTitle` for a single video (best-effort); API key never appears in errors |
| `FallbackPlaylistService`    | ~65   | Syncs the party's server-side fallback tracks with the DJ's playlist (playlist / single video / cleared), in the DJ's shuffle setting. API first, DB only after a complete non-empty result; `applyShuffleSetting` re-orders the queue without any API call |
| `FallbackTrackCommandService`| ~385  | Transactional writes for `fallback_track` and the play log `fallback_play`: replace (soft-invalidate QUEUED → CANCELLED, insert new in playlist or shuffled order), cancel, daily 30-day purge (of the tracks and of the play log rows fetched before the cutoff), `applyShuffleSetting` (on: fresh random order; off: playlist order continuing after the last played track), `moveTrack` (the DJ's up / down / play next), `placeTrack` (a drag: in front of another track or to the end; a moved track is flagged `manualMove`), `skipTrack` (the DJ's ✕: QUEUED → SKIPPED for this round, nothing in the play log; when it was the last queued track the next round starts at once, with the skipped track at its end) and `takeNextTrack` — takes the queued track with the lowest `playOrder`, claims it QUEUED → PLAYED with one conditional UPDATE (concurrent callers never get the same track), writes the hand-out to the play log in the same transaction and returns that log row (its id is what `next-track` answers with — the key `B:<id>` of the history), and when that was the last one starts the next round at once (re-queues the party's newest import in playlist order or freshly shuffled; a shuffled round never opens with the track that is still playing); every method that changes the queue first takes a per-party PostgreSQL advisory lock — a stress test with concurrent moves and takes deadlocked without it |
| `FallbackQueueService`       | ~55   | Read side for the dashboard: the queued tracks of the round (up to 500) in exactly the order `takeNextTrack` serves them, plus how many are left and whether the DJ has moved tracks by hand; `moveTrack` / `placeTrack` / `skipTrack` resolve the party's current playlist (a skip also the shuffle setting) and delegate; the view says how many tracks were skipped in this round; `getVersion` / `versionOf` — a hash of the whole view, so that a window can tell that the list changed elsewhere |
| `NextTrackService`           | 120   | "What plays next?": a waiting guest song first, else a background track. Imports lazily when there is nothing to play or the tracks are ≥ 29 days old; per party+playlist single-flight and a 5-minute pause after a failed import |
| `PlayHistoryService`         | ~110  | The timeline of what played (Section 5.4, "The history is one timeline"): guest requests (`song_requests`, by play time) and background tracks (the play log, `fallback_play`) merged newest first, each side read with its own bounded query; `getHistory(partyCode, limit, HistoryFilter)` → `Page(entries, hasMore)` for the history page (the filter decides which tables are read), `getRecentlyPlayed` → what the embedded player can play again (has a YouTube video ID) for ⏮ |
| `PlayerLeaseService`         | ~140  | Which dashboard window plays: one in-memory lease per party (a window id + the time it last reported, 10 s timeout), `report` (`CLAIM` / `WATCH` / `TAKE_OVER`; the holder also collects the command waiting for it and says whether its player makes sound, which every answer tells back), `sendCommand` (⏭ Next / ⏮ Back / ⏯ pause and resume from any window; refused when nobody plays; one command per party, the last one wins, dropped when the lease changes hands), `mayPlay` (used by `next-track`, answers 409 to another window) and `release`; takes a `Clock` in a package-private constructor so that tests move time by hand |

### 6.3 Configuration

| Class              | Lines | Purpose |
|--------------------|-------|---------|
| `SecurityConfig`   | 55    | Spring Security: OAuth2 login (Spotify + Google), public vs protected routes, logout |
| `AppConfig`        | 66    | `@EnableCaching`, `@EnableScheduling`, `@EnableAsync`, Caffeine CacheManager (per-cache TTL), `RestClient` bean with connect/read timeouts |
| `GeminiConfig`     | 40    | Google Gemini `Client` bean + `ObjectMapper` bean |
| `OAuth2DebugConfig`| 42    | Startup diagnostics: logs registered OAuth2 clients (**`@Profile("dev")` only**) |

### 6.4 Integration

| Class/Interface        | Purpose |
|------------------------|---------|
| `MusicProvider`        | Interface: `getType()`, `findTrackUrl()`, `addToQueue()` |
| `SpotifyApiConstants`  | Constants for Spotify OAuth2 parameters, grant types, scopes, JSON keys |

### 6.5 Templates (Thymeleaf)

| Template               | Purpose |
|------------------------|---------|
| `landing.html`         | Public landing page — two provider cards (Spotify / YouTube) + "How It Works" guide |
| `dashboard.html`       | DJ control panel: queue, settings, QR code, playback controls, DJ Pick form, YouTube player |
| `history.html`         | DJ history view: played and rejected songs; the `historyTableContent` fragment (count, five filter buttons — All / Guests / Playlist / Played / Rejected —, search, list of fixed height, "Show more") serves both this page and the dashboard's History tab |
| `qr-print.html`        | The QR code to print: an A4 poster or eight table cards (`qrLayout`), white, `@page A4`; the printed part in Polish and English at once; `css/qr-print.css`, `js/qr-print.js` (the Print button — no inline handler) |
| `index.html`           | Guest song request form |
| `result.html`          | Guest view: AI decision result |
| `party_ended.html`     | Guest view when party is inactive |
| `error.html`           | Generic error page (403, 404, 500) |
| `privacy.html`         | Privacy Policy (English) |
| `privacy_pl.html`      | Privacy Policy (Polish) |
| `terms.html`           | Terms of Service (English) |
| `terms_pl.html`        | Terms of Service (Polish) |
| `fragments/components.html` | Shared fragments: DJ navigation (the account buttons and the sticky bar of three tabs Panel / Queue / History), scroll restore script (skipped when the URL has a hash), feedback modal + toast + JS |
| `fragments/fallback-queue.html` | "Up next" list of the fallback playlist (titles, order caption, "Next" badge); rendered by `DjFallbackQueueController` into `#fallbackQueue` on the dashboard |
| `fragments/player-controls.html` | The ⏮ Back, ⏯ pause / resume and ⏭ Next buttons under the video of the YouTube Player card (their labels and the "sent" text travel in `data-*` attributes; the pause button carries both of its labels and follows the state of the music) — and the two hidden buttons ⏮ Wstecz / ↺ Od początku that `youtube-autopilot.js` shows *instead of* the single ⏮ in a window that does not play |
| `fragments/player-lease-banner.html` | The "playback runs on another device" banner of the YouTube Player card — hidden until `youtube-autopilot.js` learns from the server that another window holds the player lease; its texts travel in `data-*` attributes |

### 6.6 Static Assets

| File                    | Purpose |
|-------------------------|---------|
| `css/app.css`           | Shared stylesheet with design tokens, page-scoped rules (`.page-dj`, `.page-guest`, etc.), `.list-scroll` (a long list in a box of fixed height with a sticky header), `.dj-tabbar` (the tab bar that stays in view) |
| `js/dashboard/*.js`     | **The dashboard's script as ES modules** (since 2026-10-01, review 3.3; `<script type="module" src="/js/dashboard/main.js">`, no bundler — it replaced the 1284-line `dashboard.js`): `main.js` imports `list-tools.js` (sorting, search, filters, "Show more"; the standalone history page loads it alone), `fallback-queue.js` (the "up next" list, moves, drag, skip, Stop, shuffle, the import result), `forms.js` (AJAX forms, the Auto-Pilot switch, "copy" of the party link), `tabs.js` (Panel / Queue / History) and `polling.js` (the guest queue every 3 s, the guest limits). They and the player talk only through the events of **`events.js`** (`s2p:playback-mode`, `s2p:playback-mode-reported`, `s2p:guest-queue-updated`, `s2p:fallback-playlist-saved` / `-cleared`, `s2p:fallback-queue-stale`, `s2p:fallback-queue-version`) — before, about 15 functions were put on `window` and called after a `typeof … === 'function'`. The templates have no `onclick` / `onchange` calling a global any more. Everything the old file did, unchanged: Dashboard core: AJAX form interceptor (preserves YT player), the tabs (`initTabs`: Panel / Queue / History, the lit tab follows the scroll, the history loaded by AJAX with YouTube), table polling (3s, ETag/304), clipboard, client-side table sorting, search of the long lists and the history's filter buttons and "Show more" (`initListTools`; the buttons and "Show more" ask the server again: `reloadHistory`) |
| `js/youtube-autopilot.js` | YouTube Auto-Pilot — **an ES module since 2026-10-01**, loaded after `js/dashboard/main.js`; its only global is `window.onYouTubeIframeAPIReady` — a "dumb player" (Section 14, stage 4): **the track in the player is one object, `current`** (since 2026-10-01, review 3.2: kind GUEST / BACKGROUND / HISTORY / MANUAL, guest song id, timeline key, playlist, lease seq at load, load number, start time, whether ⏭ retraces, phase LOADING → RUNNING → OVER), created only by `startTrack` — before, ten variables were set by hand in six places; when the player is idle / on `ENDED` / after a player error it asks `POST /dj/dashboard/next-track` and `loadVideoById()`s the answer; confirms guest songs via `/dj/dashboard/play`; never touches a paused or playing track; asks only while its window holds the player lease (`POST /dj/dashboard/player-lease` every 3 s), otherwise shows the banner |
| `js/scroll-restore.js`  | The scroll memory of the DJ pages (`fragments/components.html`, `scroll-restore-script`, in `<head>`): hides the page until the saved scroll position is restored. Was an inline script until 2026-10-01 (CSP, review 5.1) |
| `js/dj-nav.js`          | Loaded by the `dj-nav` fragment (dashboard and history): `form[data-confirm]` asks before it is sent (end the party, log out, delete the account — a `submit` listener in the capture phase, so it runs before `forms.js`), and the feedback form. Were inline `onsubmit` attributes and an inline script until 2026-10-01 |
| `js/guest-party.js`     | The guest's party page (`index.html`): the song / mood switch (it focuses the field only with a mouse — on a phone the keyboard made the page jump), the list under the form fetched again when the guest comes back, the "sending…" button (its text in `data-text-submitting`). Was an inline script until 2026-10-01 |
| `js/song-autocomplete.js` | Song autocomplete / typeahead via public iTunes Search API (client-side, debounced at 300ms, no server involvement, no YouTube quota) |
| `js/qr-print.js` / `css/qr-print.css` | The print page of the QR code: the Print button (`window.print()`); A4 layout of the poster and the 2 × 4 cards, the bar hidden in print |
| `js/wake-lock.js`       | Screen Wake Lock: keeps the display on while Auto-Pilot is on (it listens to the `s2p:playback-mode` event of the dashboard's modules — a classic script, by the event's name; the lock is re-acquired on `visibilitychange`; silent no-op stubs where the API is unavailable or the context is not secure). From the owner's commit `33eef2a` of 2026-04-06 |

### 6.7 Resources

| File                        | Purpose |
|-----------------------------|---------|
| `application.properties`    | All config: DB, OAuth2, Spotify, YouTube, Gemini, server settings |
| `messages.properties`       | English i18n messages |
| `messages_pl.properties`    | Polish i18n messages |
| `prompts/prompt-template_en.txt`       | Gemini AI prompt template (English) |
| `prompts/prompt-template_pl.txt`       | Gemini AI prompt template (Polish) |
| `prompts/prompt-duplicate-rule_en.txt` | Gemini AI duplicate detection rule (English) |
| `prompts/prompt-duplicate-rule_pl.txt` | Gemini AI duplicate detection rule (Polish) |
| `prompts/prompt-normalize.txt`         | Gemini AI song name normalization prompt (language-agnostic, temperature 0.0) |

### 6.8 Browser tests (`src/test/browser/`, outside `src/test/java`, so Maven does not touch it)

Described in Section 5.4, "Testing"; its own `README.md` is the guide.

| File | Purpose |
|------|---------|
| `README.md` | How it works, how to run it, how to write a scenario, how to record the fixture again, what is not covered |
| `run.py` | The runner: copies the repo (without `target/`, `.git`, `.idea`) to a work directory, runs `DashboardPageRenderTest` there, starts the stand-in, opens every scenario in a headless Chrome, prints the verdicts (exit 1 if one fails) |
| `server.py` | The stand-in server (Python standard library only): the real rendered pages, the real `static/js` and `static/css`, the endpoints the scripts call (including the History tab's fragment, one rendered file per filter), configurable by a scenario or replaying a fixture |
| `fake-yt.js` | The fake YouTube IFrame API (logs calls; holds a load in UNSTARTED / CUED; moves the clock; blocks the API; gates the first lease report until the scenario has configured the stand-in) |
| `harness.js` | The scenario runner inside the page: `S2P.scenario({...})`, the helper `t` (`press`, `step`, `waitFor`, `stand.config`, `reportAfterConfig`, ...), the verdict, uncaught errors |
| `scenarios/*.js` | The scenarios (Section 5.4, "Testing", lists what they cover): `back-and-restart`, `boundary`, `double-press`, `guests-waiting`, `import-result`, `next-after-playlist-change`, `pause-while-loading`, `silent-states`, `skip-track`, and — the sixth session — `page-language`, `lease` (three scenarios), `lists` (`queue-list`, `history-tab`), `tabs`; — the seventh — `recovery` (`recover-after-failed-ask`, `recover-after-player-errors`), `resume` (eight scenarios: the resume after a reload and a takeover, and the old window after a takeover), `autopilot-sync` (five: the Auto-Pilot setting across windows) |
| `fixtures/play-log-boundary.json` | The real answers of `next-track` and `recent-tracks` for a looping 3-track playlist, recorded by `PlayLogFixtureRecorderTest` |

The Java side, in `src/test/java`: `template/DashboardPageRenderTest` (renders the pages the scenarios run on: the dashboard in Polish and
English, the brand-new party, the "up next" fragment and the History fragments) and `controller/PlayLogFixtureRecorderTest` (records the
fixture; skipped unless `S2P_FIXTURE_OUT` is set). The CI workflow that runs it all: `.github/workflows/browser-tests.yml`.

---

## 7. External API Integrations

### 7.1 Google Gemini AI

- **Purpose:** Evaluate song requests (accept/reject based on vibe match) + normalize DJ pick song names
- **Model:** `gemini-2.5-flash` (pinned — do NOT use `*-latest` aliases; env `GOOGLE_AI_MODEL` overrides it). Until 2026-09-30
  `gemini-2.5-flash-lite`, which did not recognise well-known Polish songs from a line of their lyrics even with the improved prompt
  (the owner's tries). A guest's request may think up to `google.ai.thinking-budget` tokens (1024, env `GOOGLE_AI_THINKING_BUDGET`;
  0 = off, -1 = the model decides) — paid as output and added to the wait, within the 10 s timeout; the DJ's pick (normalization)
  never thinks. Cost: a fraction of a cent per request (about $0.5 per 1000 without thinking, a few dollars per 1000 at the full budget)
- **SDK:** `google-genai` Java SDK
- **Response format:** JSON (`DjResponse` record)
- **Temperature:** Default for evaluations (creative DJ comments), **0.0 for normalization** (deterministic)
- **Fallback:** If AI fails → request is auto-rejected with "AI offline" message; normalization falls back to raw input. An answer
  without `songName` keeps the guest's text as the name (Section 5.2)
- **Timeout:** 10 s per call (`GeminiConfig.TIMEOUT_MS`, `HttpOptions.timeout`); a call that takes longer fails like any other
  (before 2026-09-30 there was none, and a hanging call held one of the few threads that evaluate guests' requests)
- **Prompt language:** Locale-aware (English + Polish). Prompt is selected based on guest's browser locale via `LocaleContextHolder`; unsupported locales fall back to English.
- **Comment length:** AI instructed to keep comments under 300 characters; entity truncates at 500 as safety net
- **Duplicate detection:** Configurable window — recent N songs are injected into the prompt
- **An unknown song is not rejected (2026-10-01):** "Shakira & Burna Boy – Dai Dai" (released May 2026, after the model's knowledge)
  was rejected as "it does not exist", while "shakira dai dai" passed — the old rule "the song MUST exist, never invent
  collaborations" made the model reject what it did not know. Point 2 of the song prompt now says: never invent songs, but not
  knowing one is no reason to reject it (the search checks it); an unknown song keeps the guest's name in `songName` and its vibe is
  judged by the artist and the genre.
- **A line of lyrics is searched by the guest's words (2026-09-30):** `gemini-2.5-flash` named the same line of a well-known
  Polish song with a different made-up artist and title on each try, and YouTube then found another song. The AI's answer now says
  what the request is — `requestKind`: `title` / `artist` / `lyrics` / `mood` (`DjResponse`; null in an answer without it) — and at a
  YouTube party a `lyrics` request is searched by what the guest typed (`SongEvaluationService.searchQueryFor`; YouTube's search
  matches lyrics well), everything else by the AI's name; Spotify's search does not match lyrics, so a Spotify party keeps the AI's
  name. The prompt asks for `lyrics` even when the AI thinks it knows the song, and for no title or artist in the comment of an
  accepted request (the found video may differ from its guess). The AI still decides whether the request fits the party.
- **The name shown is the video's, not the AI's (2026-09-30):** the AI named a line of lyrics with a wrong artist and a made-up
  title, and YouTube's search still found the right song — the lists said one thing and the player played another. For a YouTube
  party, once a request (or a DJ pick) resolves to a video, `SongEvaluationService.nameOfTrack` takes the video's own title
  (`YouTubePlaylistClient.findTitle`, one `videos.list` call, 1 unit from the general pool), cleans it of the upload's tags
  (`YouTubeUrls.cleanVideoTitle`: "(Official Video)", "[HD]", "(Teledysk)"…; "(Da Ba Dee)" or "(Remix)" stay) and stores it as
  `song_name` — the guest's result page, the DJ's queue, the history and the duplicate check all use it. The order of artist and
  title is the uploader's. Without a key, a video or a title the AI's name stays; Spotify parties are unchanged. The AI's comment
  may still mention its own name.
- **Lyrics as a request (2026-09-30):** a guest typed a line of a well-known Polish song and the model said it knew no such song and
  picked another one. Point 1 of `prompt-template_{pl,en}.txt` now says a request may be a fragment of the lyrics, that the song it
  comes from is picked (not replaced), a mood only when it is neither title, artist nor lyrics, and an honest word in the comment when a
  quote is not recognised. The lite model still picked a wrong song with it (the owner's try), so the model is now
  `gemini-2.5-flash` (above).

### 7.2 Spotify Web API

Two separate authentication flows:

1. **Login OAuth2** (Spring Security) — identifies the DJ
   - Scopes: `user-modify-playback-state`, `user-read-playback-state`, `user-read-private`
   - Provider config in `application.properties`

2. **Playback OAuth2** (Custom `SpotifyAuthService`) — controls DJ's Spotify player
   - Same scopes but separate token stored in `PartySettingsEntity`
   - Token auto-refresh when expired (5-minute buffer)
   - Auto-Pilot of a Spotify party and the DJ's "push to Spotify" mark a song as played only once Spotify has taken it
     (`whenComplete` on `QueueService.addToQueue`); when that fails the song stays in the queue (Auto-Pilot adds a note to its
     comment). Until 2026-09-30 a failed auto-queue was marked played too (`exceptionally` followed by `thenAccept`).

3. **Client Credentials** (Application-level) — search tracks without user context
   - Used by `SpotifyMusicProvider.findTrackUrl()`
   - Thread-safe cached token with double-checked locking via `ReentrantLock`

### 7.3 YouTube Data API v3 (Two-Level Cache)

- **Purpose:** Resolve song names to playable YouTube video URLs
- **Implementation:** `YouTubeMusicProvider` uses `RestClient` to call YouTube Search API
- **API key:** Configured via `youtube.api-key` property; the search sends it in the `X-goog-api-key` header, not in the URL
  (an I/O error puts the whole URL into the logged exception message)
- **Quota optimization — two-level cache:** YouTube video IDs are permanent, so each unique song costs API quota only **once per 30 days**:
  - **L1 — Caffeine (in-memory, 24h TTL):** prevents repeated DB queries during a session
  - **L2 — PostgreSQL (`youtube_cache` table, 30-day TTL):** survives restarts, compliant with YouTube API ToS (max 30-day retention)
  - **L3 — YouTube Data API (100 quota/search):** only called on L1+L2 miss or L2 entry expired
- **ToS compliance:** Expired entries (>30 days) are refreshed on next access and cleaned up daily at 04:00 via `@Scheduled` task
- **Fallback:** If API key is missing or search fails → returns YouTube search results URL (manual play only, Auto-Pilot won't work with search URLs)
- **Auto-Pilot:** Playback runs in the browser via the YouTube IFrame Player API (`youtube-autopilot.js`); the "what plays next" decision (guest song or background track) is server-side (Section 14)
- **Quota (per Google's "Quota costs" page, checked 2026-09-28):** `search.list` has its **own** default limit of 100
  calls/day; every other endpoint shares **10,000 units/day**. `playlistItems.list` and `videos.list` cost 1 unit per call.
  (The older wording "100 units per search" gives the same ~100 unique searches/day.) Verify your project's actual
  quota in Google Cloud Console → APIs & Services → YouTube Data API v3 → Quotas.
- **Search fuse (`YouTubeSearchBudget`, REVIEW.md 4.1):** the `search.list` limit is one per Google project, shared by every
  party. The provider counts its real API searches per Google day (midnight to midnight `America/Los_Angeles`) and past
  `youtube.search.daily-budget` (80, env `YOUTUBE_SEARCH_DAILY_BUDGET`; 0 = off) stops asking: new songs get the search link
  (the DJ can play it by hand, Auto-Pilot skips it — the owner's choice). A 403 `quotaExceeded` trips it at once. **The count is in
  the database since 2026-10-01** (`youtube_search_budget`, `V10`, one row per Google day; a search is taken with one
  `INSERT … ON CONFLICT DO UPDATE … WHERE used < budget RETURNING used`, so a restart does not give the day's budget back and
  concurrent requests or instances never take more; rows older than 30 days are deleted). Whether the day is spent is mirrored in
  memory, because the DJ's dashboard asks on every poll. Search links are not put into the `youtubeSearch` cache, so a
  song gets its video once the API can be asked again. **Raise the budget together with the quota** when Google grants more.
- **A search that found nothing is remembered for 10 minutes** (`YouTubeMusicProvider.notFound`, in memory, by normalised query;
  review 4.5): asked again it would cost another search for the same empty answer. A failed call (network, 5xx) is not remembered.
- **A YouTube Mix is refused (2026-09-30):** a link with `list=RD…` (a Mix YouTube makes up for one viewer, e.g. opened from a video)
  is not given out by the Data API. `POST /dj/dashboard/fallback-playlist` refuses it before saving anything (`YouTubeUrls.isMix`;
  `RDCLAK…` YouTube Music lists are left to the import): `X-Fallback-Saved: false`, `X-Fallback-Import: failed`,
  `X-Fallback-Import-Reason: YOUTUBE_MIX`; the dashboard shows `dashboard.fallback.import.youtubemix` and leaves the party's playlist,
  the track that plays and the Stop button alone. Before, the link was saved, the import failed with the general API error and the
  server kept retrying it.
- **Playlist import (`YouTubePlaylistClient`, Phase 2):** when the DJ sets a fallback playlist the backend reads it once —
  at most 500 items = ≤ 10 `playlistItems` + ≤ 10 `videos` calls (≈ 20 units from the general pool, none from `search.list`);
  the video titles shown in the DJ's "up next" list come from the same `videos.list` calls (`part=status,snippet`, no extra
  quota) and are kept under the same 30-day rule. Requires `youtube.api-key`; a single video needs no API call to be
  played (its title is looked up with one best-effort `videos.list` call — without a key it is simply stored untitled). Failures (`NO_API_KEY`, `INVALID_PLAYLIST`, `API_ERROR`,
  `NO_PLAYABLE_TRACKS`) are thrown as `FallbackImportException` **before** any DB write, so existing tracks stay intact.
  The API key is part of the request URL, so exceptions are scrubbed of it and carry no cause.

---

## 8. Security Model

| Route                | Access          |
|----------------------|-----------------|
| `/`                  | Public          |
| `/p/**`              | Public (guests) |
| `/privacy`, `/terms` | Public (legal)  |
| `/oauth2/**`         | Public          |
| `/login/**`          | Public          |
| `/css/**`, `/js/**`  | Public          |
| `POST /csp-report`   | Public, no CSRF (browsers' CSP reports) |
| `/dj/**`             | Authenticated   |
| Everything else      | Authenticated   |

- OAuth2 login via Spotify or Google
- Custom login page: `/` (landing page)
- Successful login redirects to `/dj/dashboard`
- Logout: `POST /dj/logout` → clears session → redirects to `/`
- CSRF enabled (tokens in `<meta>` tags for AJAX, available on dashboard and history pages)
- **IDOR protection:** All DJ endpoints that accept `partyCode` validate ownership via `DjSessionHelper.validateOwnership()` (compares request partyCode against session-cached partyCode)
- **Spotify playback OAuth2** moved to `/dj/spotify/**` (authenticated, ownership-validated)
- **Content-Security-Policy** (2026-10-01, review 5.1; `SecurityConfig.CONTENT_SECURITY_POLICY`): scripts only from the app,
  `cdn.jsdelivr.net` (Bootstrap) and YouTube (`www.youtube.com`, `s.ytimg.com`) — no `'unsafe-inline'`: no page has an inline script
  or an `on…=` handler (`DashboardPageRenderTest.assertNothingInline`, also for the guest page); styles also inline (the templates
  have `style="…"` attributes); `connect-src` the app and `itunes.apple.com`; frames only YouTube; `object-src 'none'`,
  `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; `report-uri /csp-report`. **Sent as
  `Content-Security-Policy-Report-Only`** while `security.csp.enforce=false` (env `CSP_ENFORCE`): browsers report what it would
  block, the log says `CSP violation: …`. Switch it on once real use (a party on YouTube, a Spotify party, the guest's page, the DJ's
  login) leaves the log quiet.

---

## 9. Caching Strategy

Uses **Caffeine** cache with per-cache TTL configuration.

| Cache Name       | Key        | TTL    | Max Size | Usage |
|------------------|------------|--------|----------|-------|
| `partySettings`  | partyCode  | 24h    | 500      | `PartySettingsQueryService.getSettings()` — every caller gets a **copy** of the cached entity (review 1.2: a `setX` on it changed what every request saw); `PartySettingsCommandService.updateSettings()` evicts the entry after its commit. The Spotify tokens are not in the entity's `toString` |
| `qr-codes`       | text+size  | 24h    | 1000     | `QrCodeService.generateQrCodeBase64()` (`@Cacheable`) |
| `youtubeSearch`  | searchQuery| 24h    | 1000     | `YouTubeMusicProvider.findTrackUrl()` L1 cache — backed by permanent `youtube_cache` DB table (L2) |
| `dashboardQueue` | partyCode  | 3s     | 200      | `DjService.getDashboardQueue()` (`@Cacheable`) — auto-expires for polling freshness; evicted after a song is confirmed played (`markSongAsPlayed`, `pushToSpotify`), because next-track reads it |

Additional caching: DJ's `partyCode` is cached in `HttpSession` to avoid repeated `ownerId` → DB lookups.

Account deletion (`AccountDeletionService`) evicts the party's code from `partySettings` and `dashboardQueue` (and, until the guest
page stopped using it on 2026-09-30, `publicQueue`)
after its transaction commits — before 2026-09-30 the deleted party (with its Spotify tokens) stayed in `partySettings` for up
to 24 h and guests could still send requests to it.

---

## 10. Production Configuration

### Environment Variables Required

| Variable               | Purpose                          |
|------------------------|----------------------------------|
| `SPOTIFY_CLIENT_ID`    | Spotify app client ID            |
| `SPOTIFY_CLIENT_SECRET`| Spotify app client secret        |
| `GOOGLE_CLIENT_ID`     | Google OAuth2 client ID          |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret      |
| `GOOGLE_AI_API_KEY`    | Google Gemini API key            |
| `YOUTUBE_API_KEY`      | YouTube Data API v3 key (optional — without it songs fall back to search URLs, and the fallback playlist cannot be imported server-side). **The variable name must have no trailing characters** (a stray `:` in the IDE run configuration silently disables it) |
| `DB_PASSWORD`          | PostgreSQL database password     |
| `SCAN2PLAY_GUEST_URL`  | Optional. Overrides `scan2play.guest-url` (default `https://www.scan2play.com.pl/`) — the base URL encoded in the dashboard QR code and "Party Link". Set it to the machine's LAN IP (`http://<lan-ip>:8080/`) to test the guest flow from a phone locally; `localhost` is not reachable from a phone. |

**Logging in to the DJ dashboard from another device (a phone).** The login redirect is built from the address the browser
used (`{baseUrl}`), and Google accepts only registered redirect URIs that are HTTPS (localhost exempt), not a raw IP
address (localhost exempt) and on a public suffix — so a LAN address such as `http://192.168.x.x:8080` can never be
registered and DJ login does not work through it. The guest side (`/p/<code>`, QR code) needs no login and works through
the LAN IP with `SCAN2PLAY_GUEST_URL`. For the DJ dashboard on a phone use `localhost` forwarded over USB (Chrome remote
debugging port forwarding) or a public HTTPS address such as a tunnel on your own domain; see SESSION_HANDOFF.md.

### Key Application Properties

| Property                         | Value                                    |
|----------------------------------|------------------------------------------|
| `server.port`                    | 8080                                     |
| `scan2play.guest-url`            | `https://www.scan2play.com.pl/`          |
| `google.ai.model-name`           | `gemini-2.5-flash-lite`                  |
| `spring.jpa.hibernate.ddl-auto`  | `validate`                               |
| `server.forward-headers-strategy`| `FRAMEWORK` (for reverse proxy)          |
| `youtube.search.daily-budget`    | `80` (env `YOUTUBE_SEARCH_DAILY_BUDGET`; Section 7.3) |
| `guest.limit.per-ip-party` / `guest.limit.per-ip-window-minutes` | `30` / `10` (env `GUEST_LIMIT_PER_IP_PARTY`, `GUEST_LIMIT_PER_IP_WINDOW_MINUTES`; Section 5.2) |
| `guest.limit.per-party-daily`    | `300` (env `GUEST_LIMIT_PER_PARTY_DAILY`; Section 5.2) |
| `guest.client-ip-header`         | empty (env `GUEST_CLIENT_IP_HEADER`; `CF-Connecting-IP` behind Cloudflare; Section 5.2) |
| `security.csp.enforce`           | `false` = the CSP is only reported (env `CSP_ENFORCE`; Section 8) |
| `server.shutdown`                | `graceful` (30s timeout)                 |
| `server.tomcat.max-http-form-post-size` | `10KB`                             |
| `spring.datasource.hikari.maximum-pool-size` | `15`                          |
| `spring.datasource.hikari.minimum-idle` | `5`                                |

### Database migrations (Flyway)

The schema is owned by Flyway, not Hibernate (`ddl-auto=validate` only checks that entities match the
schema). Every schema change is a new file `src/main/resources/db/migration/V<n>__<what>.sql`;
Flyway applies pending files in order at startup, before Hibernate validates, and records them in the
`flyway_schema_history` table.

- **Never edit an applied migration** — add the next version instead. Adding a column/table to an entity
  now also means adding its migration in the same change, otherwise startup fails validation.
- Migrations so far: `V1__baseline` (schema as of 2026-09-28, taken from a dump of the working database),
  `V2__create_fallback_track` (Phase 2 tracks), `V3__default_request_limits` (data fix: parties created while
  `@Builder` ignored the field defaults have `request_limit`/`cooldown_minutes` = 0, which switches guest rate
  limiting off; sets them to 2 / 3 where < 1 — `duplicate_check_window` is left alone because 0 is valid there),
  `V4__fallback_track_order_and_title` (`fallback_track.play_order` — backfilled with a random order for parties
  with shuffle on, playlist order otherwise — and `title`), `V5__fallback_track_manual_move` (`manual_move` flag:
  which queued tracks the DJ has moved by hand), `V6__song_request_played_at` (`song_requests.played_at`, nullable, no
  back-fill: requests played before V6 keep NULL and the history orders them by `requested_at`; verified against a real
  PostgreSQL 18 — V1–V6 on an empty database with Hibernate validation, and V5 → V6 on data that already existed),
  `V7__fallback_play_log` (the table `fallback_play` — one row per background track handed out: `video_id` and `title` as a
  snapshot, `fetched_at` copied from the track for the 30-day retention, `played_at`, an identity `id` that is the key of the
  play — and the index `(party_code, played_at DESC, id DESC)`; the migration also copies the tracks that are `PLAYED` with a
  `played_at` at that moment, ordered by play time, and nothing else; verified against a real PostgreSQL 18 — V1–V7 on an empty
  database with Hibernate validation, **V6 → V7 on data that already existed** through the Flyway API (exactly one migration
  executed; a `PLAYED` row without a time, `QUEUED` and `CANCELLED` rows are not copied; `fallback_track` untouched), the
  history reads, the boundary of a round, the retention purge and the account deletion, and 480 hand-outs from three parties
  together with the DJ's moves, drops and shuffle flips and the nightly purge — three runs on fresh databases, no deadlock, one log
  row per hand-out), `V8__fallback_track_skipped` (the status `SKIPPED` — the DJ skips a track for the current round: only the
  check constraint `fallback_track_status_check` of `V2` is dropped and created again with the fourth value, no data changes;
  verified against a real PostgreSQL 18 — V1–V8 on an empty database with Hibernate validation, and **V7 → V8 on data that already
  existed** through the Flyway API: exactly one migration executed, the rows and their statuses kept, `SKIPPED` refused at V7 and
  accepted after, an unknown status still refused — and the skips themselves, see Section 5.4 "Skipping a track"),
  `V9__queue_indexes` (2026-10-01, review 1.5: `idx_fallback_track_queue` and `idx_fallback_track_party_fetched` created,
  `idx_fallback_track_party_status`, `idx_owner_id` — a duplicate of the UNIQUE on `owner_id` — and `idx_party_code` — the leading
  column of `idx_party_decision_time` — dropped, `IF EXISTS`), `V10__youtube_search_budget` (2026-10-01: the table of the daily
  YouTube search count, Section 7.3; no entity maps it). From V9 on, migrations are checked by `MigrationIT` (`mvnw verify -Pit`,
  Section 13 "Testing") on an empty PostgreSQL 18, locally and in `.github/workflows/db-tests.yml`.
- **`spring.flyway.baseline-on-migrate=true`**: a database that already has tables but no history table
  (every database created before Flyway, including production) is recorded as version 1 *without running
  V1*, and only V2+ are applied. An empty database gets V1 applied in full. Both paths were verified
  against a real PostgreSQL 18 (empty DB: V1 creates a schema identical to the working DB; existing DB:
  baselined, app starts, Hibernate validation passes).
- **First production deploy checklist:** (1) take a database backup, (2) dump the production schema
  (`pg_dump --schema-only --no-owner`) and compare it with `V1__baseline.sql` — they must describe the same
  tables/columns (e.g. `party_settings.fallback_*` columns added by hand), otherwise Hibernate's
  validation will refuse to start; (3) deploy — Flyway creates `flyway_schema_history` and baselines it.

### Production Hardening (applied 2026-04-05)

- **IDOR protection** on all DJ endpoints (`DjSessionHelper.validateOwnership()`)
- **`@EnableAsync`** added — `@Async` in `QueueService` was previously a no-op (ran synchronously)
- **Database password externalized** to `${DB_PASSWORD}` env variable (was hardcoded)
- **`ddl-auto` changed to `validate`** — Hibernate no longer auto-modifies schema (Flyway migrations were adopted on 2026-09-28, see "Database migrations" above)
- **Graceful shutdown** enabled (30s timeout for in-flight Callable requests)
- **HikariCP pool** configured (15 max, 5 idle, 5s connect timeout)
- **RestClient timeouts** (5s connect, 10s read) — prevents hung threads on external API calls
- **Request size limit** (10KB) — prevents abuse on form POST endpoints
- **Spotify OAuth2 endpoints** moved from public `/spotify/**` to authenticated `/dj/spotify/**`
- **`OAuth2DebugConfig`** gated behind `@Profile("dev")` — no client IDs in production logs
- **SpotifyAuthService** null-safe token refresh — throws explicit exception instead of propagating `null`
- **FeedbackEntity** added index on `ownerId` for efficient `deleteByOwnerId()` (account deletion)

---

## 11. Class Dependency Graph (Simplified)

```
HomeController
    └── (no dependencies)

DjDashboardController
    ├── DjService
    ├── PartySettingsQueryService
    ├── QrCodeService
    └── DjSessionHelper

DjPartySettingsController
    ├── PartySettingsCommandService
    ├── AccountDeletionService
    └── DjSessionHelper

DjSongController
    ├── DjService
    └── DjSessionHelper

DjSessionHelper
    ├── PartySettingsQueryService
    └── PartySettingsCommandService

FeedbackController
    ├── FeedbackRepository
    └── DjSessionHelper

GuestController
    ├── DjService
    ├── SongEvaluationService
    ├── PartySettingsQueryService
    ├── GuestSessionService
    ├── GuestRequestLimiter
    └── MessageSource

LegalController
    └── (no dependencies)

SpotifyAuthController
    ├── SpotifyAuthService
    └── DjSessionHelper

DjService
    ├── SongRequestRepository
    ├── PartySettingsQueryService
    ├── QueueService
    └── SongEvaluationService

SongEvaluationService
    ├── Client (Gemini)
    ├── ObjectMapper
    ├── SongRequestRepository
    ├── PartySettingsQueryService
    ├── QueueService
    ├── MessageSource
    └── PlatformTransactionManager

QueueService
    ├── SpotifyMusicProvider
    └── YouTubeMusicProvider ──→ YouTubeSearchBudget

SpotifyMusicProvider
    └── SpotifyAuthService

SpotifyAuthService
    ├── PartySettingsQueryService
    ├── PartySettingsCommandService
    ├── RestClient
    └── ObjectMapper

AccountDeletionService
    ├── PartySettingsRepository
    ├── SongRequestRepository
    ├── FallbackTrackRepository
    ├── FallbackPlayRepository
    └── FeedbackRepository

PartySettingsCommandService
    └── PartySettingsRepository

PartySettingsQueryService
    └── PartySettingsRepository
```

---

## 12. HTTP Endpoints Summary

### Public (No Auth)

| Method | Path                        | Handler                          |
|--------|-----------------------------|----------------------------------|
| GET    | `/`                         | `HomeController.home()`          |
| GET    | `/p/{partyCode}`            | `GuestController.partyIndex()`   |
| GET    | `/p/{partyCode}/queue`      | `GuestController.partyQueue()` — the guest's list alone (Section 5.2) |
| POST   | `/p/{partyCode}/request`    | `GuestController.requestSong()`  |
| GET    | `/privacy`                  | `LegalController.privacyPolicy()` |
| GET    | `/terms`                    | `LegalController.termsOfService()` |
| POST   | `/csp-report`               | `CspReportController.report()` — a browser's CSP report (Section 8) |

### Authenticated (DJ Only)

| Method | Path                              | Handler                                          | Notes |
|--------|-----------------------------------|--------------------------------------------------|-------|
| GET    | `/dj/dashboard`                   | `DjDashboardController.dashboard()`              |       |
| GET    | `/dj/history-view`                | `DjDashboardController.historyView()`            | Optional `limit` (default 50, raised to 50 at least, capped at 300; not a number → 400) and `filter` (`all` \| `guest` \| `background` \| `played` \| `rejected`; missing or unknown → `all`): the last `limit` entries of that kind from the timeline of what played or was rejected — guests' songs and background tracks (`HistoryEntry`), newest event first; the model also has `historyFilter` (the lit button), `historyHasMore` and `historyNextLimit` for "Show more" |
| GET    | `/dj/qr-print`                    | `DjDashboardController.qrPrint()`                | The party's QR code to print (2026-10-01, the owner's wish): `layout=poster` (default; one A4 poster) or `cards` (eight cards 93 × 68 mm to cut out). The QR code is 1000 px (`QR_PRINT_SIZE`), the printed text is Polish **and** English (written in the template, each part with its own `lang`), the bar above it (not printed) follows the DJ's language. Linked from the QR card of the dashboard ("🖨", a new tab) |
| GET    | `/dj/history-view/fragment`       | `DjDashboardController.historyFragment()`        | AJAX partial HTML, ownership-validated; the same `limit` and `filter` |
| GET    | `/dj/dashboard/updates`           | `DjDashboardController.getDashboardUpdates()`    | AJAX partial HTML (polling, ETag/304), ownership-validated; every answer carries `X-Guest-Limits` and `X-Guest-Limits-Use` (Section 5.2) |
| POST   | `/dj/dashboard/next-track`        | `DjDashboardController.nextTrack()`              | JSON `{source: GUEST\|BACKGROUND, id, videoId, playlistId}` (`playlistId` = the playlist a BACKGROUND track came from, null for a guest song; `id` = the request id of a guest song, or the id of the play log row — table `fallback_play` — that this hand-out wrote for a BACKGROUND track, the same id `recent-tracks` uses in its `B:<id>` key) or 204, ownership-validated. **Not read-only**: a background track is marked `PLAYED` and written to the play log as it is handed out (a guest song is still confirmed via `/dj/dashboard/play`), so ask only when a track is about to be loaded. Optional `deviceId` (the asking window's id): **409** when another window holds the party's player lease (see below) — nothing is handed out; a request without an id counts as another window while a lease is live. Section 14 Phase 2 stage 3; called by `youtube-autopilot.js` since stage 4 |
| POST   | `/dj/dashboard/player-lease`      | `DjPlayerLeaseController.report()`               | JSON `{holder, free, fallbackPlaylistId, queueVersion, command, playing, playbackMode}` (`playbackMode` is the party's Auto-Pilot setting, which every window follows; `playing` is whether the player of the window that plays makes sound — true / false, null when nobody plays or it has not said; `fallbackPlaylistId` is the party's current fallback playlist, so the window that plays can stop a track of a playlist the DJ has replaced or cleared elsewhere; `queueVersion` changes whenever the "up next" list would look different, so every window can tell that it was changed in another one; `command` is `NEXT` when the DJ pressed ⏭ in another window — only ever for the holder, handed out once), ownership-validated. Params `partyCode`, `deviceId` (random id of the window, `[A-Za-z0-9_-]{8,64}`), `mode` = `CLAIM` / `WATCH` / `TAKE_OVER`, optional `playing` = `true` / `false` (whether the window's own player makes sound — only the holder's is kept; anything else → 400). **Not read-only**: the report renews the window's lease (10 s timeout), `TAKE_OVER` moves it. 400 for a bad id or mode. Called by `youtube-autopilot.js` every 3 s — Section 5.4, "One window plays" |
| POST   | `/dj/dashboard/player-command`    | `DjPlayerLeaseController.sendCommand()`          | ownership-validated. Params `partyCode`, `command` = `NEXT`, `PREVIOUS`, `PREVIOUS_TRACK`, `RESTART`, `PAUSE` or `RESUME` (`PREVIOUS_TRACK` and `RESTART` are the two back buttons of a window that does not play, since 2026-09-30; `PREVIOUS` is the single ⏮ with its rules and is no longer sent by the page, only accepted). The DJ gives the window that plays a command from any window (the phone as a remote control); it is carried out when that window's next lease report brings it (≤ ~3 s); one command waits per party, the last one pressed. 204 when it is waiting, **409** when no window holds a live lease (nobody would carry it out), 400 for an unknown command — Section 5.4, "Next ⏭" and "Back ⏮" |
| GET    | `/dj/dashboard/recent-tracks`     | `DjPlayerLeaseController.recentTracks()`         | JSON list (at most 30, newest first) of `{key, source, id, videoId, title, secondsAgo}` (`secondsAgo`: since it started, by the server's clock — the resume after a reload reads it) — the tracks that played most recently and can be played again (guests' songs with a YouTube video ID, background tracks); `key` is `G:<request id>` / `B:<play id>` (`id` of a background track = the id of its play log row, one per play, so a video that played in two rounds appears twice with two keys). Read-only, ownership-validated. The window that plays walks back along it for ⏮ — Section 5.4, "Back ⏮" |
| POST   | `/dj/dashboard/player-lease/release` | `DjPlayerLeaseController.release()`           | ownership-validated. Params `partyCode`, `deviceId`. The holder gives the lease up when its page is left (`sendBeacon`, CSRF token in the body as `_csrf`); ignored when the window does not hold it. 204, or 400 for a bad id |
| POST   | `/dj/dashboard/vibe`              | `DjPartySettingsController.updateGlobalVibe()`   | ownership-validated |
| POST   | `/dj/dashboard/limits`            | `DjPartySettingsController.updateLimits()`       | ownership-validated |
| POST   | `/dj/dashboard/play`              | `DjSongController.markAsPlayed()`                | song-level ownership check |
| POST   | `/dj/dashboard/playback-mode`     | `DjPartySettingsController.togglePlaybackMode()` | ownership-validated. Sets Auto-Pilot to `mode` (`AUTO` / `MANUAL`, what the dashboard's switch sends); without `mode` it toggles |
| POST   | `/dj/dashboard/fallback-playlist` | `DjPartySettingsController.updateFallbackPlaylist()` | YouTube only, ownership-validated. Always saves the URL; also imports the playlist into `fallback_track` (best-effort) and reports it in headers: `X-Fallback-Id`, `X-Fallback-Import: ok\|failed`, `X-Fallback-Tracks: <n>` or `X-Fallback-Import-Reason: NO_API_KEY\|INVALID_PLAYLIST\|API_ERROR\|NO_PLAYABLE_TRACKS` |
| POST   | `/dj/dashboard/fallback-shuffle`  | `DjPartySettingsController.toggleFallbackShuffle()` | ownership-validated. Toggles the flag **and re-orders the queued tracks** (on: new random order; off: playlist order continuing after the last played track); new state in `X-Fallback-Shuffle`. The dashboard asks for confirmation first when the DJ has moved tracks by hand |
| GET    | `/dj/dashboard/fallback-queue`    | `DjFallbackQueueController.fallbackQueue()`      | HTML fragment (`fragments/fallback-queue.html`), read-only, ownership-validated — the DJ's "up next" list (all tracks left in this round, titles, order caption, count); response header `X-Queue-Version` = the version of the list, the same value the lease reports carry |
| POST   | `/dj/dashboard/fallback-queue/move` | `DjFallbackQueueController.moveTrack()`      | ownership-validated. Params `partyCode`, `trackId`, `direction` = `UP` / `DOWN` / `TOP` (play next). 204 when done, 409 when the track can no longer be moved (the player has taken it, or it belongs to another party or an old playlist), 400 for a bad parameter |
| POST   | `/dj/dashboard/fallback-queue/place` | `DjFallbackQueueController.placeTrack()`    | ownership-validated. Params `partyCode`, `trackId`, `beforeTrackId` (optional; missing = the end of the queue): the DJ dropped a dragged track in front of `beforeTrackId`. 204 when done, 409 when a track can no longer be moved (taken by the player, another party's or an old playlist's), 400 for a bad parameter |
| POST   | `/dj/dashboard/fallback-queue/skip` | `DjFallbackQueueController.skipTrack()`      | ownership-validated. Params `partyCode`, `trackId`: the DJ skips the track for this round (`SKIPPED`; it comes back with the next round; nothing is written to the play log). 204 when done, 409 when the track can no longer be skipped (taken by the player, another party's or an old playlist's), 400 for a bad parameter |
| POST   | `/dj/dashboard/dj-pick`           | `DjSongController.addDjPick()`                   | YouTube only, bypasses AI, ownership-validated |
| POST   | `/dj/requests/{id}/push-to-spotify`| `DjSongController.pushToSpotify()`              | song-level ownership check |
| POST   | `/dj/start-party`                 | `DjPartySettingsController.startParty()`         |       |
| POST   | `/dj/end-party`                   | `DjPartySettingsController.endParty()`           |       |
| POST   | `/dj/feedback`                    | `FeedbackController.submitFeedback()`            | REST JSON response, ownership-validated |
| POST   | `/dj/delete-account`              | `DjPartySettingsController.deleteAccount()`      | Deletes all DJ data, invalidates session |
| GET    | `/dj/spotify/login`               | `SpotifyAuthController.spotifyLogin()`           | Authenticated, ownership-validated |
| GET    | `/dj/spotify/callback`            | `SpotifyAuthController.spotifyCallback()`        | Authenticated, ownership-validated |
| POST   | `/dj/logout`                      | Spring Security logout handler                   |       |

---

## 13. Known Limitations & Technical Debt

### Architecture
- **No User entity** — DJ is identified by `ownerId` (OAuth2 provider ID) stored directly on `PartySettingsEntity`. There is no separate `User` table.
- **One party per DJ** — `ownerId` has UNIQUE constraint. A DJ cannot run multiple parties simultaneously.
- **No party cleanup** — Old parties and song requests accumulate. Consider adding a scheduled task to archive/delete parties inactive for >N days.
- **Schema changes need a Flyway migration** — the schema is managed by Flyway (Section 10, "Database migrations"). Entity changes without a matching `V<n>__*.sql` fail Hibernate validation at startup. Flyway's schema history exists only in databases that have started with this version at least once.

### Security
- Spotify tokens are stored as plain text in the database (no encryption at rest).
- The Content-Security-Policy is only reported so far (Section 8): switching it on (`CSP_ENFORCE=true`) waits for quiet reports. Inline
  styles stay allowed until the `style="…"` attributes become classes.
- The whole-project review of 2026-09-30 (`REVIEW.md` at the repo root) lists what is still open, ranked, and what has been fixed.

### Frontend
- Server-rendered (Thymeleaf) with AJAX enhancements. No SPA, no JavaScript framework.
- Dashboard live updates via `setTimeout` + `fetch` polling every 3 seconds with ETag/304 optimization. No WebSockets/SSE.
- No client-side validation on forms (server-side only).
- **`<html lang>`:** every page whose texts come from the message bundles — `dashboard`, `landing`, `index`, `history`, `result`, `error`,
  `party_ended` — declares the language of those texts with `th:lang="#{html.lang}"` (the key of each bundle says which language it is,
  so the declaration cannot disagree with the texts, also for a browser whose language has no bundle). The legal pages have their text
  written in, one file per language (`LegalController` picks it): `terms` / `privacy` say `en`, the `_pl` ones `pl`, and use no message
  key. `HtmlLangDeclarationTest` scans `templates/` so that a new page with `#{…}` keys cannot forget the declaration.

### Scalability
- **Guest rate limits are in memory** (the session, `GuestRequestLimiter`; Section 5.2): a restart starts them over, and a second
  instance would have its own. Clearing cookies resets only the guest's own limit. The YouTube search count is in the database
  (`YouTubeSearchBudget`, Section 7.3).
- **The async executor is bounded** (review 4.4, 2026-10-01): the guests' requests (`Callable`, Gemini up to 10 s) and `@Async`
  run on Boot's `applicationTaskExecutor` with 16 core / 32 max threads and a queue of 50 (`spring.task.execution.pool.*`, env
  `ASYNC_POOL_CORE_SIZE` / `ASYNC_POOL_MAX_SIZE` / `ASYNC_POOL_QUEUE_CAPACITY`); past that a request fails at once (an error page)
  instead of waiting past its 30 s. Boot's default queue was unbounded.
- **In-memory Caffeine cache** — not shared across instances. If horizontally scaled, consider Spring Session + Redis.
- **Single-instance deployment** assumed. For multi-instance, caching and session management need Redis/JDBC backing. The player lease (which dashboard window plays, Section 5.4) is in memory too and would need the same.

### Testing
- **Smoke test (7 tests)** — `SmokeTest` (`@WebMvcTest`, no DB): public routes, security redirects, YouTube IFrame not server-rendered.
- **Unit tests (424 tests, one of them skipped unless asked for — see below)** covering core business logic: entity truncation, code generation, rate limiting, queue management, IDOR blocking, provider delegation, party lifecycle, playlist URL extraction, fallback playlist import (with titles), the server-side next-track decision (`NextTrackService`), the fallback queue order (`FallbackTrackCommandService`: playlist order, shuffle, rounds, shuffle switch) and the play log it writes (a row per hand-out, under the party lock, one id per play; the purge; the account deletion), the "up next" service/controller (listing and moving tracks, the queue lock), the player lease and its commands (`PlayerLeaseService` with a clock the test moves by hand, `DjPlayerLeaseController`, the 409 of `next-track`, the version of the "up next" list), the timeline of what played (`PlayHistoryService`: the merge, play time vs request time, the bound and `hasMore`, what ⏮ can play again; `DjService.markPlayed`; `YouTubeUrls`), the `limit` of the history endpoints, `recent-tracks` and the `PREVIOUS` command, the state of the player and the `PAUSE` / `RESUME` commands (`PlayerLeaseService`, `DjPlayerLeaseController`), and the rendering of `fragments/fallback-queue.html` (with its skip button and the skipped count), `fragments/player-lease-banner.html`, `fragments/player-controls.html`, the history fragment and the queue's polled `<tbody>` with the real message bundles; skipping a track for this round (`FallbackTrackCommandService.skipTrack`, `FallbackQueueService`, `DjFallbackQueueController`), the nightly purge of the song requests (`SongRequestRetentionService`: the cutoff, the batches, the bound, the schedule) and — through `DashboardPageRenderTest` — the whole `dashboard.html`, rendered from the model of the real controller, in both languages (the texts of the import result, the ids the scripts need, `<html lang>` = the language of the bundle — also for a locale that has no bundle) and the History tab's fragment for every filter through the real controller; `HtmlLangDeclarationTest` checks that every page of `templates/` that uses `#{…}` keys declares its language.
- Unit tests are pure Mockito (no Spring context) — fast (~2s). Smoke test uses `@WebMvcTest` (~5s).
- **Tests on a real PostgreSQL (2026-10-01, review 6.1): 24 `*IT` tests**, `mvnw verify -Pit` (a Maven profile: failsafe runs the
  `*IT` classes, surefire is off; `mvnw test` stays without a database). The base class `PostgresIntegrationTest` creates a
  throw-away `s2p_it_<time>_<random>` database on the server of `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD` (the defaults of
  `application.properties`; `PGDATABASE` is ignored), starts the whole application on it with dummy credentials — Flyway V1..V10
  and Hibernate validation — and drops it when the JVM ends. `MigrationIT` (every migration applied, the indexes, the plan of the
  next-track query), `FallbackQueueIT` (the order, the rounds, a superseded playlist, shuffle, the DJ's moves, skips, the purge,
  the version of the list and that it reads no track, the one-statement import — both counted with Hibernate statistics),
  `FallbackQueueConcurrencyIT` (4 players × 35 hand-outs while 4 threads move, drag and re-order: no deadlock, every round holds
  every track once — **with the advisory lock removed it fails with "deadlock detected"**, checked), `SongRequestRepositoryIT`
  (the history's `COALESCE` order, the fingerprint, the batched purge), `YouTubeSearchBudgetIT` (the count survives a restart,
  40 concurrent takes on three instances take exactly the budget), `ApplicationSetupIT` (the bounded executor). On GitHub:
  `.github/workflows/db-tests.yml` with a `postgres:18` service (Railway's database is `postgres-ssl:18`). The ITs of 1.5, 1.4, 2.1
  were red before their change and green after.
- **Total: 532 unit tests, 531 run and 1 skipped** (+1 `HtmlLangDeclarationTest`: the "party ended" and error pages lead a guest back to the party's link) (2026-10-01, the CSP and review items 4.6 / 5.4 / 1.6 / 1.9: `SongEvaluationServiceTest`
  +7 — the whole pipeline with a test answering for Gemini (`askAi`), a rejection without a name, the guest's text in the prompt —,
  `GuestControllerTest` +2 (the style), `CspReportControllerTest` 3, `SmokeTest` +2 (the header, the report), `DjPartySettingsControllerFallbackTest` +2, `FallbackTrackCommandServiceTest` −1 (the
  two retry tests of 1.6 became one));
  before, **516** (with `QrPrintPageTest` 3: the poster, the cards, the bar's language — rendered through the real controller and QR generator, written to `target/qr-print/`); before the QR print **513, 512 run and 1 skipped** (`mvnw test "-Dtest=!Scan2playApplicationTests,!*IT"`; a first count of 537
  included the 24 ITs, which `-Dtest` had pulled into surefire — they are now skipped outside failsafe) after the review items of 2026-10-01 (1.2 `PartySettingsQueryServiceTest` 4,
  `PartySettingsCommandServiceTest` +1; 4.5 `YouTubeMusicProviderTest` +2; 1.4 / 2.1 the mocked tests of the import and of the
  version rewritten for the new queries). **507, 506 run and 1 skipped** with the guest's view of the queue (`GuestQueueServiceTest` 5, `GuestPageRenderTest` +3 —
  also the fragment alone —, `GuestControllerTest` +3, `GuestSessionServiceTest` +1); **495** with a YouTube Mix refused (`DjPartySettingsControllerFallbackTest` +1,
  `YouTubeUrlsTest` +1); **493** with the two request modes (`GuestPageRenderTest` 2 — the guest page rendered in both
  languages and after a mood sent as a song —, `RequestModeTest` 1, `GuestControllerTest` +2, `SongEvaluationServiceTest` +2);
  **486** with lyrics searched by the guest's words (`SongEvaluationServiceTest` +3: the
  answer read with and without `requestKind`, the search query); **483** with the video's title as the song's name
  (`SongEvaluationServiceTest` +2, `YouTubeUrlsTest` +1); **480** with the limits on the DJ's dashboard (`DashboardPageRenderTest` +1: the colour of the
  badges; `DjDashboardControllerGuestLimitsTest` 5 — the `X-Guest-Limits-Use` header and the
  `X-Guest-Limits` header, on a 304 too — two more in `GuestRequestLimiterTest`, one in `YouTubeSearchBudgetTest`; `DashboardPageRenderTest`
  checks the new ids); **471** after review items 4.1 and 1.3 (2026-09-30, the eighth session: `GuestRequestLimiterTest` 9,
  `GuestControllerTest` 5, `YouTubeSearchBudgetTest` 3, `YouTubeMusicProviderCacheTest` 2 (the `@Cacheable` through a real cache proxy),
  `YouTubeMusicProviderTest` +2, `DjServiceTest` +1; `GuestSessionServiceTest` rewritten for `tryAcquire`, 7 → 6, with parallel requests);
  **450** with the Auto-Pilot setting followed by every window (7 more:
  `DjPartySettingsControllerPlaybackModeTest`, one in `DjPlayerLeaseControllerTest`); **443** with the resume after a reload (one more in `DjPlayerLeaseControllerTest`:
  `secondsAgo` of `recent-tracks`); **442** after the review's first package (2026-09-30, the seventh session: 11 more —
  `SongEvaluationServiceTest` (the Spotify auto-queue), `YouTubeMusicProviderTest` (the key in a header), `SpotifyAuthControllerTest`
  (the OAuth state), one each in `DjServiceTest` and `AccountDeletionServiceTest`). Before it: **431 tests, 430 run and 1 skipped** (`.\mvnw.cmd -B test "-Dtest=!Scan2playApplicationTests"` in a copy of the repo, counted 2026-09-30, after the sixth session: 429 before it, minus the 6 tests of the removed `next-guest-track` endpoint, plus 3 of `DashboardPageRenderTest`, 4 of `HtmlLangDeclarationTest` and 1 of `FallbackQueueFragmentTest`). Until 2026-10-01 there were no integration tests in the repo (now: the `*IT` tests above) — the queue SQL of Phase 3, the history queries and V6 of Phase 4 stage 2, the play log and V7 of the follow-up (the round boundary, the keys, the retention, 480 concurrent hand-outs), and `V8`, the skip and the purge of the song requests (Sections 4.1, 5.4, 10) were checked against a throw-away PostgreSQL database, not by a test that stays. The one exception is the **fixture recorder** `PlayLogFixtureRecorderTest`: a `@SpringBootTest` that is skipped (no Spring context is even started) unless `S2P_FIXTURE_OUT` is set, and that refuses a database whose name does not start with `s2p_`; it is compiled with the rest, so an API change that breaks it shows at once.
- **Browser tests** (`src/test/browser`, Section 5.4 "Testing", 6.8): 58 scenarios (`page-forms.js`, 2026-10-01: the vibe select and the
  confirmations of the account buttons, which lost their inline handlers for the CSP — red with the new handlers removed; 9 added 2026-10-01 for the resume of an interrupted track, "resume" after a reload and a held pause, `resume.js`) that run the real `youtube-autopilot.js` and `dashboard.js` on the real rendered dashboard in a headless Chrome — **not part of `mvnw test`**, run by hand with `python src/test/browser/run.py` (a few minutes; needs Python 3, Java and Chrome or Edge, no Node, no other dependency). A GitHub Actions workflow runs them (`.github/workflows/browser-tests.yml`, on every push to `dev` / `main` and every pull request) — **first run on GitHub green (2026-09-30)**. The unit tests have their own workflow, `.github/workflows/unit-tests.yml` (same triggers) — **both green on GitHub for every push up to `d0ca3c7` (2026-10-01)**; the database tests have a third, `.github/workflows/db-tests.yml` (above) — not run on GitHub yet. What the browser tests do not cover is listed in Section 5.4.
- `Scan2playApplicationTests` (`@SpringBootTest`) requires full context (DB, OAuth2, Gemini) — skipped in CI without database.

### AI
- If Gemini API is down, all requests are auto-rejected (graceful degradation, but no retry mechanism).
- No server-side verification that the AI returned a real song.

### DJ Pick
- DJ Pick form is currently shown only for YouTube provider. Could be extended to Spotify.

---

## 14. Roadmap — V2.0 "Master Queue"

**Status (2026-09-29): Phases 1 and 2 are done on `dev`** — the target architecture is in place and Section 5.4
describes it. What is left is optional (see "Follow-ups" at the end of Phase 2). The rest of this section is the
record of how it got there.

Design discussion (2026-09) concluded the client-side Auto-Pilot logic (as it was until then) is a
"Fat Client" anti-pattern: the browser holds playback state, juggles the DJ's fallback
playlist against guest requests, and listens for flaky YouTube IFrame events. Target
architecture: "Dumb Client, Smart Server". Splitting into phases turned out to matter —
the original one-shot plan (below, kept for reference) assumed a `PENDING`/`APPROVED`
guest-song approval gate tied to Auto-Pilot that **does not actually exist in the code**
(Auto-Pilot ON/OFF only ever controlled whether the client auto-advanced or the DJ clicked
songs manually — every Gemini-accepted song has always gone straight to `accepted`).
Anyone continuing this: verify each phase against the actual code before building on it,
the way this note had to.

### Phase 1 — DONE (dev branch)

Moved just the "which guest song is next" decision server-side:
- `DjService.findNextPlayableGuestTrack(partyCode, excludeIds)` — oldest `accepted` song
  with an extractable video ID, reusing the existing `getDashboardQueue` cache (3s TTL).
- `GET /dj/dashboard/next-guest-track` — read-only, IDOR-checked, 204 when nothing's ready. *(Removed 2026-09-30, together with its
  tests: nothing had called it since stage 4. `DjService.findNextPlayableGuestTrack` stays — `NextTrackService` uses it.)*
- `youtube-autopilot.js`: `findNextGuestSong()` (DOM scan of `#song-list`) replaced by an
  async call to that endpoint. Dropped the client-side `markedAsPlayedIds`/`skippedSongIds`
  bookkeeping — the server is now the source of truth for "already played" (its query only
  ever returns `accepted` rows) and for "no valid video ID". Kept a small `erroredSongIds`
  set client-side for videos the *player itself* rejects (removed/private/region-blocked —
  unlike a missing video ID, the server can't know this), sent back as `?exclude=`.
- Added a `stateVersion` counter + a `tryAutoPlayInFlight` guard so the now-`async` player
  event handlers don't act on a stale lookup if a newer event fires while one is in flight.
- *Superseded by Phase 2 stage 4:* the client now calls `POST /dj/dashboard/next-track`, and `stateVersion` is gone
  (`tryAutoPlayInFlight` plus a re-check after the lookup do the job). `next-guest-track` remained for a while as a
  read-only "is a guest waiting?" endpoint that the client no longer called, and was **removed on 2026-09-30**.

**Deliberately NOT done in Phase 1** (see rationale below): no `PENDING` approval gate (it
never existed, so nothing to preserve), no pre-fetching, fallback/background-playlist
handling untouched.

### Phase 2 — DONE (2026-09-29; staged; dev branch)

Decision (2026-09-28): do it, in stages — quota turned out not to be a concern (see Section 7.3: playlist import ≈ 20
units from the general pool, `search.list` untouched). The remaining costs are schema management (solved by adopting
Flyway) and effort/regression risk in a live product, hence the stages:

| Stage | What | Status |
|-------|------|--------|
| 1 | Adopt Flyway; baseline `V1` (Section 10, "Database migrations") | **done** |
| 2 | `V2__create_fallback_track`, `FallbackTrackEntity`, `YouTubePlaylistClient` (import, max 500 tracks), `FallbackPlaylistService`; the fallback-playlist endpoint imports best-effort (headers); account deletion + 30-day purge; unit tests | **done** |
| 3 | `POST /dj/dashboard/next-track` (`NextTrackService`): guest song first, else the next `QUEUED` fallback track of the current playlist (playlist order or server-side shuffle, marked `PLAYED` when handed out, loops when exhausted); lazy/refresh import (nothing to play, or rows ≥ 29 days old); also `V3` limits fix and `YouTubeUrls` extracted from the controller | **done** |
| 4 | Simplify `youtube-autopilot.js` to "when idle / on `ENDED` / on error ask the server, `loadVideoById`" — drops `loadPlaylist`, playlist-index tracking, `guestSongPending`, `fallbackTrackChanged`, `setShuffle`/`setLoop`; also drops the `data-fallback-*` attributes and `updateFallbackShuffle`. Decided with the owner: a guest song waits until the running background track ends; a paused player is never touched (a pause is the DJ's choice); with Auto-Pilot off nothing starts by itself. A track the DJ picks by hand is no longer overridden by Auto-Pilot | **done** (2026-09-29) — verified against the real YouTube player, no automated tests for the JS |
| 5 | Docs cleanup: this section, 5.4 (rewritten), 5.1, `AGENTS.md` / `.github/copilot-instructions.md`, stale code comments | **done** (2026-09-29) |

**Deviations from the original plan below:** the tracks live in a dedicated `fallback_track` table (statuses `QUEUED` /
`PLAYED` / `CANCELLED`, no `type` column) instead of a unified `party_queue` — guest requests stay in `song_requests`
and the `PENDING` approval gate never existed. Only video IDs are stored, retained ≤ 30 days (titles were added in
Phase 3 — see below — under the same rule).

Original Phase 2 notes (written before the decision above — "today" means before stage 4):

1. **Background tracks server-side.** Today the fallback playlist is 100% client-side —
   `player.loadPlaylist(list: playlistId)` — YouTube's own IFrame player iterates/shuffles
   it, at **zero** YouTube Data API quota cost. Moving this server-side (as originally
   planned below: fetch 50 items via `playlistItems`, store as rows, decide server-side)
   is a real feature add, not a pure refactor — it introduces quota usage and DB storage
   that don't exist today, in exchange for a single unified `next-track` decision. Worth
   doing, but weigh it deliberately rather than assuming it's free.
2. **Pre-fetching** (~15s before track end, cache the next video ID client-side). Skipped
   in Phase 1: the endpoint call is same-origin and cheap (not an external API), so the
   gap it would close is small, while committing to a track *before* it's confirmed
   playing reopens exactly the kind of "mark played too early" risk Phase 1 was careful to
   avoid (see `findNextPlayableGuestTrack`'s doc comment — it deliberately does *not* mark
   played). Only worth it if the plain fetch-on-`ENDED` gap turns out to be audible.
   **Outcome (stage 4):** measured ENDED → next track PLAYING = 230–250 ms with the server stubbed, i.e. YouTube's
   own load time. No pre-fetching was built; revisit only if a gap is audible at a real party.

**Follow-ups after Phase 2** (none is needed for the feature to work):
- ~~Show the import result on the dashboard (`X-Fallback-Import: ok|failed`, `X-Fallback-Import-Reason`) instead of
  always flashing the Save button green.~~ **Done 2026-09-30** (Section 5.4, "DJ actions on the fallback playlist").
- ~~`GET /dj/dashboard/next-guest-track` and its tests are no longer used by the client; remove them if no other
  consumer appears.~~ **Removed 2026-09-30** (the endpoint and its six tests; `DjService.findNextPlayableGuestTrack` stays, `NextTrackService` uses it).
- ~~`youtube-autopilot.js` has no automated tests.~~ **Browser tests in the repo since 2026-09-30** (Section 5.4, "Testing").

### Phase 3 — the DJ sees and controls the fallback queue (done; dev branch)

Requested by the owner (2026-09-29): see which song comes next from the playlist, and be able to change the order.
Not possible before because the next track was only chosen when the player asked (a random row under shuffle) and no
titles were stored, so it was done in two steps:

| Step | What | Status |
|------|------|--------|
| 1 | `V4`: `fallback_track.play_order` + `title`. The order is fixed in advance (playlist order or a random order); `takeNextTrack` takes the lowest `play_order`; a new round is prepared as soon as the last track is taken, so there is always a "next". Shuffle switch re-orders the queue (on: reshuffle; off: playlist order continuing after the last played track). Titles come from the import's `videos.list` call (no extra quota). "Up next" panel on the dashboard (Section 5.4) | **done** (2026-09-29) — 186 unit tests plus a throw-away run against a real PostgreSQL 18 (V1–V4 on an empty database, Hibernate validation, the queue SQL, concurrent callers) and the real `dashboard.js` against stub endpoints |
| 2 | The DJ reorders the queue. `V5`: `fallback_track.manual_move`. Every row has ⇑ (play next), ↑ and ↓ buttons (`POST /dj/dashboard/fallback-queue/move`) and can be dragged to any place (`POST .../place`; mouse: press and drag, touch: press and hold, then drag); the moves apply to the current round only (a new order replaces them); the caption says "changed by hand" and the shuffle switch asks for confirmation first while such moves exist (owner's decision). Every method that changes the queue takes a per-party advisory lock | **done** (2026-09-29) — 232 unit tests plus a throw-away run against a real PostgreSQL 18 (V1–V5, the moves and drops, ties in the order, the flags, concurrent moves, drops and takes — which found a deadlock, now fixed by the lock) and the real `dashboard.js` against stub endpoints (mouse and touch drags included) |

Decisions for step 2 (owner, 2026-09-29): the shuffle switch stays a visible action on the list — the panel refreshes at
once and its caption always names the active order — and it **asks first** when the DJ has moved tracks by hand,
because the new order would throw those moves away. Skipping/removing a track was not built then (it was built on
2026-09-30, for the current round only — Section 5.4, "Skipping a track"). Drag and drop was
added on the owner's request after the first review (the buttons stay).

Details worth knowing:
- The shuffle is done by PostgreSQL (`UPDATE ... SET play_order = random key`), one statement per re-order; a shuffled
  round never opens with the track that has just been handed out (it is moved to the end).
- A drag refers to the track it is dropped in front of, not to a position, so it stays right even if the queue changed while
  the DJ was dragging (the player took a track meanwhile; if it was one of the two, the answer is 409). Server side: renumber
  the queue 0..n-1, shift the tracks in between by one (`shiftPlayOrder`, one statement), set the dragged track's order.
  Only the dragged track is flagged as moved by hand; the ones it passes merely shift.
- *Play next* sets the track's `play_order` to `min - 1`. Up / down first renumber the queue 0..n-1 (one statement) so
  that two neighbours can swap values even if the order had ties, then swap the two rows. A move at the end of the
  queue (up on the first, down on the last, play next on the one that is already next) changes nothing and sets no flag.
- Only a track that is still `QUEUED` in the party's **current** playlist can be moved (the id is looked up together
  with the party code, so one DJ can never reach another party's tracks); anything else is a 409.
- **Concurrency:** statements that update many rows at once (a renumbering, a re-order, a new round) can lock the same
  rows in different orders. A stress test with concurrent moves and takes against PostgreSQL did deadlock, so every
  method of `FallbackTrackCommandService` that changes the queue first takes `pg_advisory_xact_lock` for the party
  (`FallbackTrackRepository.lockQueue`); they run one after another, which costs nothing with one DJ.
- `takeNextTrack` reads the head of the queue and claims it under that lock, so no other caller can take it in between: one
  attempt (since 2026-10-01, review 1.6 — the loop of up to 10 attempts predated the lock); the conditional claim stays as a
  safety net and only logs a warning if it ever fails.
- The fragment is sent whole on every refresh (about 1 KB per row, so up to ~500 KB for a 500-track playlist); the
  server compresses responses of 2 KB and more with gzip (`server.compression.*`, since the review's first package,
  2026-09-30), which makes a long list roughly ten times smaller on the wire.

### Phase 4 — playback controls and readable lists (done; dev branch)

Requested by the owner (2026-09-29, after trying Phase 3 on a real phone): Next / Previous buttons "like a normal player",
and an active queue and a history tab that stay readable when they hold many songs (before stage 1 the whole table was in
the page and the DJ scrolled to the bottom; the queue holds up to 100 rows, and the history was the last 50 played or
rejected songs, ordered by *request* time — not by the time a song was played — without the background tracks; stage 2
made it one timeline by play time). The owner uses both a computer and a phone as the DJ, and a second window turned out
not to be passive, so it was agreed to start there:

| Stage | What | Status |
|-------|------|--------|
| 0 | **One window plays** — a per-party player lease (`PlayerLeaseService`, in memory): the window that holds it plays, the others show the queue and a banner with a "play on this device" button; `next-track` answers 409 to a window without the lease; a playlist saved or cleared in a window that does not play stops the playing window's track from the old playlist (the lease answer names the current playlist, `next-track` names each track's). Section 5.4, "One window plays" | **done** (2026-09-29) — 266 unit tests, and the real script in two browser tabs against a stand-in server; the owner tried it on the computer and the phone ("works well"); the playlist-change part was verified only against the stand-in |
| 1 | A ⏭ *Next* button: works with Auto-Pilot off too, and works as a **remote control** (owner's decisions 2026-09-29): pressed in a window that does not play, it sends a NEXT command that the window that plays picks up with its next lease report (≤ 3 s) and carries out; pressed in the window that plays it acts at once. It asks `next-track` whatever the player is doing. The active queue and the history in a list of fixed height with its own scrollbar and a sticky header, a count in the heading, a search box (accent-insensitive), a Played / Rejected filter and "Show more" (50 at a time, up to 300) in the history, compact rows (`table-sm`). Also the "up next" list of a window that did not change it: the lease answer carries a version of the list and a window fetches it again when it changes (found by the owner's question about shuffling on the phone). Section 5.4, "Next ⏭", "The up next list in a window that did not change it", "Long lists" | **done** (2026-09-29) — 313 unit tests, and the real dashboard page (60 songs in the queue, 120 in the history) with the real `dashboard.js` and `youtube-autopilot.js` in two browser tabs against a stand-in server; the owner tried it ("works") |
| 2 | `V6`: `song_requests.played_at`, history ordered by play time and merged with the background tracks (they already have `played_at` and titles) — one timeline of what played (`PlayHistoryService`); ⏮ *Back* on top of it, shared by both devices (a browser-only Back would lose its list on reload and when the DJ switches device): a track that has played for more than 3 s starts again, otherwise the track before it comes back, pressed again the one before that; it works from any window like ⏭ (`PlayerCommand.PREVIOUS`, `GET /dj/dashboard/recent-tracks`). ⏭ after ⏮ went to the queue in this stage, without walking forward through the history (changed in stage 4: it retraces the steps). Section 5.4, "Back ⏮", "The history is one timeline" | **done** (2026-09-29) — 344 unit tests; V6 and both history queries against a real PostgreSQL 18 (V1–V6 + Hibernate validation, V5 → V6 on existing data, the merge order, the bound, the filters); ⏮ on the real dashboard page with the real scripts in two browser tabs against a stand-in server; not tried on real devices |
| 3 | Two things the owner asked for while trying stage 2 on the phone (2026-09-29): **⏯ pause / resume from any window** (`PlayerCommand.PAUSE` / `RESUME`, explicit rather than a toggle; the window that plays reports whether its player makes sound, the answers tell it to the others, the button follows it and waits with "Sent…" until the state has changed), and **the History tab scrolls into view** on a phone, with a heading of its own (`historyHeading`). Section 5.4, "Pause ⏯", "Three tabs in a bar that stays in view" | **done** (2026-09-29) — 360 unit tests; the real dashboard page with the real scripts in two browser tabs against a stand-in server (local pause, remote pause and resume with the label following, a pause made at the computer showing on the phone, 409 when nobody plays, Auto-Pilot leaving a paused player alone); the History scroll on a 375×812 viewport through the *instant* path only — the smooth path could not be run in the invisible browser pane; not tried on real devices |
| 4 | Two decisions of the owner from the end of the stage 3 session (2026-09-29): **⏮ pressed twice within 10 s (20 s since 2026-09-30) goes to the previous track** (a restart that ⏮ caused is noted with a counter of the tracks loaded into the player, so it counts only for that track; `DOUBLE_PRESS_MS`; makes the previous track reachable from another window), and **three tabs Panel DJ-a / Kolejka / Historia in a sticky bar** (the `dj-nav` fragment split into a row of account buttons and the bar; `initTabs` in `dashboard.js`; Panel scrolls to the top, the lit tab follows the scroll; plain links for Spotify and the standalone history page). Section 5.4, "Back ⏮", "Three tabs in a bar that stays in view" | **done** (2026-09-29) — 365 unit tests (a new `DjNavFragmentTest`; 366 with the ⏭ follow-up below); the real rendered dashboard, history and up-next fragments with the real scripts and styles against a stand-in server: ⏮ locally (second press within 10 s goes back, after 12 s it restarts again, a new track resets it, the first 3 s go back at once) and from a second browser window in real time (restart at 2.8 s, previous track 3 s later); the tabs on 1280×800 and 375×812 (both languages), the History AJAX swap, Panel, sticky bar, lit tab by scroll position, anchors from the standalone page (the saved scroll is skipped, `scroll-margin-top`), a Spotify dashboard; scrolling through the *instant* path only — the smooth path could not be run in the invisible browser pane; not tried on real devices |

Decided for stage 1 (owner, 2026-09-29): *Next* in a window that does not play is a remote control of the window that
does (not a hidden button, and not a takeover — a takeover would move the sound to the phone); the command travels
through the lease reports (the command is kept on the server per party until the holder collects it; stage 2 sends
*Back* through the same channel). The queue and history lists may have their own scroll box on a phone, like the
"up next" list does. Built that way. Not built (left for later if the owner wants it): compact "card" rows on a phone
beyond `table-sm` (the columns that do not fit are already hidden on a narrow screen), and a spinner or a message while a
remote *Next* is on its way — the button only says "Sent…".

Stage 4 = two decisions the owner made at the end of the stage 3 session (2026-09-29), built afterwards: (1) *Back ⏮* pressed
twice within 10 s (20 s since 2026-09-30) goes to the previous track — after a restart that ⏮ caused, a second ⏮ skips the restart (before, from
another window, the previous track could not be reached: the button is disabled for 3.5 s and a command arrives at the next 3 s
report, so the second press always found the track past its first 3 seconds); (2) three navigation tabs **Panel DJ-a /
Kolejka / Historia** in a sticky bar, "Panel" scrolling to the top of the page, so that the DJ can jump to any part from
anywhere on a long page. Choices nobody was asked about: the account buttons moved to a row above the tab bar (only the tabs
stay in view), the lit tab follows the scroll position, and the tabs are plain links where there is no AJAX (Spotify, the
standalone history page). Right after stage 4 was pushed the owner noticed that ⏭ after ⏮ lost a guest song (⏮ from a guest
song, then ⏭, went to the next playlist track instead of back to the guest song), so ⏭ now retraces the steps after ⏮ — Section
5.4, "Back ⏮". Asked next whether playlist tracks belong in the history, the owner had two more filter buttons built — Guests and
Playlist next to All / Played / Rejected — applied by the server, so that the limit counts the entries of the chosen kind
(Section 5.4, "The history is one timeline"). With this Phase 4 is complete; what remains are the optional follow-ups in `SESSION_HANDOFF.md`.

**Follow-up of stage 4 — the history of the playlist survives a loop** (`V7`, owner's decision 2026-09-29, built in a new
session). The follow-up filters raised the question whether the history keeps the whole night, and the answer was "only until the
playlist loops": a new round re-queues the played tracks and clears their `played_at`, so the "Playlist" rows — and what ⏮ / ⏭
walk along — were at most one round long, and the track handed out at the boundary was in the history nowhere. Built as a
**play log**: the table `fallback_play` (Section 4.1), one row per hand-out, written by `takeNextTrack` inside the queue lock and the
same transaction; `PlayHistoryService` reads it instead of `fallback_track`; `next-track` answers with the log row's id, so the
client's key `B:<id>` is the key of exactly that history entry — a video that plays in two rounds is two entries (with the
track's own id as the key ⏮ would go round in circles, Section 5.4). Decisions: the log is kept **30 days from the fetch of the
data** (the video ID and title are YouTube API data, Non-Authorized, at most 30 calendar days by III.E.4.d; the row copies
`fetched_at`, purged with `fallback_track` and removed with the account); **replacing or clearing the playlist does not clear
it** (that is also what happened to the `PLAYED` tracks before — the owner's question assumed otherwise, the code was checked
first). Checked: 386 unit tests; V7 against a real PostgreSQL 18 (empty database, and V6 → V7 on existing data), the round
boundary with real answers replayed to the real script in the browser, the retention purge, the account deletion and 480
concurrent hand-outs together with moves, drops and the purge (Section 10). No change to `youtube-autopilot.js` beyond a comment.

**Follow-up of stage 4 — the session of 2026-09-29/30** (the owner's list after trying the play log on the phone; details in
Sections 4.1, 5.4, 6.8, 10 and in `SESSION_HANDOFF.md`):
1. **Browser tests in the repo** (`src/test/browser`; the owner agreed): the harness that had been rebuilt and thrown away three times
   — a render test, a Python stand-in server, a fake YouTube player, scenarios that run in the page, a fixture of real answers and a
   runner that works in a copy of the repo. First scenario: ⏮ / ⏭ across a loop, with the control of the old keys, which fails as it must.
2. **Three small fixes**, each with a scenario that failed first: the window of the second ⏮ is **20 s** (`DOUBLE_PRESS_MS`, the tooltip,
   the tests); a track that is loading counts as playing, so the pause button does not say "Wznów" between two videos (limited to 10 s);
   **⏭ after the playlist changed** starts the new playlist at once instead of retracing the old tracks (decided: clear the retracing,
   let the running old track finish).
3. **Polish**: the result of the playlist import is shown (green with the count, red with the reason); a line says how many guest
   songs wait; a track of the "up next" list can be **skipped for this round** (decided: this round only, not for good; `V8`, a new
   status `SKIPPED`).
4. **Retention of the guests' song requests: 30 days from `requested_at`** (the owner's decision; `SongRequestRetentionService`, a
   bounded nightly purge; the "Data Retention" of `privacy.html` / `privacy_pl.html` now says so, and lists the playlist tracks and the
   play log too).
5. **Not built, on purpose:** the hint for a window that is silent because Auto-Pilot is off, and the message for an IFrame API that
   does not load — the owner wants the friend's steps reproduced on a real phone first. The harness has scenarios that state what
   each of the three suspected states looks like today (`silent-*`); see `SESSION_HANDOFF.md`, "Next".
6. **Later the same day, after the owner tried ⏮ on the phone: two back buttons in a window that does not play** — "Wstecz" (always
   the previous track) and "Od początku" (the current track again from the start) instead of the single ⏮ with its rules, which is
   kept for the window that plays. New commands `PREVIOUS_TRACK` and `RESTART` (`PREVIOUS` stays, accepted); Section 5.4, "Two buttons
   in a window that does not play".

**Follow-up of stage 4 — the sixth session, 2026-09-30** (what was left of the "Next" list of the fifth; the owner chose all four
items). The first question was whether the friend's steps had been reproduced on a real phone: they were tried on another laptop and
**worked** (no reproduction), and the owner will ask her again — so the hint for a window whose Auto-Pilot is off and the message for an
IFrame API that does not load are **still not built**, and the `silent-*` scenarios still describe today's behaviour.
1. **More browser scenarios** for what had no automated test: the lease (lost and taken back, free, released on leaving), the queue
   list (search, count, sort, across a poll), the History tab (filters, "Show more", the search text, a failed request) and the three
   tabs (Section 5.4, "Testing"). Written for code that already worked, so each was checked by **breaking the code in a copy** — 17
   mutations, all killed. To feed them: the stand-in serves the History fragment (one rendered file per filter, first page and "Show
   more"), `DashboardPageRenderTest` renders them through the real controller.
2. **`<html lang>` of `dashboard.html`** follows the language of the texts: `th:lang="#{html.lang}"`, the key is in both bundles (a
   `${#locale.language}` would say `de` over English texts for a German browser, which has no bundle). Scenario `page-language`
   was red first. The six other pages that use the bundles got the same attribute later that day (`HtmlLangDeclarationTest`, Section 13).
3. **`GET /dj/dashboard/next-guest-track` removed**, with its six tests (Section 12).
4. **Browser tests in CI**: `.github/workflows/browser-tests.yml`; its first run on GitHub was green (Section 5.4, "In CI"). The unit tests
   got `.github/workflows/unit-tests.yml` after that run, as agreed.

### Original one-shot plan (kept for reference — see caveat above)

1. **Single source of truth on the backend.** A unified `party_queue` table replaces the
   current split between DB-stored `SongRequestEntity` rows and the client-side fallback
   playlist. Each row has:
   - `type`: `GUEST_REQUEST` | `BACKGROUND_TRACK`
   - `status`: `PENDING` | `APPROVED` | `PLAYED` | `CANCELLED`
   - `created_at` (ordering)
2. **Frontend becomes a dumb player.** `youtube-autopilot.js` stops knowing about
   Auto-Pilot, fallback playlists, or guest vs. background distinctions. It only:
   - Listens for `onStateChange == ENDED`.
   - Calls `GET /api/party/{partyId}/next-track`.
   - Loads whatever video ID comes back.
   - **Pre-fetching:** ~15s before the current track ends, fetch the next track ID in the
     background and cache it, so the switch at track-end is instant (no network wait).
3. **Backend `NextTrackStrategy` decision tree** — as written up-front this assumed a
   `PENDING`/`APPROVED` gate keyed on Auto-Pilot that isn't real (see caveat above); a
   faithful version of this phase would need its own approval-workflow design, not just a
   port of existing behavior:
   - Auto-Pilot ON → Gemini-accepted guest song → `APPROVED` immediately.
   - Auto-Pilot OFF → Gemini-accepted guest song → `PENDING` until DJ clicks Accept on the
     dashboard, then → `APPROVED`.
   - `next-track` query priority: oldest `APPROVED` `GUEST_REQUEST` first, else oldest
     `APPROVED` `BACKGROUND_TRACK`. Winner flips to `PLAYED`.
4. **YouTube quota discipline is the reason background tracks live in Postgres at all.**
   When the DJ sets/changes the fallback playlist, hit
   `GET /youtube/v3/playlistItems` **once** (50 tracks per call, 1 quota point), store the
   video IDs as `BACKGROUND_TRACK`/`APPROVED` rows, and serve every subsequent "what's
   next" decision from Postgres — never re-poll YouTube per track.
5. **Changing the fallback playlist mid-party = soft invalidation, not `DELETE`.**
   `PLAYED` rows are kept (history/analytics). Unplayed `BACKGROUND_TRACK` rows
   (`APPROVED`) flip to `CANCELLED`. New tracks are fetched and inserted as
   `APPROVED`. Wrap this in `@Transactional` so a YouTube API failure rolls back and
   the currently playing/queued track is never disrupted (Graceful Degradation
   principle already used elsewhere in this codebase, e.g. Gemini-down handling).
   The currently-playing video finishes undisturbed either way, because the dumb
   client never sees this happen — it just asks `next-track` again when the track ends.

Existing pieces that carry over unchanged regardless of phase: Gemini evaluation
(`SongEvaluationService`), the two-level YouTube cache (Section 7.3, unrelated — that
caches *search-by-name → video ID* lookups, not the fallback playlist), and IDOR
ownership checks (`DjSessionHelper`).
