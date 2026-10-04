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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V18 on a database that has parties already: a Spotify party goes with its requests and its DJ's feedback (its DJ logged in with
 * Spotify, a login that no longer exists); the other parties and their requests stay; the token columns are gone and SPOTIFY is no
 * longer allowed. A throw-away database of its own, migrated to V17, given parties the old way, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class SpotifyRemovalMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String PARTY = "INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window, owner_id, party_code,"
            + " request_limit, active_provider, global_vibe, spotify_access_token) VALUES (true, 3, 5, '%s', '%s', 2, '%s', 'ANY', %s)";
    private static final String REQUEST = "INSERT INTO song_requests (party_code, song_name, decision, energy_level, requested_at)"
            + " VALUES ('%s', '%s', 'accepted', 5, now())";
    private static final String FEEDBACK = "INSERT INTO feedback (owner_id, party_code, message, submitted_at)"
            + " VALUES ('%s', '%s', 'hello', now())";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_spotify_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void aSpotifyPartyGoes_theOthersStay_andTheTokensAreGone() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("17").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute(PARTY.formatted("spotify-dj", "SP001", "SPOTIFY", "'enc:v1:token'"));
            statement.execute(PARTY.formatted("google-dj", "YT001", "YOUTUBE", "NULL"));
            statement.execute(PARTY.formatted("google-dj-2", "RQ001", "REQUESTS_ONLY", "NULL"));
            statement.execute(REQUEST.formatted("SP001", "Spotify song"));
            statement.execute(REQUEST.formatted("YT001", "YouTube song"));
            statement.execute(REQUEST.formatted("RQ001", "Requests song"));
            statement.execute(FEEDBACK.formatted("spotify-dj", "SP001"));
            statement.execute(FEEDBACK.formatted("google-dj", "YT001"));
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(column(statement, "SELECT party_code FROM party_settings ORDER BY party_code")).containsExactly("RQ001", "YT001");
            assertThat(column(statement, "SELECT song_name FROM song_requests ORDER BY song_name")).containsExactly("Requests song", "YouTube song");
            assertThat(column(statement, "SELECT owner_id FROM feedback")).containsExactly("google-dj");
            assertThat(column(statement, "SELECT column_name FROM information_schema.columns WHERE table_name = 'party_settings'"
                    + " AND column_name LIKE 'spotify%'")).isEmpty();
            assertThatThrownBy(() -> statement.execute("INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window,"
                    + " owner_id, party_code, request_limit, active_provider, global_vibe) VALUES (true, 3, 5, 'x', 'SP002', 2, 'SPOTIFY', 'ANY')"))
                    .as("SPOTIFY is no longer allowed").isInstanceOf(SQLException.class);
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
