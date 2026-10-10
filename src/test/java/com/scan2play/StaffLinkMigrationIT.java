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
 * V33 on a database with invitation links: a link made before gives what it gave until now — the role "Obsługa kolejki"
 * ({@code StaffRole.QUEUE}); a party without a link has no role for one. And V34's invitations by e-mail go with their party. A
 * throw-away database of its own, migrated to V32, given parties, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class StaffLinkMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_stafflink_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void aLinkMadeBefore_givesWhatItGave_andTheInvitationsGoWithTheirParty() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("32").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window, staff_token) VALUES ('PUB01', 'pub-owner', true, 'ANY', 2, 3, 15, 'old-link'),"
                    + " ('BAR01', 'bar-owner', true, 'ANY', 2, 3, 15, NULL)");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT party_code || ': ' || coalesce(staff_link_permissions, '(none)') FROM party_settings ORDER BY party_code")) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
            assertThat(rows).containsExactly("BAR01: (none)", "PUB01: QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY");

            statement.execute("INSERT INTO staff_invitation (party_code, email, email_key, permissions, invited_at)"
                    + " VALUES ('PUB01', 'ola.k@gmail.com', 'olak@gmail.com', 'HISTORY', now())");
            statement.execute("DELETE FROM party_settings WHERE party_code = 'PUB01'");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM staff_invitation")) {
                result.next();
                assertThat(result.getInt(1)).as("ON DELETE CASCADE").isZero();
            }
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
