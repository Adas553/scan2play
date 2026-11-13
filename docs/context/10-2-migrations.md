> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 10 (cont.). Keep it to what is true now.

### Database migrations (Flyway)

The schema is a sequence of files `src/main/resources/db/migration/V<n>__<what>.sql`, applied in order at startup before Hibernate
validates; **never edit an applied one** — not even a comment: Flyway checksums the whole file; add the next. An entity change that
touches the schema needs its migration in the same change, or the application does not start.
`spring.flyway.baseline-on-migrate=false` (the review of 2026-10-14; `true` until then): it recorded a database that had tables
before Flyway (production, once, 2026-09-28) as V1 without running it. Every database has its history now; one with tables and no
history is the wrong database, and the start fails. To move an old one once more: `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` for that start.
**`SPRING_JPA_HIBERNATE_DDL_AUTO`** is not set on Railway (removed 2026-10-06); never set it to `update`: Hibernate would change the
schema behind Flyway's back.

| Version | What |
|---------|------|
| V1 | baseline (the schema of 2026-09-28) |
| V2, V4, V5, V7, V8, V9 | the background playlist's `fallback_track`, its order, moves, play log `fallback_play`, status `SKIPPED`, the queue indexes (all dropped by V19) |
| V3 | data fix: request limits 0 → 2 / 3 |
| V6 | `song_requests.played_at` (no back-fill) |
| V10 | `youtube_search_budget` (dropped by V19) |
| V11 | `spring_session`, `spring_session_attributes` (Spring Session JDBC's schema, word for word) |
| V12 | every `timestamp` → `timestamptz`; the old values read in the session's zone = the JVM's that wrote them (Polish time locally, UTC on Railway) |
| V13 | `party_settings.active_provider` may be `REQUESTS_ONLY` (the check constraint) |
| V14 | `song_requests.guest_text` varchar(150), nullable (no back-fill: the words of older requests were never kept) |
| V15 | `song_requests.votes` integer NOT NULL DEFAULT 1 |
| V16 | `party_settings.global_vibe`: BACHATA_AND_KIZOMBA / SALSA_AND_TIMBA / REGGAETON_AND_DANCEHALL → LATINO, the check constraint with the new list; `party_settings.vibe_note` varchar(150) |
| V17 | `party_settings.dj_name` varchar(60), nullable |
| V18 | Spotify removed: Spotify parties deleted with their requests, background tracks, plays and their DJ's feedback; `spotify_*` columns dropped; the kind's check without SPOTIFY |
| V19 | YouTube removed: every party that is not `REQUESTS_ONLY` (and one with no kind) deleted with its requests and its DJ's feedback; `fallback_play`, `fallback_track`, `youtube_cache`, `youtube_search_budget` dropped; the columns `active_provider`, `playback_mode`, `fallback_playlist_url`, `fallback_shuffle` dropped |
| V20 | `song_requests.skipped_at` timestamptz, nullable: when the DJ skipped the request; the requests skipped before get their request time |
| V21 | `push_subscription`: the DJ's devices of the notifications (`endpoint` UNIQUE, index on `owner_id`) |
| V22 | `party_settings.comment_style` varchar(20) NOT NULL DEFAULT 'CLASSIC', a check of the five styles |
| V23 | data: the notes "Skipped by the DJ ⏭" / "Restored by the DJ ↩" a skip and a restore wrote in place of the AI's comment → NULL (a skip keeps the AI's comment since; `SkipCommentMigrationIT`) |
| V24 | `party_settings.instagram_url`, `facebook_url`, `tiktok_url` varchar(200), nullable: the DJ's profiles |
| V25 | `song_requests.cleared_at` timestamptz, nullable: when the DJ cleared the request with the queue; the old note "Cleared by the DJ 🧹" → NULL, its request time as `cleared_at` (`SkipCommentMigrationIT`) |
| V26 | data + constraint: `comment_style` 'SARCASTIC_LIGHT' → 'SARCASTIC', the check without it (`CommentStyleMigrationIT`) |
| V27 | `party_settings.tip_url` varchar(200), nullable: the DJ's tip link |
| V28 | `party_settings.request_counter` int NOT NULL DEFAULT 0; `song_requests.request_number` int, `tips` int NOT NULL DEFAULT 0 (≥ 0); the songs so far that reached the queue numbered in request order per party, each count set to its last number; `uk_song_requests_party_number` (party, number) unique where numbered (`RequestNumberMigrationIT`) |
| V29 | `party_settings.host_blocked`, `host_wanted` varchar(16000), `host_token` varchar(32) (unique index `uk_party_settings_host_token`), all nullable: the hosts' lists and their link |
| V30 | `party_settings.staff_token` varchar(32) (unique index `uk_party_settings_staff_token`); table `party_staff` (id, `party_code` FK ON DELETE CASCADE, `member_id`, `member_name` varchar(100), `joined_at`; UNIQUE (party, member), index `idx_party_staff_member_id`): the party's staff |
| V31 | `song_requests.energy_level` dropped: the AI's energy rating (1–10) is no longer asked for or shown (the owner, 2026-10-10: nobody used it) |
| V32 | `party_staff.permissions` varchar(200) NOT NULL (no default: the app always writes it) — the people on a staff get the role "Obsługa kolejki" (`QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY`, what they could do before; `StaffPermissionMigrationIT`); `party_settings.owner_name` varchar(100), nullable: the organiser's Google name |
| V33 | `party_settings.staff_link_permissions` varchar(200), nullable (null = no link): the invitation link's role; a link made before gets "Obsługa kolejki" (`StaffLinkMigrationIT`) |
| V34 | table `staff_invitation` (id, `party_code` FK ON DELETE CASCADE, `email`, `email_key` varchar(254), `permissions` varchar(200), `invited_at`; UNIQUE `uk_staff_invitation_email` (party, email_key), index `idx_staff_invitation_email_key`): invitations to the staff by e-mail |
| V36 | table `trial_used` (`owner_hash` varchar(64) PK, `used_at` timestamptz NOT NULL DEFAULT now()), filled with the keys of the `dj_plan` rows of source TRIAL: the Google accounts that had a trial, kept after "Usuń konto" (`TrialUsedMigrationIT`, `DjPlanIT`) |
| V37 | `party_settings.host_token_made_at`, `staff_token_made_at` timestamptz (nullable): when the hosts' and the invitation link were made — each works 30 days; a link there at the migration counts from it |
| V38 | table `song_request_dedications` (`id` bigserial PK, `song_request_id` bigint NOT NULL FK → `song_requests` ON DELETE CASCADE, `text` varchar(100) NOT NULL, `checked` boolean NOT NULL, `created_at` timestamptz NOT NULL; index `idx_dedication_request` (song_request_id, id)), `song_requests.dedications` integer NOT NULL DEFAULT 0 and `party_settings.dedications_on` boolean NOT NULL DEFAULT true: the guests' dedications |
| V39 | `song_requests.checked` boolean NOT NULL DEFAULT true; the requests whose comment is the app's note "AI jest chwilowo niedostępne…" / "The AI is unavailable right now…" (the text of `ai.unavailable.to_dj` since 2026-10-01) set to false: a request the AI did not judge stays off the guests' list and the TV screen (`RequestCheckedMigrationIT` reads the note from the bundles) |
| V40 | table `site_counter` (`day` date, `event` varchar(20), `count` integer, PK (day, event)): the site's counter — numbers per day, no personal data (`SiteCounter`, `/dj/stats`) |
| V41 | tables `dj_library` (`party_code` PK, FK ON DELETE CASCADE, `track_count`, `format` varchar(10), `uploaded_at`) and `dj_library_track` (`party_code` FK to `dj_library` ON DELETE CASCADE, `match_key` varchar(200), PK both): the DJ's library — song keys only (`DjLibraryService`) |
| V42 | the DJ's library by title and artists: `dj_library_track` made again (`party_code` FK to `dj_library` ON DELETE CASCADE, `title_key`, `artists` varchar(200), PK all three), `dj_library.playlist` varchar(200); the libraries of V41 deleted (`LibraryTitlesMigrationIT`) |
| V43 | `dj_library_track.swapped` boolean NOT NULL DEFAULT false: the key of an M3U's name read the other way round — "✓" only, never "?" (`DjLibraryIT`) |
| V44 | `song_requests.added_by_dj` boolean NOT NULL DEFAULT false: a song the DJ or the staff added in the panel ("Dodaj piosenkę"; `SongRequestVotesIT`) |
| V45 | table `party_logo` (`party_code` PK, FK ON DELETE CASCADE, `png` bytea, `width`, `height`, `version` varchar(64), `updated_at`) and `party_settings.logo_version` varchar(64), nullable: the DJ's logo (`PartyLogoIT`) |
| V46 | `party_settings.poster_heading` varchar(40), `poster_line` varchar(60), nullable; `poster_language` varchar(4) NOT NULL DEFAULT 'AUTO' (check AUTO / BOTH / PL / EN); `poster_show_logo`, `poster_show_links`, `poster_show_tip`, `poster_show_address` boolean NOT NULL DEFAULT true: the QR print as the DJ sets it (`MigrationIT`) |
| V47 | `party_settings.poster_theme` varchar(10) NOT NULL DEFAULT 'CLASSIC' (check CLASSIC / ELEGANT / CLUB / PASTEL), `poster_accent` varchar(7), nullable (check `^#[0-9a-f]{6}$`): the QR print's look (`MigrationIT`) |
| V48 | `party_settings.owner_email` varchar(320), `created_at` timestamptz DEFAULT now() (the parties of before: null), `welcome_dismissed_at` timestamptz (the parties of before: the migration's time — the welcome card is for a new account): the organiser's address and the panel's welcome (`MigrationIT`, `OrganiserContactMigrationIT`) |
| V49 | the foreign keys on `party_code` (`party_logo`, `party_staff`, `staff_invitation`, `dj_library`, and `dj_library_track` → `dj_library`) recreated with ON UPDATE CASCADE; table `party_code_retired` (`party_code` varchar(5) PK, `retired_at` timestamptz NOT NULL DEFAULT now()): "Zmień kod imprezy" (`MigrationIT`, `PartyCodeChangeIT`) |
| V50 | `party_settings.poster_tables` integer, nullable (check 1 … 50): the QR print's number of tables — the cards and the table tents numbered (`MigrationIT`) |
| V51 | `party_settings.poster_font` varchar(6) NOT NULL DEFAULT 'THEME' (check THEME / SANS / SERIF / SCRIPT): the QR print's heading font (`MigrationIT`) |
| V52 | `party_settings_poster_theme_check` made again with RUSTIC / GALA / NEON / CONFETTI beside V47's four: the QR print's themes (`MigrationIT`) |
| V53 | `party_settings.poster_paper` varchar(7), nullable (check `^#[0-9a-f]{6}$`): the QR print's paper colour (`MigrationIT`) |
| V54 | `party_settings.poster_size` varchar(6) NOT NULL DEFAULT 'NORMAL' (check SMALL / NORMAL / LARGE): the QR print's heading size (`MigrationIT`) |
| V55 | `party_settings.next_open_at`, `next_close_at` timestamptz, nullable; partial indexes `idx_party_settings_next_open_at` / `…_next_close_at` (WHERE … IS NOT NULL): the party opens and closes by itself (`MigrationIT`, `PartyScheduleIT`) |
| V56 | `party_settings.kiosk_token` varchar(32) (unique index `uk_party_settings_kiosk_token`), `song_requests.via_kiosk` boolean NOT NULL DEFAULT false; data: `KIOSK` added to the staff's, the invitation link's and the invitations' permissions that were the whole set before (the co-organisers) — the venue's kiosk (`MigrationIT`, `KioskMigrationIT`) |
| V57 | `dj_library_track.artist_name`, `title_name` varchar(200), `bpm` numeric(5,2), `tonality` varchar(10), `genre` varchar(100), `year` smallint, all nullable; `dj_library.with_details` boolean NOT NULL DEFAULT false: each song of the DJ's library as the file writes it, beside its key — only shown (the queue's tempo and key); the libraries of before stay, without them (`LibraryDetailsMigrationIT`, `DjLibraryIT`) |
| V58 | `party_settings.library_hints` boolean NOT NULL DEFAULT true: the guests' song suggestions the DJ has first, "✓ DJ ma" — "Podpowiedzi gości" in the library's card (`MigrationIT`, `LibraryHintService`) |
| V59 | `party_settings.guest_theme` varchar(10) NOT NULL DEFAULT 'CLASSIC' (check CLASSIC / CHRISTMAS / SCARY / CONFETTI / WEDDING): the look of the guests' pages, "Motyw dla gości" (`MigrationIT`, `GuestThemeTest` — the check is the enum) |
| V63 | table `evening_refusal` (`party_code` FK → `party_settings` ON DELETE / UPDATE CASCADE, `evening` date, `reason` HOSTS_BLOCKED / SKIPPED_BY_DJ / NOT_A_SONG with `evening_refusal_reason_check`, `count`; PK (party_code, evening, reason), index `idx_evening_refusal_evening` for the purge): the requests refused before the AI, counted for the evening summary, 13 months (`EveningSummaryIT`) |
| V64 | the automatic e-mails: `party_settings.welcome_sent_at` timestamptz (the parties of before: the migration's time — they count as welcomed), `email_language` varchar(2), `morning_summary` boolean NOT NULL DEFAULT true, `email_token` varchar(32) NOT NULL DEFAULT 32 hex of `gen_random_uuid()` (unique index — every row, old and new, gets its own), partial index `idx_party_settings_welcome_due` (created_at) WHERE welcome_sent_at IS NULL; `evening_summary.morning_sent_at` timestamptz; index `idx_song_requests_requested_at` (the morning job's day of every party); table `email_status` (one row, id = 1: `last_sent_at`, `last_failed_at`, `last_failure`) (`MigrationIT`, `AutomaticEmailsIT`) |
| V65 | `email_status.admin_report_day` date, nullable: the day of the owner's last daily report, claimed before the send (`AdminReportIT`) |
| V66 | `email_status.admin_report_on` boolean NOT NULL DEFAULT true, `admin_report_time` time NOT NULL DEFAULT '09:10', `admin_report_days` smallint NOT NULL DEFAULT 127 (check 0–127; Monday 1 … Sunday 64): when the owner's report goes (`AdminReportIT`) |
| V67 | `party_settings.booking_email` varchar(254), `booking_phone` varchar(20), nullable: the DJ's contact for bookings, shown under "Zarezerwuj tego DJ-a" (Section 4.1) |
| V68 | "Życzenia przed imprezą": `wish_event` (`id` bigserial, `party_code` FK ON DELETE / UPDATE CASCADE, `token` varchar(32) unique, `name` varchar(80), `event_date` date, `closed`, `wish_count`, `created_at`; indexes `(party_code, event_date)`, `(event_date)`) and `guest_wish` (PK `(event_id, wish_key)`, `wish_key` varchar(401), `song_name` varchar(150), `wishes`, `first_at`, `last_at`; FK ON DELETE CASCADE); data: `WISHES` added to the permissions that were the whole set with `KIOSK` (the co-organisers), as V56 (Section 5.1, `05-1i-wishes.md`) |
| V69 | "Życzenia przed imprezą": `wish_event.guest_theme` varchar(10), NOT NULL DEFAULT 'CLASSIC', CHECK the looks of V59 — the event's guests' page has its own look, not the party's "Motyw dla gości" (a new look goes into both CHECKs) (Section 5.1, `05-1i-wishes.md`) |
| V62 | `song_requests.reject_reason` and `evening_song.reject_reason` varchar(16), null or DUPLICATE / OFF_VIBE (checks `song_requests_reject_reason_check`, `evening_song_reject_reason_check`): why the AI rejected a request, for the evening summary (`EveningSummaryIT`) |
| V61 | tables `evening_song` (what the evening summary keeps of a deleted request: `song_request_id` PK, `party_code` FK → `party_settings` ON DELETE / UPDATE CASCADE, `song_name` nullable, `requested_at` NOT NULL, `played_at`, `status` with a check of the five, `votes`, `tips`, `dedications`, `added_by_dj`, `via_kiosk`; indexes `idx_evening_song_party_time`, `idx_evening_song_time`) and `evening_summary` (`id` bigserial PK, `party_code` FK as above, `evening` date, `title` varchar(60), `share_token` varchar(32) with `uk_evening_summary_share_token`, `share_expires_at`, `updated_at`; `uk_evening_summary_party_evening`) — the summary for 13 months, its title and the hosts' link (`EveningSummaryIT`) |
| V60 | data: `dj_library_track.title_key` with one word's two spellings made one, as `LibraryKey.SPELLINGS` makes the new keys ("ing" → "in", "number" → "no", on the key without spaces); two tracks of a party whose keys became one merged, the smaller key of before kept (`LibraryKeySpellingsMigrationIT`) |
| V35 | table `dj_plan` (`owner_id` varchar(255) PK, `plan` FREE / PRO and `source` TRIAL / PAID / GIFT — check constraints `dj_plan_plan_check`, `dj_plan_source_check` —, `valid_until` timestamptz nullable, `updated_at` NOT NULL DEFAULT now()): a DJ's plan; empty — no row is Free (`MigrationIT`, `DjPlanIT`) |

