package com.scan2play;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V39 on a database with requests: the ones that went to the DJ unchecked before it — the app's note in place of the AI's comment, in
 * Polish or in English, read here from the bundles themselves — are unchecked after it; every other request is checked. A throw-away
 * database of its own, migrated to V38, given requests, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class RequestCheckedMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_checked_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theRequestsThatWentUnchecked_areUnchecked_everyOtherIsChecked() throws SQLException, IOException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("38").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             PreparedStatement insert = connection.prepareStatement("INSERT INTO song_requests (party_code, song_name, decision,"
                     + " dj_comment, requested_at) VALUES ('WED01', ?, ?, ?, now())")) {
            Object[][] rows = {
                    {"AI down, Polish", "accepted", unavailable("messages_pl.properties")},
                    {"AI down, English", "played", unavailable("messages.properties")},
                    {"Wilki - Baśka", "accepted", "Klasyk!"},
                    {"No comment", "rejected", null}};
            for (Object[] row : rows) {
                insert.setString(1, (String) row[0]);
                insert.setString(2, (String) row[1]);
                insert.setString(3, (String) row[2]);
                insert.executeUpdate();
            }
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT song_name || ': ' || checked FROM song_requests ORDER BY id")) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
            assertThat(rows).containsExactly("AI down, Polish: false", "AI down, English: false", "Wilki - Baśka: true",
                    "No comment: true");
        }
    }

    /** The note the app saves when the AI could not be asked ({@code SongEvaluationService.withoutTheAi}), as the bundle has it. */
    private static String unavailable(String bundle) throws IOException {
        try (InputStream in = RequestCheckedMigrationIT.class.getResourceAsStream("/" + bundle)) {
            return new PropertyResourceBundle(in).getString("ai.unavailable.to_dj");
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
