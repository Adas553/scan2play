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
 * V37 on a database with links: a hosts' link and an invitation link there before it count their 30 days from the migration — they
 * keep working now; a party without a link gets no time. A throw-away database of its own, migrated to V36, given parties, and then
 * to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class LinkMadeAtMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_linkmadeat_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theLinksThereBefore_countFromTheMigration_aPartyWithoutOneGetsNoTime() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("36").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window, host_token, staff_token) VALUES"
                    + " ('WED01', 'dj-1', true, 'ANY', 2, 3, 15, 'hosts-link', NULL),"
                    + " ('PUB01', 'dj-2', true, 'ANY', 2, 3, 15, NULL, 'staff-link'),"
                    + " ('BAR01', 'dj-3', true, 'ANY', 2, 3, 15, NULL, NULL)");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT party_code || ': '"
                    + " || coalesce((now() - host_token_made_at < interval '1 minute')::text, '-') || ' '"
                    + " || coalesce((now() - staff_token_made_at < interval '1 minute')::text, '-')"
                    + " FROM party_settings ORDER BY party_code")) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
            assertThat(rows).containsExactly("BAR01: - -", "PUB01: - true", "WED01: true -");
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
