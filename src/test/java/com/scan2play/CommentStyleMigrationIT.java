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
 * V26 on a database with parties: a party with the gentle sarcasm gets the sarcastic style, every other keeps its own. A throw-away
 * database of its own, migrated to V25, given parties, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class CommentStyleMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String PARTY = "INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit,"
            + " cooldown_minutes, duplicate_check_window, comment_style) VALUES ('%s', 'owner-%s', true, 'ANY', 2, 3, 15, '%s')";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_style_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theGentleSarcasmBecomesSarcastic_theOtherStylesStay() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("25").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> styles = List.of("CLASSIC", "FUNNY", "SARCASTIC_LIGHT", "SARCASTIC", "SHORT");
            for (int i = 0; i < styles.size(); i++) {
                String code = "STYL" + i;
                statement.execute(PARTY.formatted(code, code, styles.get(i)));
            }
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> styles = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT party_code || ': ' || comment_style FROM party_settings ORDER BY party_code")) {
                while (rows.next()) {
                    styles.add(rows.getString(1));
                }
            }
            assertThat(styles).containsExactly("STYL0: CLASSIC", "STYL1: FUNNY", "STYL2: SARCASTIC", "STYL3: SARCASTIC",
                    "STYL4: SHORT");
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
