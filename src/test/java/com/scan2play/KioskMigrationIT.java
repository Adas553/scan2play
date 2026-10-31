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
 * V56 on a database with a staff: the new permission KIOSK goes to those who had everything there was to hand over (a co-organiser —
 * their person, their invitation link, their invitation by e-mail); a set the organiser ticked stays as it was. A throw-away database
 * of its own, migrated to V55, given a staff, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class KioskMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String EVERYTHING = "QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY";
    private static final String QUEUE_ROLE = "QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_kiosk_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theCoOrganisers_getTheKiosk_theOthersKeepTheirTicks() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("55").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window, staff_link_permissions) VALUES ('PUB01', 'pub-owner', true, 'ANY', 2, 3, 15, '" + EVERYTHING + "'),"
                    + " ('PUB02', 'bar-owner', true, 'ANY', 2, 3, 15, '" + QUEUE_ROLE + "')");
            statement.execute("INSERT INTO party_staff (party_code, member_id, member_name, joined_at, permissions) VALUES"
                    + " ('PUB01', 'kasia', 'Kasia', now(), '" + EVERYTHING + "'), ('PUB01', 'ola', 'Ola', now(), '" + QUEUE_ROLE + "'),"
                    + " ('PUB01', 'ewa', 'Ewa', now(), 'HISTORY,SUMMARY')");
            statement.execute("INSERT INTO staff_invitation (party_code, email, email_key, permissions, invited_at) VALUES"
                    + " ('PUB01', 'Jan@x.pl', 'jan@x.pl', '" + EVERYTHING + "', now()), ('PUB01', 'ala@x.pl', 'ala@x.pl', 'HISTORY', now())");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(rows(statement, "SELECT member_id || ': ' || permissions FROM party_staff ORDER BY member_id")).containsExactly(
                    "ewa: HISTORY,SUMMARY", "kasia: " + EVERYTHING + ",KIOSK", "ola: " + QUEUE_ROLE);
            assertThat(rows(statement, "SELECT party_code || ': ' || staff_link_permissions FROM party_settings ORDER BY party_code"))
                    .containsExactly("PUB01: " + EVERYTHING + ",KIOSK", "PUB02: " + QUEUE_ROLE);
            assertThat(rows(statement, "SELECT email_key || ': ' || permissions FROM staff_invitation ORDER BY email_key"))
                    .containsExactly("ala@x.pl: HISTORY", "jan@x.pl: " + EVERYTHING + ",KIOSK");
            assertThat(rows(statement, "SELECT coalesce(kiosk_token, 'none') FROM party_settings")).containsExactly("none", "none");
        }
    }

    private static List<String> rows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
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
