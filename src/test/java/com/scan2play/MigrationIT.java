package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations on an empty PostgreSQL: the application context of {@link PostgresIntegrationTest} starts only when Flyway has
 * applied every {@code db/migration/V*.sql} and Hibernate has validated the entities against the result
 * ({@code ddl-auto=validate}), so this class mostly checks that nothing was skipped.
 */
class MigrationIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyMigrationIsAppliedOnAnEmptyDatabase() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        List<String> versions = Arrays.stream(files)
                .map(file -> file.getFilename().substring(1, file.getFilename().indexOf("__")))
                .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
                .toList();

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions).as("migration files").isNotEmpty();
        assertThat(applied).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
    }

    @Test
    void theIndexesAreTheOnesTheQueriesNeed() {
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains("idx_fallback_track_queue", "idx_fallback_track_party_fetched", "idx_party_decision_time",
                "idx_fallback_play_party_played");
        assertThat(indexes).as("duplicates of another index or of a UNIQUE constraint")
                .doesNotContain("idx_fallback_track_party_status", "idx_owner_id", "idx_party_code");
    }

    /** What plays next — the query of every hand-out and of the "up next" list — is read in order from the index, no sort. */
    @Test
    void theNextTrackIsReadFromTheIndexInPlayOrder() {
        List<String> plan = jdbc.execute((Connection connection) -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");   // the table is empty; ask what the planner can do
                ResultSet rows = statement.executeQuery("EXPLAIN SELECT * FROM fallback_track WHERE party_code = 'ABCDE' "
                        + "AND playlist_id = 'PL1' AND status = 'QUEUED' ORDER BY play_order, playlist_position LIMIT 1");
                List<String> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1));
                }
                return lines;
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        });

        assertThat(String.join("\n", plan)).contains("idx_fallback_track_queue").doesNotContain("Sort");
    }

    @Test
    void theDatabaseIsAThrowAwayOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("s2p_it_");
    }
}
