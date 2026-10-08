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
 * V28 on a database with requests: the songs that reached the queue (waiting, played, skipped or cleared by the DJ) are numbered in
 * the order asked for, party by party; one the AI rejected gets no number; each party's count goes on from its last number, a party
 * without songs at 0; nobody has tips yet. A throw-away database of its own, migrated to V27, given requests, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class RequestNumberMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String PARTY = "INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit,"
            + " cooldown_minutes, duplicate_check_window) VALUES ('%s', 'owner-%s', true, 'ANY', 2, 3, 15)";
    private static final String REQUEST = "INSERT INTO song_requests (party_code, song_name, decision, energy_level, requested_at,"
            + " skipped_at, cleared_at) VALUES ('%s', '%s', '%s', 5, now() - interval '%d minutes', %s, %s)";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_number_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theSongsInTheQueueAreNumbered_inTheOrderAskedFor_partyByParty() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("27").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            for (String party : List.of("PARTA", "PARTB", "EMPTY")) {
                statement.execute(PARTY.formatted(party, party));
            }
            // asked for 50, 40 … 10 minutes ago: the order of the numbers
            statement.execute(REQUEST.formatted("PARTA", "a1 waiting", "accepted", 50, "null", "null"));
            statement.execute(REQUEST.formatted("PARTA", "a2 rejected by the AI", "rejected", 45, "null", "null"));
            statement.execute(REQUEST.formatted("PARTA", "a3 played", "played", 40, "null", "null"));
            statement.execute(REQUEST.formatted("PARTA", "a4 skipped", "rejected", 30, "now()", "null"));
            statement.execute(REQUEST.formatted("PARTA", "a5 cleared", "rejected", 20, "null", "now()"));
            statement.execute(REQUEST.formatted("PARTB", "b1 waiting", "accepted", 10, "null", "null"));
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(read(statement, "SELECT song_name || ': ' || coalesce(request_number::text, '-') || ' ' || tips"
                    + " FROM song_requests ORDER BY song_name"))
                    .containsExactly("a1 waiting: 1 0", "a2 rejected by the AI: - 0", "a3 played: 2 0", "a4 skipped: 3 0",
                            "a5 cleared: 4 0", "b1 waiting: 1 0");
            assertThat(read(statement, "SELECT party_code || ': ' || request_counter FROM party_settings ORDER BY party_code"))
                    .containsExactly("EMPTY: 0", "PARTA: 4", "PARTB: 1");
        }
    }

    private static List<String> read(Statement statement, String sql) throws SQLException {
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
