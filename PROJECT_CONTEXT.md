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

**Indexes:** `idx_party_code` on `partyCode`, `idx_party_decision_time` on `(partyCode, decision, requestedAt DESC)`.

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

#### `FallbackTrackEntity` → table: `fallback_track` (Flyway `V2`)

Server-side copy of a party's fallback ("background music") playlist — Section 14, Phase 2. Written when the DJ
sets the playlist; **not yet read by playback** (the client still runs `loadPlaylist()` until Phase 2 stage 3/4).

| Field              | Type                    | Notes                                                             |
|--------------------|-------------------------|-------------------------------------------------------------------|
| `id`               | Long (PK, auto)         |                                                                   |
| `partyCode`        | String(5)               | Owning party (no FK, like `song_requests`)                        |
| `playlistId`       | String(64)              | Source: playlist ID, or `V:<videoId>` for a single video          |
| `videoId`          | String(20)              | 11-char YouTube video ID (no titles stored — ToS minimisation)    |
| `playlistPosition` | int                     | 0-based order within the source playlist                          |
| `status`           | FallbackTrackStatus     | `QUEUED` → `PLAYED`, or `CANCELLED` (playlist changed/cleared)    |
| `fetchedAt`        | LocalDateTime           | When fetched from the YouTube API — basis of the 30-day retention |
| `playedAt`         | LocalDateTime (nullable)|                                                                   |

**Soft invalidation:** changing/clearing the playlist flips still-`QUEUED` rows to `CANCELLED` (never deletes); `PLAYED`
rows stay as history. **Retention:** rows older than 30 days are purged daily at 04:30
(`FallbackTrackCommandService.purgeStaleTracks`) and on account deletion. **Index:** `idx_fallback_track_party_status`
on `(partyCode, status)`.

### 4.2 Enums

| Enum                | Values |
|---------------------|--------|
| `MusicProviderType` | `SPOTIFY`, `YOUTUBE` |
| `PlaybackMode`      | `MANUAL`, `AUTO` |
| `FallbackTrackStatus` | `QUEUED`, `PLAYED`, `CANCELLED` |
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
  - **YouTube:** Client-side — embedded IFrame Player auto-plays accepted songs
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
    → Guest sees request form + top 5 accepted songs
    → Submits song request (name + optional style)
    → AI evaluates request via Gemini (async Callable — releases Tomcat thread)
    → Result page shows decision + DJ comment
```

Rate limiting is session-based (HttpSession). No guest authentication required.

### 5.3 Spotify Playback Auth (Secondary OAuth2)

```
Dashboard → [Connect Spotify for Auto-Pilot]
    → /dj/spotify/login?partyCode=XXXXX  (authenticated + ownership validated)
    → Spotify Authorization (playback scopes)
    → /dj/spotify/callback (authenticated + ownership validated, tokens stored in PartySettingsEntity)
```

This is a **separate** OAuth2 flow from the login. The login OAuth2 identifies the DJ. The Spotify playback OAuth2 grants permission to control the DJ's Spotify player.

**Security:** Both endpoints require DJ authentication and validate partyCode ownership (IDOR protection).

### 5.4 YouTube Auto-Pilot (Client-Side, guest-track decision now server-side)

> **Note (2026-09-28):** rewritten below to match `main` @ 5314006. The polling-watcher
> design (500ms `setInterval` comparing `getCurrentTime()`/`getDuration()`) described in
> earlier versions of this doc was removed on 2026-04-06 (`68e84c9`) in favor of purely
> event-driven transitions. Do not reintroduce a polling watcher.
>
> **Update (dev branch, this session):** "which guest song is next" no longer scans the
> DOM — `checkYouTubeAutoPlay()` now calls `GET /dj/dashboard/next-guest-track`
> (`DjService.findNextPlayableGuestTrack`), which is Phase 1 of the Section 14 Master
> Queue plan. Everything else below (fallback playlist, shuffle, natural-boundary
> guest-song handover) is still exactly as described — only the "find the next guest
> song" step moved. See Section 14 for what's done vs. still client-side.

```
Dashboard (YouTube provider, Auto-Pilot ON)
    → youtube-autopilot.js loads YouTube IFrame Player API
    → dashboard.js polling refreshes #song-list every 3s (ETag/304) — DJ's visual queue only,
       no longer read by Auto-Pilot's decision logic
    → checkYouTubeAutoPlay() asks the server (GET /dj/dashboard/next-guest-track) for the
       oldest accepted song with a valid video URL — same FIFO+eligibility rule as before,
       just server-side now
    → Loads video via loadVideoById()
    → On PLAYING: marks song as PLAYED via fetch POST
    → On ENDED: resets, tries next song immediately
    → On PAUSED: does nothing (respects manual pause)

