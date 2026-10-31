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

    /** V33: the invitation link's role; V34: the invitations by e-mail — one per address and party, found by the address. */
    @Test
    void theLinksRole_andTheInvitationsByEmail_haveTheirColumnsAndIndexes() {
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'staff_link_permissions' AND is_nullable = 'YES'", Integer.class)).isEqualTo(200);
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains("staff_invitation_pkey", "uk_staff_invitation_email", "idx_staff_invitation_email_key");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'staff_invitation'"
                + " AND is_nullable = 'NO' ORDER BY column_name", String.class))
                .containsExactly("email", "email_key", "id", "invited_at", "party_code", "permissions");
    }

    /** V35: a DJ's plan — one row per organiser, a known plan and source only, an end or none. */
    @Test
    void aDjsPlan_hasItsTable_oneRowPerOrganiser_andOnlyKnownValues() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'dj_plan'"
                + " AND is_nullable = 'NO' ORDER BY column_name", String.class))
                .containsExactly("owner_id", "plan", "source", "updated_at");
        jdbc.update("INSERT INTO dj_plan (owner_id, plan, source) VALUES ('mig-plan-a', 'PRO', 'GIFT')");
        assertThat(jdbc.queryForObject("SELECT valid_until IS NULL AND updated_at IS NOT NULL FROM dj_plan WHERE owner_id = 'mig-plan-a'",
                Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dj_plan (owner_id, plan, source) VALUES ('mig-plan-a', 'FREE', 'PAID')"))
                .as("one row per organiser").hasMessageContaining("dj_plan_pkey");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dj_plan (owner_id, plan, source) VALUES ('mig-plan-b', 'GOLD', 'PAID')"))
                .hasMessageContaining("dj_plan_plan_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dj_plan (owner_id, plan, source) VALUES ('mig-plan-b', 'PRO', 'COUPON')"))
                .hasMessageContaining("dj_plan_source_check");
        jdbc.update("DELETE FROM dj_plan WHERE owner_id LIKE 'mig-plan-%'");
    }

    /** V48: the organiser's address (as long as an address may be), the account's day by the database, the welcome card's close. */
    @Test
    void theOrganisersAddress_theAccountsDay_andTheWelcome_haveTheirColumns() {
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'owner_email' AND is_nullable = 'YES'", Integer.class)).isEqualTo(320);
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'created_at' AND is_nullable = 'YES' AND data_type = 'timestamp with time zone'", String.class))
                .isEqualTo("now()");
        assertThat(jdbc.queryForObject("SELECT column_default IS NULL FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'welcome_dismissed_at' AND is_nullable = 'YES' AND data_type = 'timestamp with time zone'",
                Boolean.class)).isTrue();
    }

    /** V49: every foreign key on a party's code follows a new code (ON UPDATE CASCADE); the retired codes have their table. */
    @Test
    void thePartysCode_canChange_andTheOldCodesAreKept() {
        assertThat(jdbc.queryForList("SELECT conname || ' ' || confupdtype::text FROM pg_constraint WHERE contype = 'f'"
                + " AND conname IN ('fk_party_logo_party', 'fk_party_staff_party', 'fk_staff_invitation_party', 'fk_dj_library_party',"
                + " 'fk_dj_library_track_library') ORDER BY conname", String.class))
                .containsExactly("fk_dj_library_party c", "fk_dj_library_track_library c", "fk_party_logo_party c",
                        "fk_party_staff_party c", "fk_staff_invitation_party c");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'party_code_retired'"
                + " AND is_nullable = 'NO' ORDER BY column_name", String.class)).containsExactly("party_code", "retired_at");
    }

    /** V50: the QR print's number of tables — none, or 1 … 50 (the editor's "Liczba stołów"). */
    @Test
    void theQrPrint_hasANumberOfTables_noneOrOneTo50() {
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name = 'poster_tables' AND is_nullable = 'YES' AND column_default IS NULL", String.class)).isEqualTo("integer");
        String code = "V50" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForObject("SELECT poster_tables FROM party_settings WHERE party_code = ?", Integer.class, code)).isNull();
        for (int tables : new int[] {1, 50}) {
            jdbc.update("UPDATE party_settings SET poster_tables = ? WHERE party_code = ?", tables, code);
        }
        for (int wrong : new int[] {0, 51, -1}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET poster_tables = ? WHERE party_code = ?", wrong, code))
                    .hasMessageContaining("party_settings_poster_tables_check");
        }
    }

    /** V53: the QR print's paper as the DJ picked it — none (the theme's), or a colour "#rrggbb". */
    @Test
    void theQrPrint_hasAPaperColour_noneOrAColour() {
        String code = "V53" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForObject("SELECT poster_paper FROM party_settings WHERE party_code = ?", String.class, code)).isNull();
        jdbc.update("UPDATE party_settings SET poster_paper = '#fbf3e2' WHERE party_code = ?", code);
        assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET poster_paper = 'red' WHERE party_code = ?", code))
                .hasMessageContaining("party_settings_poster_paper_check");
    }

    /** V52: the QR print's themes — the four of V47 and four more; still only a known one. */
    @Test
    void theQrPrint_hasEightThemes_andOnlyThem() {
        String code = "V52" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        for (String theme : List.of("RUSTIC", "GALA", "NEON", "CONFETTI", "CLASSIC", "ELEGANT", "CLUB", "PASTEL")) {
            jdbc.update("UPDATE party_settings SET poster_theme = ? WHERE party_code = ?", theme, code);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET poster_theme = 'WINTER' WHERE party_code = ?", code))
                .hasMessageContaining("party_settings_poster_theme_check");
    }

    /** V51: the QR print's heading font — the theme's by default, and only a known one. */
    @Test
    void theQrPrint_hasAHeadingFont_theThemesByDefault() {
        String code = "V51" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForObject("SELECT poster_font FROM party_settings WHERE party_code = ?", String.class, code)).isEqualTo("THEME");
        for (String font : List.of("SANS", "SERIF", "SCRIPT", "THEME")) {
            jdbc.update("UPDATE party_settings SET poster_font = ? WHERE party_code = ?", font, code);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET poster_font = 'COMIC' WHERE party_code = ?", code))
                .hasMessageContaining("party_settings_poster_font_check");
    }

    /** V54: the QR print's heading size — as the theme has it by default, and only a known one. */
    @Test
    void theQrPrint_hasAHeadingSize_asItIsByDefault() {
        String code = "V54" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForObject("SELECT poster_size FROM party_settings WHERE party_code = ?", String.class, code)).isEqualTo("NORMAL");
        for (String size : List.of("SMALL", "LARGE", "NORMAL")) {
            jdbc.update("UPDATE party_settings SET poster_size = ? WHERE party_code = ?", size, code);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE party_settings SET poster_size = 'HUGE' WHERE party_code = ?", code))
                .hasMessageContaining("party_settings_poster_size_check");
    }

    /**
     * V55: when the party opens and closes by itself — two moments, none by default, each with a partial index the job of every minute
     * finds the due ones by (nearly every party has none). The planner uses them when it may not read the whole table.
     */
    @Test
    void aParty_mayOpenAndCloseByItself_andTheJobFindsItByIndex() {
        String code = "V55" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForMap("SELECT next_open_at, next_close_at FROM party_settings WHERE party_code = ?", code))
                .containsEntry("next_open_at", null).containsEntry("next_close_at", null);
        assertThat(jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_name = 'party_settings'"
                + " AND column_name IN ('next_open_at', 'next_close_at') AND is_nullable = 'YES'", String.class))
                .containsExactly("timestamp with time zone", "timestamp with time zone");
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'party_settings'"
                + " AND indexname IN ('idx_party_settings_next_open_at', 'idx_party_settings_next_close_at') ORDER BY indexname", String.class))
                .hasSize(2).allSatisfy(def -> assertThat(def).containsPattern("WHERE \\(next_(open|close)_at IS NOT NULL\\)"));

        String plan = String.join("\n", jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (java.sql.ResultSet rows = statement.executeQuery("EXPLAIN SELECT id FROM party_settings"
                        + " WHERE next_open_at <= now() OR next_close_at <= now()")) {
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    while (rows.next()) {
                        lines.add(rows.getString(1));
                    }
                    statement.execute("RESET enable_seqscan");
                    return lines;
                }
            }
        }));
        assertThat(plan).contains("idx_party_settings_next_open_at", "idx_party_settings_next_close_at");
    }

    /** V56: the venue's kiosk — a link's secret, unique, none by default; a request from it marked, none by default. */
    @Test
    void aParty_mayHaveAKiosk_andItsRequestsAreMarked() {
        String code = "V56" + (System.nanoTime() % 100);
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", code, "owner-" + code);
        assertThat(jdbc.queryForObject("SELECT kiosk_token FROM party_settings WHERE party_code = ?", String.class, code)).isNull();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE indexname = 'uk_party_settings_kiosk_token'"
                + " AND indexdef LIKE 'CREATE UNIQUE INDEX%'", String.class)).hasSize(1);
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, requested_at) VALUES (?, 'Wilki - Baśka', 'accepted', now())", code);
        assertThat(jdbc.queryForObject("SELECT via_kiosk FROM song_requests WHERE party_code = ?", Boolean.class, code)).isFalse();
    }

    @Test
    void theDatabaseIsAThrowAwayOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("s2p_it_");
    }
}
