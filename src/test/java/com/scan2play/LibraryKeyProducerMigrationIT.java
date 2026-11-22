package com.scan2play;

import com.scan2play.util.LibraryKey;
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
 * V79 on a database with libraries uploaded before it: a title's key loses the producer after a dash ("Toketeo - Prod. by Dj Honda x
 * DJ Jerry" is "toketeo") as {@link LibraryKey} makes the new ones — the same key for the same title, checked against the Java
 * itself —; a track whose key becomes one the library has goes, a title that only starts with "prod" and a track without its name
 * (uploaded before V57) stay, the other parties' are cut on their own. A throw-away database of its own, migrated to V78, given
 * libraries, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class LibraryKeyProducerMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_libprod_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theProducerIsCutFromTheKeysOfBefore_andTheirDoublesGo() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("78").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            for (String party : new String[]{"WED01", "WED02"}) {
                statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                        + " duplicate_check_window) VALUES ('" + party + "', 'dj-" + party + "', true, 'ANY', 2, 3, 15)");
                statement.execute("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES ('" + party
                        + "', 8, 'REKORDBOX', now())");
            }
            // the keys as V78 made them
            statement.execute("INSERT INTO dj_library_track (party_code, title_key, artists, title_name) VALUES"
                    // Serato's title, the one found on Windows
                    + " ('WED01', 'toketeoprodbydjhondaxdjjerry', 'bebeshito+djhonda+djjerry', 'Toketeo - Prod. by Dj Honda x DJ Jerry'),"
                    // the song the library has already without the producer: the one with it goes
                    + " ('WED01', 'toketeo', 'bebeshito', 'Toketeo'),"
                    + " ('WED01', 'toketeoproducedbydjhonda', 'bebeshito', 'Toketeo – Produced by Dj Honda'),"
                    // an accent, a version in brackets before the dash
                    + " ('WED01', 'hitprodbysliwa', 'kali', 'Hit (Radio Edit) - Prod. by Śliwa'),"
                    // two producers of one song: the smaller key of before stays
                    + " ('WED01', 'nocprodbya', 'mata', 'Noc - Prod. by A'),"
                    + " ('WED01', 'nocprodbyb', 'mata', 'Noc - Prod by B'),"
                    // a title's own words, a track without its name (uploaded before V57)
                    + " ('WED01', 'songprodigytribute', 'band', 'Song - Prodigy Tribute'),"
                    + " ('WED01', 'oldprodbyx', 'band', NULL),"
                    // the same song at another party, where nothing is in its way
                    + " ('WED02', 'toketeoproducedbydjhonda', 'bebeshito', 'Toketeo – Produced by Dj Honda')");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(keys(statement, "WED01")).containsExactly(
                    "hit|kali|Hit (Radio Edit) - Prod. by Śliwa",
                    "noc|mata|Noc - Prod. by A",
                    "oldprodbyx|band|null",
                    "songprodigytribute|band|Song - Prodigy Tribute",
                    "toketeo|bebeshito|Toketeo",
                    "toketeo|bebeshito+djhonda+djjerry|Toketeo - Prod. by Dj Honda x DJ Jerry");
            assertThat(keys(statement, "WED02")).containsExactly("toketeo|bebeshito|Toketeo – Produced by Dj Honda");
            // the migration cuts them as the Java does
            assertThat(LibraryKey.of("Bebeshito, Dj Honda, DJ Jerry", "Toketeo - Prod. by Dj Honda x DJ Jerry").title()).isEqualTo("toketeo");
            assertThat(LibraryKey.of("Kali", "Hit (Radio Edit) - Prod. by Śliwa").title()).isEqualTo("hit");
            assertThat(LibraryKey.of("Bebeshito", "Toketeo – Produced by Dj Honda").title()).isEqualTo("toketeo");
            assertThat(LibraryKey.of("Band", "Song - Prodigy Tribute").title()).isEqualTo("songprodigytribute");
        }
    }

    private static List<String> keys(Statement statement, String party) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT title_key || '|' || artists || '|' || coalesce(title_name, 'null')"
                + " FROM dj_library_track WHERE party_code = '" + party + "' ORDER BY title_key, artists")) {
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
        }
        return keys;
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
