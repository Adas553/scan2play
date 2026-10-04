package com.scan2play;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19 on a database that has parties already: a YouTube party — and a party with no kind, which counted as one — goes with its
 * requests and its DJ's feedback; a requests-only party and its requests stay; the player's tables and the columns of the kind,
 * Auto-Pilot and the background playlist are gone. A throw-away database of its own, migrated to V18, given parties the old way, and
 * then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class YouTubeRemovalMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String PARTY = "INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window, owner_id, party_code,"
            + " request_limit, active_provider, global_vibe, fallback_playlist_url) VALUES (true, 3, 5, '%s', '%s', 2, %s, 'ANY', %s)";
    private static final String REQUEST = "INSERT INTO song_requests (party_code, song_name, decision, energy_level, requested_at)"
            + " VALUES ('%s', '%s', 'accepted', 5, now())";
    private static final String FEEDBACK = "INSERT INTO feedback (owner_id, party_code, message, submitted_at)"
            + " VALUES ('%s', '%s', 'hello', now())";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_youtube_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void aYouTubePartyGoes_theRequestsOnlyOneStays_andThePlayersTablesAreGone() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("18").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute(PARTY.formatted("youtube-dj", "YT001", "'YOUTUBE'", "'https://www.youtube.com/playlist?list=PLx'"));
            statement.execute(PARTY.formatted("old-dj", "OL001", "NULL", "NULL"));
            statement.execute(PARTY.formatted("requests-dj", "RQ001", "'REQUESTS_ONLY'", "NULL"));
            statement.execute(REQUEST.formatted("YT001", "YouTube song"));
            statement.execute(REQUEST.formatted("OL001", "Old song"));
            statement.execute(REQUEST.formatted("RQ001", "Requests song"));
            statement.execute(FEEDBACK.formatted("youtube-dj", "YT001"));
            statement.execute(FEEDBACK.formatted("requests-dj", "RQ001"));
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(column(statement, "SELECT party_code FROM party_settings")).containsExactly("RQ001");
            assertThat(column(statement, "SELECT song_name FROM song_requests")).containsExactly("Requests song");
            assertThat(column(statement, "SELECT owner_id FROM feedback")).containsExactly("requests-dj");
            assertThat(column(statement, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                    + " AND table_name IN ('fallback_play', 'fallback_track', 'youtube_cache', 'youtube_search_budget')")).isEmpty();
            assertThat(column(statement, "SELECT column_name FROM information_schema.columns WHERE table_name = 'party_settings'"
                    + " AND column_name IN ('active_provider', 'playback_mode', 'fallback_playlist_url', 'fallback_shuffle')")).isEmpty();
            // a new party needs none of them
            statement.execute("INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window, owner_id, party_code,"
                    + " request_limit, global_vibe) VALUES (true, 3, 5, 'new-dj', 'NW001', 2, 'ANY')");
        }
    }

    private static List<String> column(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static void onServer(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/postgres", USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
