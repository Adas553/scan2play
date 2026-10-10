package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

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

    /**
     * V22: the comment style — a party made before it is classic, and the database takes no style the app does not know (V26: the
     * gentle sarcasm neither).
     */
    @Test
    void aPartyHasACommentStyle_classicByDefault_andOnlyAKnownOne() {
        String code = "V22" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);

        assertThat(jdbc.queryForObject("SELECT comment_style FROM party_settings WHERE party_code = ?", String.class, code))
                .isEqualTo("CLASSIC");
        jdbc.update("UPDATE party_settings SET comment_style = 'SARCASTIC' WHERE party_code = ?", code);
        for (String unknown : List.of("RUDE", "SARCASTIC_LIGHT")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET comment_style = ? WHERE party_code = ?", unknown, code))
                    .hasMessageContaining("party_settings_comment_style_check");
        }
    }

    /** V27: the DJ's tip link — an address, or none. */
    @Test
    void aPartyHasATipLink_noneByDefault() {
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'party_settings' AND column_name = 'tip_url' AND is_nullable = 'YES'", Integer.class))
                .isEqualTo(200);
    }

    /** V29: the hosts' lists (room for 100 lines of 150 characters) and their link's secret — one party per secret. */
    @Test
    void aPartyHasTheHostsLists_andASecretOfItsOwn() {
        for (String column : List.of("host_blocked", "host_wanted")) {
            assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                    + " WHERE table_name = 'party_settings' AND column_name = ? AND is_nullable = 'YES'", Integer.class, column))
                    .as(column).isEqualTo(com.scan2play.util.SongList.TEXT_MAX);
        }
        String a = "V29" + (System.nanoTime() % 100);
        String b = "V2B" + (System.nanoTime() % 100);
        for (String code : List.of(a, b)) {
            jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                    + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        }
        jdbc.update("UPDATE party_settings SET host_token = 'secret-of-a' WHERE party_code = ?", a);

        assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET host_token = 'secret-of-a' WHERE party_code = ?", b))
                .hasMessageContaining("uk_party_settings_host_token");
    }

    /** V30: the party's staff — its table with the unique (party, person), the index by person, and the invitation link's secret. */
    @Test
    void thePartysStaff_hasItsTable_andItsIndexes() {
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains("uk_party_staff_member", "idx_party_staff_member_id", "uk_party_settings_staff_token");
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'party_settings' AND column_name = 'staff_token' AND is_nullable = 'YES'", Integer.class))
                .isEqualTo(32);
    }

    /** V31: no energy rating — the AI is not asked for one, nothing shows it. */
    @Test
    void theRequestsHaveNoEnergyLevel() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'song_requests'"
                + " AND column_name = 'energy_level'", String.class)).isEmpty();
    }

    /** V32: what each person of the staff may do (no default: the app always says it) and the organiser's name. */
    @Test
    void theStaffsPermissions_andTheOrganisersName_haveTheirColumns() {
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'party_staff'"
                + " AND column_name = 'permissions' AND is_nullable = 'NO' AND column_default IS NULL", Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'owner_name' AND is_nullable = 'YES'", Integer.class)).isEqualTo(100);
    }

    @Test
    void theDatabaseIsAThrowAwayOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("s2p_it_");
    }
}
