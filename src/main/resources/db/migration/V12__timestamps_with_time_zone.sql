-- Every moment as timestamptz (review 1.8): the code keeps them as Instant, the pages show them in Polish time (Times.java).
-- Before, LocalDateTime.now() wrote the JVM's wall-clock time into "timestamp without time zone": Polish time on the developer's
-- machine, UTC on Railway — and the hour that repeats when the clocks go back in October could reorder the history and ⏮.
--
-- The old values are read in the zone they were written in. The JDBC driver sets the session's TimeZone to the JVM's zone, and
-- this migration runs in the application that wrote them, so current_setting('TimeZone') is that zone (Europe/Warsaw locally,
-- UTC on Railway). A value of the hour that repeats is ambiguous; PostgreSQL takes it as standard time.

ALTER TABLE song_requests
    ALTER COLUMN requested_at TYPE timestamptz(6) USING requested_at AT TIME ZONE current_setting('TimeZone'),
    ALTER COLUMN played_at    TYPE timestamptz(6) USING played_at    AT TIME ZONE current_setting('TimeZone');

ALTER TABLE fallback_track
    ALTER COLUMN fetched_at TYPE timestamptz(6) USING fetched_at AT TIME ZONE current_setting('TimeZone'),
    ALTER COLUMN played_at  TYPE timestamptz(6) USING played_at  AT TIME ZONE current_setting('TimeZone');

ALTER TABLE fallback_play
    ALTER COLUMN fetched_at TYPE timestamptz(6) USING fetched_at AT TIME ZONE current_setting('TimeZone'),
    ALTER COLUMN played_at  TYPE timestamptz(6) USING played_at  AT TIME ZONE current_setting('TimeZone');

ALTER TABLE feedback
    ALTER COLUMN submitted_at TYPE timestamptz(6) USING submitted_at AT TIME ZONE current_setting('TimeZone');

ALTER TABLE party_settings
    ALTER COLUMN spotify_token_expires_at TYPE timestamptz(6)
        USING spotify_token_expires_at AT TIME ZONE current_setting('TimeZone');

ALTER TABLE youtube_cache
    ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE current_setting('TimeZone');