Fallback Playlist (queue empty):
    → If queue is empty and fallbackPlaylistId is configured:
      → Loads YouTube playlist via player.loadPlaylist() (or loops single video)
      → YouTube auto-advances through playlist tracks
    → If guest song arrives during fallback:
      → Does NOT interrupt current track mid-song (graceful handover)
      → Detected at natural track boundaries only — ENDED for a single looped video,
         or PLAYING with a trackChanged/BUFFERING signal for playlist auto-advance —
         no polling watcher; the player switches to the guest song at that boundary
      → Local `fallbackTrackIndex` is tracked synchronously (no `getPlaylistIndex()`
         round-trip) and restored on resume — fallback never restarts from the same
         song each time
    → When guest queue empties again, fallback resumes from saved position
    → Save without reload: DJ pastes URL → AJAX POST → updateFallbackSource()
       updates Auto-Pilot in-memory state immediately, no page refresh needed
    → Stop button: clears fallback URL server-side and stops playback instantly
```

Supported fallback URL formats (resolved client-side and server-side):
- `https://youtube.com/playlist?list=PLxxx` → playlist ID
- `https://youtube.com/watch?v=abc&list=PLxxx` → playlist ID (prefers `list=`)
- `https://youtube.com/watch?v=KD5fLb-WgBU` → single video (looped)
- `https://youtu.be/KD5fLb-WgBU?si=...` → single video (looped)
- Raw playlist ID (`PLxxx`) or raw 11-char video ID → resolved automatically

---

## 6. File Inventory

### 6.1 Controllers

| Class                       | Mapping                 | Purpose |
|-----------------------------|-------------------------|---------|
| `HomeController`            | `GET /`                 | Landing page or redirect to dashboard if authenticated |
| `DjDashboardController`     | `/dj/dashboard`, `/dj/history-view` | DJ dashboard view, AJAX polling updates (ETag), history view/fragment; `extractPlaylistId()` resolves YouTube URLs to playlist/video IDs |
| `DjPartySettingsController` | `/dj/**`                | Start/end party, vibe, rate limits, playback mode, fallback shuffle, account deletion |
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
| `DjService`                  | 201   | Song queue queries (dashboard, history, public), queue fingerprint, and song actions (mark played, push to Spotify, DJ picks) — all song actions validate partyCode ownership |
| `SongEvaluationService`      | 280   | AI-powered song evaluation pipeline: Gemini AI → track resolution → save → optional auto-queue; includes **song name normalization** (temperature 0.0 for deterministic output) |
| `PartySettingsCommandService`| 76    | Create/update party settings via generic `updateSettings()` lambda (write side, `@CachePut`) |
| `PartySettingsQueryService`  | 31    | Read party settings (read side, `@Cacheable`) |
| `QueueService`               | 75    | Delegates to MusicProvider implementations (resolve track, add to queue) |
| `SpotifyMusicProvider`       | 194   | Spotify integration: search tracks (Client Credentials), add to queue (User Auth) |
| `YouTubeMusicProvider`       | 198   | YouTube integration: Data API v3 with two-level cache (Caffeine L1 + PostgreSQL L2, 30-day TTL per YouTube API ToS) + daily scheduled cleanup |
| `SpotifyAuthService`         | 188   | Spotify OAuth2 token management (exchange, refresh, store) — null-safe refresh with explicit exception |
| `GuestSessionService`        | 73    | Session-based rate limiting for guests (token bucket) |
| `QrCodeService`              | 50    | QR code generation (ZXing, `@Cacheable`) |
| `AccountDeletionService`     | 65    | Deletes all DJ data (songs, fallback tracks, feedback, settings) — required by Google API data deletion policy |
| `YouTubePlaylistClient`      | 156   | Reads a playlist via YouTube Data API (`playlistItems.list` + `videos.list`): max 500 items, drops private/deleted/non-embeddable videos; API key never appears in errors |
| `FallbackPlaylistService`    | 53    | Syncs the party's server-side fallback tracks with the DJ's playlist (playlist / single video / cleared). API first, DB only after a complete non-empty result |
| `FallbackTrackCommandService`| 82    | Transactional writes for `fallback_track`: replace (soft-invalidate QUEUED → CANCELLED, insert new), cancel, daily 30-day purge |

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
| `history.html`         | DJ history view: played and rejected songs |
| `index.html`           | Guest song request form |
| `result.html`          | Guest view: AI decision result |
| `party_ended.html`     | Guest view when party is inactive |
| `error.html`           | Generic error page (403, 404, 500) |
| `privacy.html`         | Privacy Policy (English) |
| `privacy_pl.html`      | Privacy Policy (Polish) |
| `terms.html`           | Terms of Service (English) |
| `terms_pl.html`        | Terms of Service (Polish) |
| `fragments/components.html` | Shared fragments: DJ navigation, scroll restore script, feedback modal + toast + JS |

