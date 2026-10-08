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

    /** The AI's duplicate rule: the songs that played last, by when they played — not by when a guest asked for them. */
    @Test
    void theRecentlyPlayedSongs_areTheLatestToPlay() {
        String party = newPartyCode();
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS);
        save(party, "asked first, played last", "played", t, t.plus(90, ChronoUnit.MINUTES));
        save(party, "asked later, played early", "played", t.plus(10, ChronoUnit.MINUTES), t.plus(20, ChronoUnit.MINUTES));
        save(party, "played before V6", "played", t.plus(30, ChronoUnit.MINUTES), null);
        save(party, "waiting", "accepted", t.plus(100, ChronoUnit.MINUTES), null);
        save(party, "rejected", "rejected", t.plus(110, ChronoUnit.MINUTES), null);
        save(newPartyCode(), "another party", "played", t, t.plus(115, ChronoUnit.MINUTES));

        assertThat(requests.findRecentlyPlayed(party, PageRequest.of(0, 10))).extracting(SongRequestEntity::getSongName)
                .containsExactly("asked first, played last", "played before V6", "asked later, played early");
        assertThat(requests.findRecentlyPlayed(party, PageRequest.of(0, 1))).extracting(SongRequestEntity::getSongName)
                .containsExactly("asked first, played last");
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

        jdbc.update("UPDATE song_requests SET dj_comment = song_name || '!' WHERE party_code = ?", party);   // the AI's comments
        Instant clearedAt = Instant.parse("2026-10-08T20:00:00Z");

        assertThat(requests.rejectWaiting(party, clearedAt)).isEqualTo(2);

        assertThat(requests.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(party, List.of("accepted"))).isEmpty();
        // the AI's comment stays (V25: no note over it); only the cleared ones are marked
        assertThat(jdbc.queryForList("SELECT song_name || ':' || decision || ':' || coalesce(dj_comment, '') || ':' "
                + "|| coalesce(to_char(cleared_at AT TIME ZONE 'UTC', 'HH24:MI'), '-') FROM song_requests "
                + "WHERE party_code = ? ORDER BY song_name", String.class, party))
                .containsExactly("played:played:played!:-", "rejected by the AI:rejected:rejected by the AI!:-",
                        "waiting one:rejected:waiting one!:20:00", "waiting two:rejected:waiting two!:20:00");
        assertThat(requests.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(other, List.of("accepted")))
                .as("another party's queue is untouched").hasSize(1);
        assertThat(requests.rejectWaiting(party, Instant.now())).as("nothing left to clear").isZero();
    }

    /** "Wyczyść historię": only the party's played and rejected requests; a waiting one and a skip that still blocks its song stay. */
    @Test
    void clearingTheHistoryDeletesOnlyThePartysPastRequests_notASkipThatStillKeepsItsSongOut() {
        String party = newPartyCode();
        String other = newPartyCode();
        Instant now = Instant.now();
        save(party, "played", "played", now.minus(3, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
        save(party, "rejected by the AI", "rejected", now.minus(1, ChronoUnit.HOURS), null);
        save(party, "skipped long ago", "rejected", now.minus(4, ChronoUnit.HOURS), null, now.minus(3, ChronoUnit.HOURS));
        save(party, "skipped just now", "rejected", now.minus(1, ChronoUnit.HOURS), null, now.minus(5, ChronoUnit.MINUTES));
        save(party, "waiting", "accepted", now, null);
        save(other, "another party's", "played", now, now);

        assertThat(requests.deleteHistory(party, now.minus(2, ChronoUnit.HOURS))).isEqualTo(3);

        assertThat(jdbc.queryForList("SELECT song_name FROM song_requests WHERE party_code = ?", String.class, party))
                .containsExactlyInAnyOrder("skipped just now", "waiting");
        assertThat(jdbc.queryForList("SELECT song_name FROM song_requests WHERE party_code = ?", String.class, other))
                .as("another party's history is untouched").containsExactly("another party's");
        assertThat(requests.deleteHistory(party, now.minus(2, ChronoUnit.HOURS))).as("nothing left to clear").isZero();
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
        return save(party, song, decision, requestedAt, playedAt, null);
    }

    private SongRequestEntity save(String party, String song, String decision, Instant requestedAt, Instant playedAt,
                                   Instant skippedAt) {
        return requests.save(SongRequestEntity.builder().partyCode(party).songName(song).style("ANY").decision(decision)
                .requestedAt(requestedAt).playedAt(playedAt).skippedAt(skippedAt).build());
    }
}
