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
 * V23 on a database with requests: the fixed English notes a skip and a restore wrote in place of the AI's comment go; every other
 * comment — the AI's own, the note of a cleared queue — stays. A throw-away database of its own, migrated to V22, given requests the
 * old way, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class SkipCommentMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String REQUEST = "INSERT INTO song_requests (party_code, song_name, decision, energy_level, requested_at,"
            + " dj_comment, skipped_at) VALUES ('SK001', '%s', '%s', 5, now(), '%s', %s)";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_skip_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theNotesOfSkipsAndRestoresGo_theAisCommentsAndTheClearedQueuesNoteStay() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("22").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute(REQUEST.formatted("a skipped", "rejected", "Skipped by the DJ ⏭", "now()"));
            statement.execute(REQUEST.formatted("b restored", "accepted", "Restored by the DJ ↩", "null"));
            statement.execute(REQUEST.formatted("c cleared", "rejected", "Cleared by the DJ 🧹", "null"));
            statement.execute(REQUEST.formatted("d the AI", "accepted", "Klasyk wesel!", "null"));
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> comments = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT song_name || ': ' || coalesce(dj_comment, '-') || ' '"
                    + " || (skipped_at IS NOT NULL) FROM song_requests ORDER BY song_name")) {
                while (rows.next()) {
                    comments.add(rows.getString(1));
                }
            }
            assertThat(comments).containsExactly("a skipped: - true", "b restored: - false", "c cleared: Cleared by the DJ 🧹 false",
                    "d the AI: Klasyk wesel! false");
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