### 6.6 Static Assets

| File                    | Purpose |
|-------------------------|---------|
| `css/app.css`           | Shared stylesheet with design tokens, page-scoped rules (`.page-dj`, `.page-guest`, etc.) |
| `js/dashboard.js`       | Dashboard core: AJAX form interceptor (preserves YT player), AJAX tab switching, table polling (3s, ETag/304), clipboard, client-side table sorting |
| `js/youtube-autopilot.js` | YouTube Auto-Pilot: IFrame Player state machine, auto-plays accepted songs, respects pause; fallback playlist state machine (start/resume/stop), 500ms watcher for graceful guest song handover, playlist position tracking |
| `js/song-autocomplete.js` | Song autocomplete / typeahead via public iTunes Search API (client-side, debounced at 300ms, no server involvement, no YouTube quota) |

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

---

## 7. External API Integrations

### 7.1 Google Gemini AI

- **Purpose:** Evaluate song requests (accept/reject based on vibe match) + normalize DJ pick song names
- **Model:** `gemini-2.5-flash-lite` (pinned — do NOT use `*-latest` aliases)
- **SDK:** `google-genai` Java SDK
- **Response format:** JSON (`DjResponse` record)
- **Temperature:** Default for evaluations (creative DJ comments), **0.0 for normalization** (deterministic)
- **Fallback:** If AI fails → request is auto-rejected with "AI offline" message; normalization falls back to raw input
- **Prompt language:** Locale-aware (English + Polish). Prompt is selected based on guest's browser locale via `LocaleContextHolder`; unsupported locales fall back to English.
- **Comment length:** AI instructed to keep comments under 300 characters; entity truncates at 500 as safety net
- **Duplicate detection:** Configurable window — recent N songs are injected into the prompt

### 7.2 Spotify Web API

Two separate authentication flows:

1. **Login OAuth2** (Spring Security) — identifies the DJ
   - Scopes: `user-modify-playback-state`, `user-read-playback-state`, `user-read-private`
   - Provider config in `application.properties`

2. **Playback OAuth2** (Custom `SpotifyAuthService`) — controls DJ's Spotify player
   - Same scopes but separate token stored in `PartySettingsEntity`
   - Token auto-refresh when expired (5-minute buffer)

3. **Client Credentials** (Application-level) — search tracks without user context
   - Used by `SpotifyMusicProvider.findTrackUrl()`
   - Thread-safe cached token with double-checked locking via `ReentrantLock`

### 7.3 YouTube Data API v3 (Two-Level Cache)

