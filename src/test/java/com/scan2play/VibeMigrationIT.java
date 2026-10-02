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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16 on a database that has parties already: the three vibes it merges (bachata, salsa, reggaeton) become LATINO, the others stay,
 * the new ones are allowed and the old ones no longer, and the vibe note's column is there. A throw-away database of its own,
 * migrated to V15, given parties the old way, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class VibeMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static final String INSERT = "INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window, owner_id, party_code,"
            + " request_limit, active_provider, global_vibe) VALUES (true, 3, 5, '%s', '%s', 2, 'YOUTUBE', '%s')";
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_vibe_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theMergedVibesBecomeLatino_theOthersStay_andTheNoteHasItsColumn() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("15").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute(INSERT.formatted("o1", "VB001", "SALSA_AND_TIMBA"));
            statement.execute(INSERT.formatted("o2", "VB002", "BACHATA_AND_KIZOMBA"));
            statement.execute(INSERT.formatted("o3", "VB003", "REGGAETON_AND_DANCEHALL"));
            statement.execute(INSERT.formatted("o4", "VB004", "JAZZ"));
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            List<String> vibes = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT global_vibe FROM party_settings ORDER BY party_code")) {
                while (rows.next()) {
                    vibes.add(rows.getString(1));
                }
            }
            assertThat(vibes).containsExactly("LATINO", "LATINO", "LATINO", "JAZZ");

            statement.execute(INSERT.formatted("o5", "VB005", "KIDS"));
            statement.execute(INSERT.formatted("o6", "VB006", "POLISH_HITS"));
            assertThatThrownBy(() -> statement.execute(INSERT.formatted("o7", "VB007", "SALSA_AND_TIMBA")))
                    .as("an old vibe is no longer allowed").isInstanceOf(SQLException.class);

            try (ResultSet column = statement.executeQuery("SELECT character_maximum_length FROM information_schema.columns"
                    + " WHERE table_name = 'party_settings' AND column_name = 'vibe_note'")) {
                assertThat(column.next()).isTrue();
                assertThat(column.getInt(1)).isEqualTo(150);
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
