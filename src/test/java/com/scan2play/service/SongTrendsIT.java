package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Trendy próśb" on a real PostgreSQL: which requests the query counts (what the AI let through, checked, not the DJ's, in the 30
 * days) and in which half. "Now" is far ahead, so no other test's requests fall in the period.
 */
class SongTrendsIT extends PostgresIntegrationTest {

    @Autowired PartySettingsRepository parties;
    @Autowired SongRequestRepository requests;
    @Autowired JdbcTemplate jdbc;

    private static final Instant NOW = Instant.parse("2103-06-01T12:00:00Z");

    private String party() {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("trends-" + System.nanoTime())
                .build()).getPartyCode();
    }

    private void ask(String code, String song, int daysAgo, UnaryOperator<SongRequestEntity.SongRequestEntityBuilder> how) {
        requests.saveAndFlush(how.apply(SongRequestEntity.builder().partyCode(code).songName(song).style("ANY")
                .decision(DjService.DECISION_ACCEPTED).requestedAt(NOW.minus(Duration.ofDays(daysAgo)))).build());
    }

    private void ask(String code, String song, int daysAgo) {
        ask(code, song, daysAgo, b -> b);
    }

    @Test
    void theQuery_countsWhatTheAiLetThrough_byParty_inTheThirtyDays() {
        String a = party();
        String b = party();
        String c = party();
        String d = party();
        // on three parties: a twice (one party), c only in the earlier half
        ask(a, "Wilki - Baśka", 1);
        ask(a, "Wilki - Baśka", 2, r -> r.decision(DjService.DECISION_PLAYED));
        ask(b, "Wilki - Baśka", 3, r -> r.decision(DjService.DECISION_REJECTED).skippedAt(NOW.minus(Duration.ofDays(3))));
        ask(c, "WILKI – BAŚKA", 20, r -> r.decision(DjService.DECISION_REJECTED).clearedAt(NOW.minus(Duration.ofDays(19))));
        // on one party each by what counts — the AI's refusal, unchecked, the DJ's own, older than 30 days
        ask(b, "Lady Pank - Mniej niż zero", 1);
        ask(c, "Lady Pank - Mniej niż zero", 1, r -> r.decision(DjService.DECISION_REJECTED));
        ask(d, "Lady Pank - Mniej niż zero", 1, r -> r.checked(false));
        ask(a, "Lady Pank - Mniej niż zero", 1, r -> r.addedByDj(true));
        ask(d, "Lady Pank - Mniej niż zero", 31);
        // not yet: a request after "now"
        ask(d, "Wilki - Baśka", -1);

        SongTrendsService.Result result = new SongTrendsService(jdbc).trends(NOW);

        assertThat(result.parties()).as("a, b, c — d has nothing that counts").isEqualTo(3);
        assertThat(result.songs()).isEqualTo(1);
        assertThat(result.belowMinimum()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.top()).containsExactly(new SongTrendsService.Trend("Wilki - Baśka", 3, 2, 1, "up"));
    }
}
