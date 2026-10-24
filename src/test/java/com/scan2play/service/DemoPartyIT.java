package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo party (DemoParty) on a real PostgreSQL: made at the start, its examples put back by {@link DemoParty#reset} — every
 * visitor's request gone, the numbers from #1 again, the dedications with them —, its party owned by no Google account.
 */
class DemoPartyIT extends PostgresIntegrationTest {

    @Autowired DemoParty demoParty;
    @Autowired JdbcTemplate jdbc;

    private List<String> rows(String code) {
        return jdbc.queryForList("SELECT decision || ' ' || coalesce(request_number::text, '-') || ' ' || checked || ' ' || song_name"
                + " FROM song_requests WHERE party_code = ? ORDER BY requested_at, id", String.class, code);
    }

    @Test
    void theStart_madeTheDemo_andAResetPutsItsExamplesBack_withoutTheVisitors() {
        // ApplicationReadyEvent: the demo is there before anyone asks
        assertThat(jdbc.queryForList("SELECT party_code || ' ' || owner_id || ' ' || vibe_note FROM party_settings"
                + " WHERE party_code IN ('DEMO', 'DEME') ORDER BY party_code", String.class))
                .containsExactly("DEME scan2play-demo-en Wedding, 30+ crowd, dance hits, no metal",
                        "DEMO scan2play-demo Wesele 30+, przeboje do tańca, bez metalu");
        assertThat(rows("DEME")).as("the English demo, its own examples").hasSize(DemoParty.ENGLISH.examples().size())
                .contains("accepted 3 true Whitney Houston - I Wanna Dance with Somebody").noneMatch(row -> row.contains("Wilki"));
        List<String> examples = rows("DEMO");
        assertThat(examples).hasSize(DemoParty.POLISH.examples().size())
                .startsWith("played 1 true Zenek Martyniuk - Przez twe oczy zielone")
                .contains("rejected - true Slayer - Raining Blood", "accepted 3 true Wilki - Baśka", "rejected 5 true Ich Troje - Powiedz");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM song_request_dedications d JOIN song_requests r ON r.id = d.song_request_id"
                + " WHERE r.party_code = 'DEMO'", Integer.class)).isEqualTo(2);

        // a visitor's request, as SongEvaluationService saves it for the demo (unchecked)
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, votes, checked, requested_at, request_number)"
                + " VALUES ('DEMO', 'a visitor''s words', 'accepted', 1, false, now(), 99)");
        demoParty.reset();

        assertThat(rows("DEMO")).as("the same examples, the visitor's request gone").isEqualTo(examples);
        assertThat(jdbc.queryForObject("SELECT request_counter FROM party_settings WHERE party_code = 'DEMO'", Integer.class))
                .as("the next guest's number follows the examples'").isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_settings WHERE party_code = 'DEMO'", Integer.class)).isEqualTo(1);
    }
}
