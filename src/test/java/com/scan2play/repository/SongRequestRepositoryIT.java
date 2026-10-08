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
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("0-0-0-0");

        SongRequestEntity first = save(party, "one", "accepted", Instant.now(), null);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("1-" + first.getId() + "-1-0");

        SongRequestEntity second = save(party, "two", "accepted", Instant.now(), null);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("2-" + second.getId() + "-2-0");
        // a vote changes no row count and no id — the fingerprint still moves, so the DJ's page fetches the queue again
        assertThat(requests.addVote(first.getId())).isEqualTo(1);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("2-" + second.getId() + "-3-0");
        // a tip the DJ counted (V28) moves it too: every window of the DJ shows the new count
        jdbc.update("UPDATE song_requests SET request_number = 1 WHERE id = ?", first.getId());
        assertThat(requests.addTip(first.getId(), party)).isEqualTo(1);
        assertThat(requests.computeFingerprint(party, List.of("accepted"))).isEqualTo("2-" + second.getId() + "-3-1");
        assertThat(requests.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(party, List.of("accepted")))
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

        assertThat(requests.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(party, List.of("accepted"))).isEmpty();
        // the AI's comment stays (V25: no note over it); only the cleared ones are marked
        assertThat(jdbc.queryForList("SELECT song_name || ':' || decision || ':' || coalesce(dj_comment, '') || ':' "
                + "|| coalesce(to_char(cleared_at AT TIME ZONE 'UTC', 'HH24:MI'), '-') FROM song_requests "
                + "WHERE party_code = ? ORDER BY song_name", String.class, party))
                .containsExactly("played:played:played!:-", "rejected by the AI:rejected:rejected by the AI!:-",
                        "waiting one:rejected:waiting one!:20:00", "waiting two:rejected:waiting two!:20:00");
        assertThat(requests.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(other, List.of("accepted")))
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

    /**
     * A guest's 👍 (GuestVoteService): only on a song of that party that still waits — the id comes from the guest —, and taking it
     * back never goes below the first guest's own vote.
     */
    @Test
    void aGuestsVote_countsOnlyOnTheirPartysWaitingSong_andTakingItBackStopsAtOne() {
        String party = newPartyCode();
        Long waiting = save(party, "waiting", "accepted", Instant.now(), null).getId();
        Long played = save(party, "played", "played", Instant.now(), Instant.now()).getId();
        Long elsewhere = save(newPartyCode(), "another party's", "accepted", Instant.now(), null).getId();
        java.util.function.Function<Long, Integer> votes = id ->
                jdbc.queryForObject("SELECT votes FROM song_requests WHERE id = ?", Integer.class, id);

        assertThat(requests.addGuestVote(waiting, party)).isEqualTo(1);
        assertThat(requests.addGuestVote(waiting, party)).isEqualTo(1);
        assertThat(requests.addGuestVote(played, party)).as("played: no longer waits").isZero();
        assertThat(requests.addGuestVote(elsewhere, party)).as("another party's song, by its id").isZero();
        assertThat(List.of(votes.apply(waiting), votes.apply(played), votes.apply(elsewhere))).containsExactly(3, 1, 1);

        assertThat(requests.removeGuestVote(waiting, party)).isEqualTo(1);
        assertThat(requests.removeGuestVote(waiting, party)).isEqualTo(1);
        assertThat(requests.removeGuestVote(waiting, party)).as("the first guest's own vote stays").isZero();
        assertThat(requests.removeGuestVote(elsewhere, party)).isZero();
        assertThat(votes.apply(waiting)).isEqualTo(1);
    }

    /** A room of guests tapping 👍 on one song at the same moment: every vote counted, none lost (one atomic UPDATE each). */
    @Test
    void guestsVotingAtTheSameMoment_eachVoteCounts() throws Exception {
        String party = newPartyCode();
        Long song = save(party, "the hit", "accepted", Instant.now(), null).getId();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> votes = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            votes.add(pool.submit(() -> {
                start.await();
                return requests.addGuestVote(song, party);
            }));
        }
        start.countDown();
        int counted = 0;
        for (java.util.concurrent.Future<Integer> vote : votes) {
            counted += vote.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(counted).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT votes FROM song_requests WHERE id = ?", Integer.class, song)).isEqualTo(41);
    }

    /**
     * The DJ's tips (V28): one more or one less with one statement, only on the party's own song with a number, never below 0.
     */
    @Test
    void aTip_isCountedOnlyOnThePartysNumberedSong_andNeverGoesBelowZero() {
        String party = newPartyCode();
        SongRequestEntity numbered = save(party, "numbered", "played", Instant.now(), Instant.now());
        jdbc.update("UPDATE song_requests SET request_number = 7 WHERE id = ?", numbered.getId());
        SongRequestEntity rejected = save(party, "rejected by the AI", "rejected", Instant.now(), null);

        assertThat(requests.addTip(numbered.getId(), party)).isEqualTo(1);
        assertThat(requests.addTip(numbered.getId(), party)).isEqualTo(1);
        assertThat(requests.addTip(numbered.getId(), newPartyCode())).as("another party's DJ").isZero();
        assertThat(requests.addTip(rejected.getId(), party)).as("no number").isZero();
        assertThat(requests.removeTip(numbered.getId(), party)).isEqualTo(1);
        assertThat(requests.removeTip(numbered.getId(), party)).isEqualTo(1);
        assertThat(requests.removeTip(numbered.getId(), party)).as("none left").isZero();
        assertThat(jdbc.queryForObject("SELECT tips FROM song_requests WHERE id = ?", Integer.class, numbered.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT tips FROM song_requests WHERE id = ?", Integer.class, rejected.getId())).isZero();
    }

    /**
     * The evening summary: an evening is a day in Polish time from 6:00 to 6:00 — a request at 01:30 is the evening before's, one at
     * 06:00 the next day's —, the latest first, bounded; the evening's requests in the order asked, every decision, only the party's.
     */
    @Test
    void theEvenings_runFromSixToSix_inPolishTime() {
        String party = newPartyCode();
        java.time.ZoneId warsaw = java.time.ZoneId.of("Europe/Warsaw");
        Instant saturday20 = java.time.LocalDateTime.of(2026, 10, 3, 20, 0).atZone(warsaw).toInstant();
        Instant sunday0130 = java.time.LocalDateTime.of(2026, 10, 4, 1, 30).atZone(warsaw).toInstant();
        Instant sunday0559 = java.time.LocalDateTime.of(2026, 10, 4, 5, 59).atZone(warsaw).toInstant();
        Instant sunday0600 = java.time.LocalDateTime.of(2026, 10, 4, 6, 0).atZone(warsaw).toInstant();
        Instant friday23 = java.time.LocalDateTime.of(2026, 9, 25, 23, 0).atZone(warsaw).toInstant();
        save(party, "saturday", "played", saturday20, saturday20.plus(10, ChronoUnit.MINUTES));
        save(party, "after midnight", "accepted", sunday0130, null);
        save(party, "just before six", "rejected", sunday0559, null);
        save(party, "sunday", "accepted", sunday0600, null);
        save(party, "a week before", "played", friday23, null);
        save(newPartyCode(), "another party", "played", saturday20, null);

        assertThat(requests.findEvenings(party, 10)).extracting(row -> row[0] + " " + row[1])
                .containsExactly("2026-10-04 1", "2026-10-03 3", "2026-09-25 1");
        assertThat(requests.findEvenings(party, 2)).hasSize(2);
        Instant from = java.time.LocalDateTime.of(2026, 10, 3, 6, 0).atZone(warsaw).toInstant();
        assertThat(requests.findRequestedBetween(party, from, sunday0600, PageRequest.of(0, 10)))
                .extracting(SongRequestEntity::getSongName).containsExactly("saturday", "after midnight", "just before six");
        assertThat(requests.findRequestedBetween(party, from, sunday0600, PageRequest.of(0, 2))).hasSize(2);
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
