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
 * V32 on a database with a staff: everyone who joined before keeps what the staff could do until then — the role "Obsługa kolejki"
 * ({@code StaffRole.QUEUE}); the parties get no organiser's name until the organiser opens the panel. A throw-away database of its
 * own, migrated to V31, given a staff, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class StaffPermissionMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_staffperm_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theStaffWhoJoinedBefore_keepWhatTheyCouldDo() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("31").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window) VALUES ('PUB01', 'pub-owner', true, 'ANY', 2, 3, 15)");
            statement.execute("INSERT INTO party_staff (party_code, member_id, member_name, joined_at) VALUES"
                    + " ('PUB01', 'kasia', 'Kasia', now()), ('PUB01', 'ola', NULL, now())");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT member_id || ': ' || permissions FROM party_staff ORDER BY member_id")) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
            assertThat(rows).containsExactly("kasia: QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY", "ola: QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY");
            try (ResultSet result = statement.executeQuery("SELECT owner_name FROM party_settings")) {
                result.next();
                assertThat(result.getString(1)).isNull();
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
