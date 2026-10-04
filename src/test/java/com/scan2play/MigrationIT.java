package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

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

        assertThat(indexes).contains("idx_party_decision_time");
        assertThat(indexes).as("duplicates of another index or of a UNIQUE constraint").doesNotContain("idx_owner_id", "idx_party_code");
        assertThat(indexes).as("the player's tables went with V19").noneMatch(index -> index.startsWith("idx_fallback"));
    }

    /** V19: one kind of party — no column of the kind, of Auto-Pilot or of the background playlist, none of the player's tables. */
    @Test
    void thereIsOneKindOfParty_andNoPlayer() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name IN ('active_provider', 'playback_mode', 'fallback_playlist_url', 'fallback_shuffle')", String.class))
                .isEmpty();
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                + " AND table_name IN ('fallback_play', 'fallback_track', 'youtube_cache', 'youtube_search_budget')", String.class))
                .isEmpty();
    }

    /** V14: the guest's own words beside the song the AI made of them — one short line, as the prompt is given it. */
    @Test
    void aSongRequestKeepsTheGuestsWords() {
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'song_requests' AND column_name = 'guest_text' AND is_nullable = 'YES'", Integer.class))
                .isEqualTo(150);
    }

    @Test
    void theDatabaseIsAThrowAwayOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("s2p_it_");
    }
}
