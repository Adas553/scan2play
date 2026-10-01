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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review item 1.8: {@code V12} turns every "timestamp without time zone" into {@code timestamptz}. The old values were the JVM's
 * wall-clock time (Polish time on the developer's machine, UTC on Railway); the migration must read them in that zone — the
 * session's {@code TimeZone}, which the JDBC driver sets to the JVM's zone — so the moment stays the same. Run on a database of
 * its own, migrated to V11, given rows the old way, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class TimestampMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_tz_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theOldWallClockTimes_becomeTheSameMoments() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("11").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            // what LocalDateTime.now() wrote at 20:05:30 on the JVM's clock
            statement.execute("INSERT INTO song_requests (party_code, energy_level, requested_at, played_at) "
                    + "VALUES ('TZ001', 5, '2026-09-29 20:05:30', '2026-09-29 20:09:00')");
            statement.execute("INSERT INTO feedback (party_code, owner_id, message, submitted_at) "
                    + "VALUES ('TZ001', 'owner', 'hi', '2026-01-15 08:00:00')");   // winter time
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        ZoneId jvm = ZoneId.systemDefault();   // the zone the driver gives the session, and the one the old values were written in
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT requested_at, played_at, (SELECT submitted_at FROM feedback LIMIT 1), "
                     + "(SELECT data_type FROM information_schema.columns WHERE table_name = 'song_requests' AND column_name = "
                     + "'requested_at') FROM song_requests")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject(1, java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(LocalDateTime.of(2026, 9, 29, 20, 5, 30).atZone(jvm).toInstant());
            assertThat(row.getObject(2, java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(LocalDateTime.of(2026, 9, 29, 20, 9).atZone(jvm).toInstant());
            assertThat(row.getObject(3, java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(LocalDateTime.of(2026, 1, 15, 8, 0).atZone(jvm).toInstant());
            assertThat(row.getString(4)).isEqualTo("timestamp with time zone");
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
