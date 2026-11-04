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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V59 on a database with libraries uploaded before it: their titles' keys get the spellings made one ("livingonaprayer" is
 * "livinonaprayer", "mambonumber5" "mambono5") as {@link com.scan2play.util.LibraryKey} makes the new ones — the same key for the same
 * title, checked against the Java itself —; two tracks of one artist whose keys become one are one, the other parties' untouched. A
 * throw-away database of its own, migrated to V58, given libraries, and then to the end.
 */
@ExtendWith(PostgresIntegrationTest.OnlyUnderFailsafe.class)
class LibraryKeySpellingsMigrationIT {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");
    private static String database;

    @BeforeAll
    static void createDatabase() throws SQLException {
        database = "s2p_it_libspell_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
        onServer("CREATE DATABASE " + database);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        onServer("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void theKeysOfBefore_areSpelledAsTheNewOnes_andTheirDoublesMerged() throws SQLException {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
        Flyway.configure().dataSource(url, USER, PASSWORD).target("58").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            for (String party : new String[]{"WED01", "WED02"}) {
                statement.execute("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                        + " duplicate_check_window) VALUES ('" + party + "', 'dj-" + party + "', true, 'ANY', 2, 3, 15)");
                statement.execute("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES ('" + party
                        + "', 4, 'M3U', now())");
            }
            // the keys as V57 made them: "Livin' On A Prayer" and "Living on a Prayer" of one artist, "Mambo Number 5", a title
            // without the words, and the same song at another party
            statement.execute("INSERT INTO dj_library_track (party_code, title_key, artists, title_name) VALUES"
                    + " ('WED01', 'livinonaprayer', 'bonjovi', 'Livin'' On A Prayer'),"
                    + " ('WED01', 'livingonaprayer', 'bonjovi', 'Living on a Prayer'),"
                    + " ('WED01', 'mambonumber5', 'loubega', 'Mambo Number 5'),"
                    + " ('WED01', 'baska', 'wilki', 'Baśka'),"
                    + " ('WED02', 'livingonaprayer', 'bonjovi', 'Living on a Prayer')");
        }

        Flyway.configure().dataSource(url, USER, PASSWORD).load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD); Statement statement = connection.createStatement()) {
            assertThat(keys(statement, "WED01")).containsExactly(
                    // of the two, the one whose key of before was the smaller ("livingonaprayer" < "livinonaprayer")
                    "baska|wilki|Baśka", "livinonaprayer|bonjovi|Living on a Prayer", "mambono5|loubega|Mambo Number 5");
            assertThat(keys(statement, "WED02")).containsExactly("livinonaprayer|bonjovi|Living on a Prayer");
            // the migration spells them as the Java does
            assertThat(com.scan2play.util.LibraryKey.of("Bon Jovi - Living on a Prayer").title()).isEqualTo("livinonaprayer");
            assertThat(com.scan2play.util.LibraryKey.of("Lou Bega - Mambo Number 5").title()).isEqualTo("mambono5");
        }
    }

    private static java.util.List<String> keys(Statement statement, String party) throws SQLException {
        java.util.List<String> keys = new java.util.ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT title_key || '|' || artists || '|' || title_name FROM dj_library_track"
                + " WHERE party_code = '" + party + "' ORDER BY title_key")) {
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
        }
        return keys;
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
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
