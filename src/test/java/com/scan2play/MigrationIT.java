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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                // Ask what the planner CAN do, not what it picks for the rows other tests left (on GitHub it chose another index
                // and a sort of one row): without a sequential scan and a sort, only an index in play order answers the query.
                // Before V9 there was none, so the plan kept its Sort.
                statement.execute("SET LOCAL enable_seqscan = off");
                statement.execute("SET LOCAL enable_sort = off");
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

    /** V13: a party of the kind REQUESTS_ONLY can be stored (the check on the provider column lets it in), a made-up kind cannot. */
    @Test
    void aRequestsOnlyPartyCanBeStored_aMadeUpKindCannot() {
        String insert = "INSERT INTO party_settings (active, cooldown_minutes, duplicate_check_window, owner_id, party_code, request_limit,"
                + " active_provider) VALUES (true, 10, 5, ?, ?, 2, ?)";
        try {
            jdbc.update(insert, "it-requests-owner", "ITRQ1", "REQUESTS_ONLY");
            assertThat(jdbc.queryForObject("SELECT active_provider FROM party_settings WHERE party_code = 'ITRQ1'", String.class))
                    .isEqualTo("REQUESTS_ONLY");
            assertThatThrownBy(() -> jdbc.update(insert, "it-vinyl-owner", "ITRQ2", "VINYL"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM party_settings WHERE party_code IN ('ITRQ1', 'ITRQ2')");
        }
    }

    @Test
    void theDatabaseIsAThrowAwayOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("s2p_it_");
    }
}
