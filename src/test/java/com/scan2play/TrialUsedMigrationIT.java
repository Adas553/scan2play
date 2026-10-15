package com.scan2play;

import com.scan2play.service.PlanService;
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
 * V36 on a database with plans: every DJ who had a trial before it is remembered in {@code trial_used} by the key
 * {@link PlanService#trialKey} computes (a paid or given plan is not a trial), so deleting the account after V36 gives them no second
 * one either. A throw-away database of its own, migrated to V35, given plans, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class TrialUsedMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_trialused_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theTrialsBefore_areRemembered_byTheJavaKey() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("35").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO dj_plan (owner_id, plan, source, valid_until) VALUES"
                    + " ('109876543210987654321', 'PRO', 'TRIAL', now() - interval '3 days'),"
                    + " ('paid-dj', 'PRO', 'PAID', NULL)");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> keys = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT owner_hash FROM trial_used")) {
                while (result.next()) {
                    keys.add(result.getString(1));
                }
            }
            assertThat(keys).containsExactly(PlanService.trialKey("109876543210987654321"));
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