- **Purpose:** Resolve song names to playable YouTube video URLs
- **Implementation:** `YouTubeMusicProvider` uses `RestClient` to call YouTube Search API
- **API key:** Configured via `youtube.api-key` property
- **Quota optimization — two-level cache:** YouTube video IDs are permanent, so each unique song costs API quota only **once per 30 days**:
  - **L1 — Caffeine (in-memory, 24h TTL):** prevents repeated DB queries during a session
  - **L2 — PostgreSQL (`youtube_cache` table, 30-day TTL):** survives restarts, compliant with YouTube API ToS (max 30-day retention)
  - **L3 — YouTube Data API (100 quota/search):** only called on L1+L2 miss or L2 entry expired
- **ToS compliance:** Expired entries (>30 days) are refreshed on next access and cleaned up daily at 04:00 via `@Scheduled` task
- **Fallback:** If API key is missing or search fails → returns YouTube search results URL (manual play only, Auto-Pilot won't work with search URLs)
- **Auto-Pilot:** Handled client-side via YouTube IFrame Player API (`youtube-autopilot.js`); the "which guest song next" decision is server-side (Section 14)
- **Quota (per Google's "Quota costs" page, checked 2026-09-28):** `search.list` has its **own** default limit of 100
  calls/day; every other endpoint shares **10,000 units/day**. `playlistItems.list` and `videos.list` cost 1 unit per call.
  (The older wording "100 units per search" gives the same ~100 unique searches/day.) Verify your project's actual
  quota in Google Cloud Console → APIs & Services → YouTube Data API v3 → Quotas.
- **Playlist import (`YouTubePlaylistClient`, Phase 2):** when the DJ sets a fallback playlist the backend reads it once —
  at most 500 items = ≤ 10 `playlistItems` + ≤ 10 `videos` calls (≈ 20 units from the general pool, none from `search.list`).
  Requires `youtube.api-key`; a single video needs no API call. Failures (`NO_API_KEY`, `INVALID_PLAYLIST`, `API_ERROR`,
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
| `/dj/**`             | Authenticated   |
| Everything else      | Authenticated   |

- OAuth2 login via Spotify or Google
- Custom login page: `/` (landing page)
- Successful login redirects to `/dj/dashboard`
- Logout: `POST /dj/logout` → clears session → redirects to `/`
- CSRF enabled (tokens in `<meta>` tags for AJAX, available on dashboard and history pages)
- **IDOR protection:** All DJ endpoints that accept `partyCode` validate ownership via `DjSessionHelper.validateOwnership()` (compares request partyCode against session-cached partyCode)
- **Spotify playback OAuth2** moved to `/dj/spotify/**` (authenticated, ownership-validated)

---

## 9. Caching Strategy

Uses **Caffeine** cache with per-cache TTL configuration.

| Cache Name       | Key        | TTL    | Max Size | Usage |
|------------------|------------|--------|----------|-------|
| `partySettings`  | partyCode  | 24h    | 500      | `PartySettingsQueryService.getSettings()` (`@Cacheable`) / `PartySettingsCommandService.updateSettings()` (`@CachePut`) |
| `qr-codes`       | text+size  | 24h    | 1000     | `QrCodeService.generateQrCodeBase64()` (`@Cacheable`) |
| `youtubeSearch`  | searchQuery| 24h    | 1000     | `YouTubeMusicProvider.findTrackUrl()` L1 cache — backed by permanent `youtube_cache` DB table (L2) |
| `dashboardQueue` | partyCode  | 3s     | 200      | `DjService.getDashboardQueue()` (`@Cacheable`) — auto-expires for polling freshness |
| `publicQueue`    | partyCode  | 5s     | 200      | `DjService.getPublicQueue()` (`@Cacheable`) |

Additional caching: DJ's `partyCode` is cached in `HttpSession` to avoid repeated `ownerId` → DB lookups.

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

### Key Application Properties

| Property                         | Value                                    |
|----------------------------------|------------------------------------------|
| `server.port`                    | 8080                                     |
| `scan2play.guest-url`            | `https://www.scan2play.com.pl/`          |
| `google.ai.model-name`           | `gemini-2.5-flash-lite`                  |
| `spring.jpa.hibernate.ddl-auto`  | `validate`                               |
| `server.forward-headers-strategy`| `FRAMEWORK` (for reverse proxy)          |
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
- `V1__baseline.sql` is the schema as of 2026-09-28 (taken from a dump of the working database).
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
    └── YouTubeMusicProvider

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
| POST   | `/p/{partyCode}/request`    | `GuestController.requestSong()`  |
| GET    | `/privacy`                  | `LegalController.privacyPolicy()` |
| GET    | `/terms`                    | `LegalController.termsOfService()` |

### Authenticated (DJ Only)

| Method | Path                              | Handler                                          | Notes |
|--------|-----------------------------------|--------------------------------------------------|-------|
| GET    | `/dj/dashboard`                   | `DjDashboardController.dashboard()`              |       |
| GET    | `/dj/history-view`                | `DjDashboardController.historyView()`            |       |
| GET    | `/dj/history-view/fragment`       | `DjDashboardController.historyFragment()`        | AJAX partial HTML, ownership-validated |
| GET    | `/dj/dashboard/updates`           | `DjDashboardController.getDashboardUpdates()`    | AJAX partial HTML (polling, ETag/304), ownership-validated |
| GET    | `/dj/dashboard/next-guest-track`  | `DjDashboardController.nextGuestTrack()`         | JSON, read-only, ownership-validated — Auto-Pilot "what's next" (Section 14 Phase 1) |
| POST   | `/dj/dashboard/vibe`              | `DjPartySettingsController.updateGlobalVibe()`   | ownership-validated |
| POST   | `/dj/dashboard/limits`            | `DjPartySettingsController.updateLimits()`       | ownership-validated |
| POST   | `/dj/dashboard/play`              | `DjSongController.markAsPlayed()`                | song-level ownership check |
| POST   | `/dj/dashboard/playback-mode`     | `DjPartySettingsController.togglePlaybackMode()` | ownership-validated |
| POST   | `/dj/dashboard/fallback-playlist` | `DjPartySettingsController.updateFallbackPlaylist()` | YouTube only, ownership-validated. Always saves the URL; also imports the playlist into `fallback_track` (best-effort) and reports it in headers: `X-Fallback-Id`, `X-Fallback-Import: ok\|failed`, `X-Fallback-Tracks: <n>` or `X-Fallback-Import-Reason: NO_API_KEY\|INVALID_PLAYLIST\|API_ERROR\|NO_PLAYABLE_TRACKS` |
| POST   | `/dj/dashboard/fallback-shuffle`  | `DjPartySettingsController.toggleFallbackShuffle()` | ownership-validated |
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
- No Content Security Policy (CSP) headers — should be added to restrict script sources (YouTube IFrame, iTunes API).

### Frontend
- Server-rendered (Thymeleaf) with AJAX enhancements. No SPA, no JavaScript framework.
- Dashboard live updates via `setTimeout` + `fetch` polling every 3 seconds with ETag/304 optimization. No WebSockets/SSE.
- No client-side validation on forms (server-side only).

### Scalability
- **Session-based rate limiting** (HttpSession only). Clearing cookies resets the limit. No server-side rate limiting per IP.
- **In-memory Caffeine cache** — not shared across instances. If horizontally scaled, consider Spring Session + Redis.
- **Single-instance deployment** assumed. For multi-instance, caching and session management need Redis/JDBC backing.

### Testing
- **Smoke test (7 tests)** — `SmokeTest` (`@WebMvcTest`, no DB): public routes, security redirects, YouTube IFrame not server-rendered.
- **Unit tests (68 tests)** covering core business logic: entity truncation, code generation, rate limiting, queue management, IDOR blocking, provider delegation, party lifecycle, playlist URL extraction.
- Unit tests are pure Mockito (no Spring context) — fast (~2s). Smoke test uses `@WebMvcTest` (~5s).
- **Total: 75 tests.** No integration tests.
- `Scan2playApplicationTests` (`@SpringBootTest`) requires full context (DB, OAuth2, Gemini) — skipped in CI without database.

### AI
- If Gemini API is down, all requests are auto-rejected (graceful degradation, but no retry mechanism).
- No server-side verification that the AI returned a real song.

### DJ Pick
- DJ Pick form is currently shown only for YouTube provider. Could be extended to Spotify.

---

## 14. Roadmap — V2.0 "Master Queue"

Design discussion (2026-09) concluded the client-side Auto-Pilot logic (Section 5.4) is a
"Fat Client" anti-pattern: the browser holds playback state, juggles the DJ's fallback
playlist against guest requests, and listens for flaky YouTube IFrame events. Target
architecture: "Dumb Client, Smart Server". Splitting into phases turned out to matter —
the original one-shot plan (below, kept for reference) assumed a `PENDING`/`APPROVED`
guest-song approval gate tied to Auto-Pilot that **does not actually exist in the code**
(Auto-Pilot ON/OFF only ever controlled whether the client auto-advanced or the DJ clicked
songs manually — every Gemini-accepted song has always gone straight to `accepted`).
Anyone continuing this: verify each phase against the actual code before building on it,
the way this note had to.

### Phase 1 — DONE (dev branch, this session)

Moved just the "which guest song is next" decision server-side:
- `DjService.findNextPlayableGuestTrack(partyCode, excludeIds)` — oldest `accepted` song
  with an extractable video ID, reusing the existing `getDashboardQueue` cache (3s TTL).
- `GET /dj/dashboard/next-guest-track` — read-only, IDOR-checked, 204 when nothing's ready.
- `youtube-autopilot.js`: `findNextGuestSong()` (DOM scan of `#song-list`) replaced by an
  async call to that endpoint. Dropped the client-side `markedAsPlayedIds`/`skippedSongIds`
  bookkeeping — the server is now the source of truth for "already played" (its query only
  ever returns `accepted` rows) and for "no valid video ID". Kept a small `erroredSongIds`
  set client-side for videos the *player itself* rejects (removed/private/region-blocked —
  unlike a missing video ID, the server can't know this), sent back as `?exclude=`.
- Added a `stateVersion` counter + a `tryAutoPlayInFlight` guard so the now-`async` player
  event handlers don't act on a stale lookup if a newer event fires while one is in flight.

**Deliberately NOT done in Phase 1** (see rationale below): no `PENDING` approval gate (it
never existed, so nothing to preserve), no pre-fetching, fallback/background-playlist
handling untouched.

### Phase 2 — IN PROGRESS (staged; dev branch)

Decision (2026-09-28): do it, in stages — quota turned out not to be a concern (see Section 7.3: playlist import ≈ 20
units from the general pool, `search.list` untouched). The remaining costs are schema management (solved by adopting
Flyway) and effort/regression risk in a live product, hence the stages:

| Stage | What | Status |
|-------|------|--------|
| 1 | Adopt Flyway; baseline `V1` (Section 10, "Database migrations") | **done** |
| 2 | `V2__create_fallback_track`, `FallbackTrackEntity`, `YouTubePlaylistClient` (import, max 500 tracks), `FallbackPlaylistService`; the fallback-playlist endpoint imports best-effort (headers); account deletion + 30-day purge; unit tests | **done** — data is written, **nothing reads it yet** |
| 3 | Extend the "what's next" endpoint: guest song first, else next `QUEUED` fallback track (server-side shuffle, mark `PLAYED`); lazy import for parties whose playlist was set before Stage 2 or whose rows are >29 days old | todo |
| 4 | Simplify `youtube-autopilot.js` to "on `ENDED`/error ask the server, `loadVideoById`" — drops `loadPlaylist`, playlist-index tracking, `guestSongPending`, `fallbackTrackChanged` | todo |
| 5 | Docs cleanup (this section, 5.4) | todo |

**Deviations from the original plan below:** the tracks live in a dedicated `fallback_track` table (statuses `QUEUED` /
`PLAYED` / `CANCELLED`, no `type` column) instead of a unified `party_queue` — guest requests stay in `song_requests`
and the `PENDING` approval gate never existed. Only video IDs are stored (no titles), retained ≤ 30 days.

Original Phase 2 notes (written before the decision above):

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
