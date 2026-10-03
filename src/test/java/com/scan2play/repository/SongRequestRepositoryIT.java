package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.SongRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guests' requests on a real PostgreSQL: the history's order by the moment of the event ({@code COALESCE} of the play and
 * the request time), the queue's fingerprint and the batched retention purge.
 */
class SongRequestRepositoryIT extends PostgresIntegrationTest {

    @Autowired SongRequestRepository requests;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theHistoryIsOrderedByWhenTheEventHappened() {
        String party = newPartyCode();
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        save(party, "played late", "played", t, t.plus(30, ChronoUnit.MINUTES));
        save(party, "rejected", "rejected", t.plus(20, ChronoUnit.MINUTES), null);
        save(party, "played before V6", "played", t.plus(10, ChronoUnit.MINUTES), null);
        save(party, "still waiting", "accepted", t.plus(40, ChronoUnit.MINUTES), null);
        save(newPartyCode(), "another party", "played", t.plus(50, ChronoUnit.MINUTES), t.plus(50, ChronoUnit.MINUTES));

        assertThat(requests.findHistory(party, List.of("played", "rejected"), PageRequest.of(0, 10)))
                .extracting(SongRequestEntity::getSongName)
                .containsExactly("played late", "rejected", "played before V6");
        assertThat(requests.findHistory(party, List.of("played", "rejected"), PageRequest.of(0, 2))).hasSize(2);
    }

    @Test
    void theFingerprintFollowsTheQueue() {
        String party = newPartyCode();
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("0-0-0");

        SongRequestEntity first = save(party, "one", "accepted", Instant.now(), null);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("1-" + first.getId() + "-1");

        SongRequestEntity second = save(party, "two", "accepted", Instant.now(), null);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("2-" + second.getId() + "-2");
        // a vote changes no row count and no id — the fingerprint still moves, so the DJ's page fetches the queue again
        assertThat(requests.addVote(first.getId())).isEqualTo(1);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("2-" + second.getId() + "-3");
        assertThat(requests.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(party, List.of("accepted")))
                .extracting(SongRequestEntity::getSongName).containsExactly("one", "two");
    }

    @Test
    void clearingTheQueueRejectsOnlyThePartysWaitingRequests() {
        String party = newPartyCode();
        String other = newPartyCode();
        save(party, "waiting one", "accepted", Instant.now(), null);
        save(party, "waiting two", "accepted", Instant.now(), null);
        save(party, "played", "played", Instant.now(), Instant.now());
        save(party, "rejected by the AI", "rejected", Instant.now(), null);
        save(other, "another party's", "accepted", Instant.now(), null);

        assertThat(requests.rejectWaiting(party, "Cleared")).isEqualTo(2);

        assertThat(requests.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(party, List.of("accepted"))).isEmpty();
        assertThat(jdbc.queryForList("SELECT song_name || ':' || decision || ':' || coalesce(dj_comment, '') FROM song_requests "
                + "WHERE party_code = ? ORDER BY song_name", String.class, party))
                .containsExactly("played:played:", "rejected by the AI:rejected:", "waiting one:rejected:Cleared",
                        "waiting two:rejected:Cleared");
        assertThat(requests.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(other, List.of("accepted")))
                .as("another party's queue is untouched").hasSize(1);
        assertThat(requests.rejectWaiting(party, "Cleared")).as("nothing left to clear").isZero();
    }

    @Test
    void thePurgeDeletesOldRequestsInBatches() {
        String party = newPartyCode();
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            save(party, "old " + i, "played", old, old);
        }
        save(party, "fresh", "played", Instant.now(), Instant.now());
        save(party, "no age", "accepted", null, null);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        // other tests' requests are all younger than the cutoff, so every deleted row is one of these
        assertThat(requests.deleteRequestedBefore(cutoff, 2)).isEqualTo(2);
        assertThat(requests.deleteRequestedBefore(cutoff, 2)).isEqualTo(2);
        assertThat(requests.deleteRequestedBefore(cutoff, 2)).isEqualTo(1);
        assertThat(requests.deleteRequestedBefore(cutoff, 2)).isZero();
        assertThat(jdbc.queryForList("SELECT song_name FROM song_requests WHERE party_code = ?", String.class, party))
                .containsExactlyInAnyOrder("fresh", "no age");
    }

    private SongRequestEntity save(String party, String song, String decision, Instant requestedAt, Instant playedAt) {
        return requests.save(SongRequestEntity.builder().partyCode(party).songName(song).style("ANY").decision(decision)
                .requestedAt(requestedAt).playedAt(playedAt).build());
    }
}
