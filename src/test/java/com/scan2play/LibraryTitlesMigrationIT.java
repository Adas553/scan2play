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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V42 on a database with a library of V41: its keys cannot be made into the title and artists of now (only one key per track was
 * kept), so it goes — the party stays and its DJ uploads the file again; the tracks' table has the new columns. A throw-away database
 * of its own, migrated to V41, given a library, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class LibraryTitlesMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_libtitles_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theLibrariesOfV41_go_thePartiesStay() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("41").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window) VALUES ('WED01', 'dj-1', true, 'ANY', 2, 3, 15)");
            statement.execute("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES ('WED01', 2, 'M3U', now())");
            statement.execute("INSERT INTO dj_library_track (party_code, match_key) VALUES ('WED01', 'wilkibaska'), ('WED01', 'abbasos')");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM dj_library")).isZero();
            assertThat(count(statement, "SELECT count(*) FROM dj_library_track")).isZero();
            assertThat(count(statement, "SELECT count(*) FROM party_settings WHERE party_code = 'WED01'")).isEqualTo(1);
            statement.execute("INSERT INTO dj_library (party_code, track_count, format, uploaded_at, playlist)"
                    + " VALUES ('WED01', 1, 'REKORDBOX', now(), 'Wesele')");
            statement.execute("INSERT INTO dj_library_track (party_code, title_key, artists) VALUES ('WED01', 'baska', 'wilki')");
            assertThat(count(statement, "SELECT count(*) FROM dj_library_track WHERE title_key = 'baska'")).isEqualTo(1);
        }
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
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