Checked by `MigrationIT` (`mvnw verify -Pit`, Section 13) on an empty PostgreSQL 18, locally and on GitHub; V16, V18 and V19 also on
rows of the old kind (`VibeMigrationIT`, `SpotifyRemovalMigrationIT`, `YouTubeRemovalMigrationIT`).

**Production (first deploy of the requests-only app: 2026-10-06).** Railway project `celebrated-enjoyment`, environment
`production`: the service `scan2play` builds `main` on every push (Railpack, Java 21, custom domain `www.scan2play.com.pl` behind
Cloudflare) and the service `Postgres` (`postgres-ssl:18`, PostgreSQL 18.6, a 500 MB volume). `main` was fast-forwarded to `dev`
(`b61f4fd`); the start log showed Flyway baselining the old schema as V1 and applying V2..V20 (0.2 s), Hibernate's validation
passing, the app up in ~10 s. V18 / V19 deleted every party of April (only the owner's and friends' tests; no backup was taken, by
the owner's choice). Variables now: `GOOGLE_AI_API_KEY`, `GOOGLE_CLIENT_ID` / `_SECRET`, `GUEST_CLIENT_IP_HEADER`, the five `PG*`,
`DATABASE_URL` (unused); `SCAN2PLAY_GUEST_URL` is not set (the default is the production URL); `YOUTUBE_API_KEY`, `SPOTIFY_*` and
`SPRING_JPA_HIBERNATE_DDL_AUTO` removed. Since: `VAPID_*`, `CSP_ENFORCE`, `ADMIN_GOOGLE_ID` (2026-10-24). No `TZ`: the JVM is UTC.
**Neither service sleeps** (Railway's serverless switched off for `scan2play` and `Postgres`, checked 2026-10-24): a sleeping app
ran no `@Scheduled` job — the nightly clearing and purges (Section 4.1) and the demo's reset (5.5) — and an app that woke before
its database failed once at start. **Healthcheck Path `/privacy`** (2026-10-24): Railway switches traffic to a new deploy only when
it answers 200 there — a static public page, no database, no session, not counted in `site_counter` (`/` would count a visit,
and Railway allows no dot in the path, so not `/robots.txt`). Keep it public and light. **The database** is reachable only on Railway's private network (its public TCP
proxy removed 2026-10-21). After the incident Railway reported on 2026-10-21 the owner replaced the Gemini key, the Google OAuth
secret and the database's password. The workspace has a spending limit of 15 USD a month (an alert at 10 USD). Later deploys: 2026-10-06 the notifications (PR #9, V21) and the
versioned script addresses (PR #10); 2026-10-07 PR #11 via #12 (`a06e4be`: clear the history, the comment style, V22 and V23
applied in 0.05 s, "Push notifications on"). Since:
`CSP_ENFORCE=true` (2026-10-15), Dependabot alerts and security updates on, Railway waits for the CI before it deploys `main`
(`checkSuites: true`, 2026-10-16).

---

