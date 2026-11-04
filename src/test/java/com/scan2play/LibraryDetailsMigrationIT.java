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
 * V57 on a database with a library uploaded before it: unlike V42 nothing goes — its keys stay (the queue's marks work as they did),
 * the new columns are null and the library says it was uploaded without them ({@code with_details} false: the settings ask a rekordbox
 * one to be uploaded again). A throw-away database of its own, migrated to V56, given a library, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class LibraryDetailsMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_libdetails_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void aLibraryOfBefore_staysWithItsKeys_markedAsWithoutDetails() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("56").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window) VALUES ('WED01', 'dj-1', true, 'ANY', 2, 3, 15)");
            statement.execute("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES ('WED01', 2, 'REKORDBOX', now())");
            statement.execute("INSERT INTO dj_library_track (party_code, title_key, artists) VALUES ('WED01', 'baska', 'wilki'),"
                    + " ('WED01', 'sos', 'abba')");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM dj_library WHERE party_code = 'WED01' AND NOT with_details")).isEqualTo(1);
            assertThat(count(statement, "SELECT count(*) FROM dj_library_track WHERE party_code = 'WED01' AND artist_name IS NULL"
                    + " AND title_name IS NULL AND bpm IS NULL AND tonality IS NULL AND genre IS NULL AND year IS NULL")).isEqualTo(2);
            // a track as an upload writes it now: its name as written, its tempo with two decimals, its key, genre and year
            statement.execute("UPDATE dj_library SET with_details = true WHERE party_code = 'WED01'");
            statement.execute("INSERT INTO dj_library_track (party_code, title_key, artists, artist_name, title_name, bpm, tonality, genre,"
                    + " year) VALUES ('WED01', 'onemoretime', 'daftpunk', 'Daft Punk', 'One More Time', 122.5, '5A', 'House', 2000)");
            try (ResultSet track = statement.executeQuery("SELECT bpm::text, year FROM dj_library_track WHERE title_key = 'onemoretime'")) {
                track.next();
                assertThat(track.getString(1)).isEqualTo("122.50");
                assertThat(track.getInt(2)).isEqualTo(2000);
            }
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
