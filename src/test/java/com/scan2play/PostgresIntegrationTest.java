package com.scan2play;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The base of the tests that need a real PostgreSQL ({@code *IT}, run by {@code mvnw verify -Pit}; not by {@code mvnw test}).
 * <p>
 * The first such test of a run creates a throw-away database {@code s2p_it_<time>_<random>} on the server named by the usual
 * {@code PGHOST}, {@code PGPORT}, {@code PGUSER}, {@code PGPASSWORD} (the defaults of {@code application.properties}: the local
 * server, user {@code postgres}), points the whole application at it — so Flyway runs every migration on an empty database and
 * Hibernate validates the result — and drops it when the JVM ends. {@code PGDATABASE} is ignored on purpose: these tests never
 * open the developer's own database. Every test class shares that one database and one Spring context (same configuration);
 * tests keep apart by using their own party codes.
 * <p>
 * The external services get dummy credentials: nothing here calls Gemini, Spotify, Google login or the YouTube API.
 */
@SpringBootTest(properties = {
        "GOOGLE_AI_API_KEY=it-dummy",
        "SPOTIFY_CLIENT_ID=it-dummy",
        "SPOTIFY_CLIENT_SECRET=it-dummy",
        "GOOGLE_CLIENT_ID=it-dummy",
        "GOOGLE_CLIENT_SECRET=it-dummy",
        "youtube.api-key=",   // even if the machine has a real key in YOUTUBE_API_KEY
        // the statement counts of the import and of the version read (PostgresStatistics)
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
public abstract class PostgresIntegrationTest {

    private static final String HOST = env("PGHOST", "localhost");
    private static final String PORT = env("PGPORT", "5432");
    private static final String USER = env("PGUSER", "postgres");
    private static final String PASSWORD = env("PGPASSWORD", "1111");

    private static String database;

    @DynamicPropertySource
    static void throwAwayDatabase(DynamicPropertyRegistry registry) {
        String name = createDatabaseOnce();
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + HOST + ":" + PORT + "/" + name);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static synchronized String createDatabaseOnce() {
        if (database == null) {
            String name = "s2p_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    + "_" + ThreadLocalRandom.current().nextInt(1000, 10000);
            execute("CREATE DATABASE " + name);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)")));
            database = name;
        }
        return database;
    }

    /** A statement on the server's maintenance database {@code postgres} (CREATE / DROP DATABASE cannot run inside another). */
    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/postgres", USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL at " + HOST + ":" + PORT + " as " + USER + ": " + sql, e);
        }
    }

    /** A party code no other test uses (5 characters, like the real ones), so that tests sharing the database keep apart. */
    protected static String newPartyCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            code.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
        }
        return code.toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
