package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Pytanie do gości" (V74) on a real PostgreSQL: a guest's vote counts once — a double tap too —, changed it stays one; guests voting
 * at once are each counted, and the ceiling holds under them; one question of a party is asked at a time, under two "Zadaj" at once
 * too; an ended or saved question takes no vote; a party keeps at most {@value PollService#MAX_POLLS}; the questions go with the party
 * and follow its new code, and the purge deletes them with their votes.
 */
class PollServiceIT extends PostgresIntegrationTest {

    @Autowired PollService polls;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private String party() {
        String code = newPartyCode('Q');
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("poll-" + code).active(true).build());
        return code;
    }

    private PollService.Poll asked(String party) {
        return polls.create(party, "Disco polo?", List.of("Tak", "Nie", "Nie wiem"), false, true).orElseThrow();
    }

    private int[] votes(PollService.Poll poll) {
        return polls.results(polls.poll(poll.partyCode(), poll.id()).orElseThrow()).rows().stream()
                .mapToInt(PollService.Row::votes).toArray();
    }

    private int voteCount(PollService.Poll poll) {
        return polls.poll(poll.partyCode(), poll.id()).orElseThrow().voteCount();
    }

    @Test
    void aVote_countsOnce_andChangedStaysOne() throws Exception {
        PollService.Poll poll = asked(party());
        assertThat(polls.vote(poll.partyCode(), poll.id(), "voter-a", 0)).isEqualTo(PollService.Voted.VOTED);
        assertThat(polls.vote(poll.partyCode(), poll.id(), "voter-b", 0)).isEqualTo(PollService.Voted.VOTED);
        assertThat(polls.vote(poll.partyCode(), poll.id(), "voter-a", 2)).isEqualTo(PollService.Voted.CHANGED);
        assertThat(votes(poll)).containsExactly(1, 0, 1);
        assertThat(voteCount(poll)).isEqualTo(2);
        assertThat(polls.choiceOf(poll.id(), "voter-a")).contains(2);
        assertThat(polls.choiceOf(poll.id(), "voter-c")).isEmpty();
        assertThat(polls.vote(poll.partyCode(), poll.id(), "voter-c", 3)).as("no fourth answer").isEqualTo(PollService.Voted.INVALID);
        assertThat(polls.vote(poll.partyCode(), poll.id(), "voter-c", -1)).isEqualTo(PollService.Voted.INVALID);
    }

    /** One guest tapping twenty times at once: one voter, one row. */
    @Test
    void aGuestsDoubleTap_isOneVote() throws Exception {
        PollService.Poll poll = asked(party());
        List<PollService.Voted> answers = atOnce(20, n -> polls.vote(poll.partyCode(), poll.id(), "same-guest", n % 2));
        assertThat(answers).filteredOn(answer -> answer == PollService.Voted.VOTED).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer == PollService.Voted.CHANGED).hasSize(19);
        assertThat(voteCount(poll)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_poll_vote WHERE poll_id = ?", Integer.class, poll.id())).isEqualTo(1);
    }

    /** Fifty guests at the same moment: fifty votes, and the question's count fifty too. */
    @Test
    void guestsVotingAtOnce_areEachCounted() throws Exception {
        PollService.Poll poll = asked(party());
        List<PollService.Voted> answers = atOnce(50, n -> polls.vote(poll.partyCode(), poll.id(), "guest-" + n, n % 3));
        assertThat(answers).containsOnly(PollService.Voted.VOTED);
        assertThat(votes(poll)).containsExactly(17, 17, 16);
        assertThat(voteCount(poll)).isEqualTo(50);
    }

    /** One place left and ten new guests at once: one gets it — never one more than the ceiling; a voter already in may change. */
    @Test
    void theCeiling_holdsUnderGuestsVotingAtOnce() throws Exception {
        PollService.Poll poll = asked(party());
        polls.vote(poll.partyCode(), poll.id(), "in-already", 0);
        jdbc.update("UPDATE party_poll SET vote_count = ? WHERE id = ?", PollService.MAX_VOTES - 1, poll.id());
        List<PollService.Voted> answers = atOnce(10, n -> polls.vote(poll.partyCode(), poll.id(), "late-" + n, 1));
        assertThat(answers).filteredOn(answer -> answer == PollService.Voted.VOTED).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer == PollService.Voted.FULL).hasSize(9);
        assertThat(voteCount(poll)).isEqualTo(PollService.MAX_VOTES);
        assertThat(polls.vote(poll.partyCode(), poll.id(), "in-already", 1)).isEqualTo(PollService.Voted.CHANGED);
    }

    @Test
    void asking_endsTheOneBefore_keepsItsVotes_andAskedAgainItTakesMore() {
        String party = party();
        PollService.Poll first = asked(party);
        polls.vote(party, first.id(), "voter-a", 1);
        PollService.Poll second = polls.create(party, "Wolniej czy szybciej?", List.of("Wolniej", "Szybciej"), false, false).orElseThrow();
        assertThat(second.state()).isEqualTo(PollService.State.SAVED);
        assertThat(polls.vote(party, second.id(), "voter-a", 0)).as("a saved one takes no vote").isEqualTo(PollService.Voted.NOT_ASKED);
        assertThat(polls.asked(party)).map(PollService.Poll::id).contains(first.id());

        assertThat(polls.ask(party, second.id())).isTrue();
        assertThat(polls.asked(party)).map(PollService.Poll::id).contains(second.id());
        assertThat(polls.poll(party, first.id()).orElseThrow().state()).isEqualTo(PollService.State.ENDED);
        assertThat(polls.vote(party, first.id(), "voter-b", 0)).as("an ended one takes no vote").isEqualTo(PollService.Voted.NOT_ASKED);
        assertThat(polls.shown(party)).map(PollService.Poll::id).as("the guests see the one asked").contains(second.id());

        assertThat(polls.end(party, second.id())).isTrue();
        assertThat(polls.asked(party)).isEmpty();
        assertThat(polls.shown(party)).map(PollService.Poll::id).as("the one ended last, a while").contains(second.id());
        jdbc.update("UPDATE party_poll SET closed_at = now() - interval '16 minutes' WHERE party_code = ?", party);
        assertThat(polls.shown(party)).as("after a quarter of an hour, none").isEmpty();

        assertThat(polls.ask(party, first.id())).isTrue();
        assertThat(polls.vote(party, first.id(), "voter-b", 0)).isEqualTo(PollService.Voted.VOTED);
        assertThat(votes(first)).containsExactly(1, 1, 0);
        assertThat(polls.ask("NOPE1", first.id())).as("another party's question").isFalse();
    }

    /**
     * Results kept for later (V75): made so, hidden until the DJ shows them; shown, the question stays on the guests' page and the
     * TV a quarter of an hour from then — even shown long after it ended.
     */
    @Test
    void resultsKeptForLater_areShownWhenTheDjSays_andTheQuestionStaysAWhileFromThen() {
        String party = party();
        PollService.Poll poll = polls.create(party, "Kto pierwszy na parkiet?", List.of("Ania", "Kuba"), true, true).orElseThrow();
        assertThat(poll.resultsHidden()).isTrue();
        assertThat(poll.revealedAt()).isNull();
        assertThat(polls.end(party, poll.id())).isTrue();
        jdbc.update("UPDATE party_poll SET closed_at = now() - interval '40 minutes' WHERE id = ?", poll.id());
        assertThat(polls.shown(party)).as("ended long ago, never shown").isEmpty();

        assertThat(polls.setResultsHidden(party, poll.id(), false)).isTrue();
        PollService.Poll shown = polls.poll(party, poll.id()).orElseThrow();
        assertThat(shown.resultsHidden()).isFalse();
        assertThat(shown.revealedAt()).isNotNull();
        assertThat(polls.shown(party)).map(PollService.Poll::id).as("shown now: on the guests' page a while").contains(poll.id());

        assertThat(polls.setResultsHidden(party, poll.id(), true)).isTrue();
        assertThat(polls.poll(party, poll.id()).orElseThrow().resultsHidden()).isTrue();
        assertThat(polls.setResultsHidden("NOPE1", poll.id(), false)).as("another party's question").isFalse();
        assertThat(polls.create(party, "Disco polo?", List.of("Tak", "Nie"), false, false).orElseThrow().resultsHidden()).isFalse();
    }

    /** The TV keeps the songs (V76): switched per question; back on the TV, the question stays on the screens a while from then. */
    @Test
    void theTvSwitch_isKeptPerQuestion_andBackOnTheTvTheQuestionStaysAWhile() {
        String party = party();
        PollService.Poll poll = asked(party);
        assertThat(poll.onTv()).as("on the TV by default").isTrue();
        assertThat(polls.setOnTv(party, poll.id(), false)).isTrue();
        assertThat(polls.poll(party, poll.id()).orElseThrow().onTv()).isFalse();
        assertThat(polls.shown(party)).as("the phones still show it").map(PollService.Poll::id).contains(poll.id());

        polls.end(party, poll.id());
        jdbc.update("UPDATE party_poll SET closed_at = now() - interval '40 minutes' WHERE id = ?", poll.id());
        assertThat(polls.shown(party)).isEmpty();
        assertThat(polls.setOnTv(party, poll.id(), true)).isTrue();
        assertThat(polls.shown(party)).as("back on the TV: a quarter of an hour from now").map(PollService.Poll::id).contains(poll.id());
        assertThat(polls.setOnTv("NOPE1", poll.id(), false)).isFalse();
    }

    /** Two "Zadaj" of two questions at once: one is asked, never two (the partial unique index would refuse the second anyway). */
    @Test
    void twoAsksAtOnce_leaveOneAsked() throws Exception {
        String party = party();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(polls.create(party, "Pytanie " + i, List.of("Tak", "Nie"), false, false).orElseThrow().id());
        }
        List<Boolean> answers = atOnce(6, n -> polls.ask(party, ids.get(n)));
        assertThat(answers).containsOnly(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_poll WHERE party_code = ? AND opened_at IS NOT NULL AND closed_at IS NULL",
                Integer.class, party)).isEqualTo(1);
    }

    @Test
    void aParty_keepsAtMostItsQuestions_andDeletingOneMakesRoom() {
        String party = party();
        for (int i = 0; i < PollService.MAX_POLLS; i++) {
            assertThat(polls.create(party, "Pytanie " + i, List.of("Tak", "Nie"), false, false)).isPresent();
        }
        assertThat(polls.create(party, "Jeszcze jedno", List.of("Tak", "Nie"), false, true)).isEmpty();
        assertThat(polls.pollsOf(party)).hasSize(PollService.MAX_POLLS).first().extracting(PollService.Poll::question).isEqualTo("Pytanie 29");
        assertThat(polls.delete(party, polls.pollsOf(party).get(0).id())).isTrue();
        assertThat(polls.create(party, "Jeszcze jedno", List.of("Tak", "Nie"), false, true)).isPresent();
    }

    @Test
    void theQuestions_goWithTheParty_followItsNewCode_andThePurgeDeletesThemWithTheirVotes() {
        String party = party();
        PollService.Poll poll = asked(party);
        polls.vote(party, poll.id(), "voter-a", 0);
        String moved = newPartyCode('Q');
        jdbc.update("UPDATE party_settings SET party_code = ? WHERE party_code = ?", moved, party);
        assertThat(polls.asked(moved)).map(PollService.Poll::id).contains(poll.id());

        jdbc.update("UPDATE party_poll SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minus(91, ChronoUnit.DAYS)),
                poll.id());
        PollService.Poll fresh = polls.create(moved, "Świeże", List.of("Tak", "Nie"), false, false).orElseThrow();
        assertThat(polls.purgeBefore(Instant.now().minus(PollService.KEPT_DAYS, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1);
        assertThat(polls.poll(moved, poll.id())).isEmpty();
        assertThat(polls.poll(moved, fresh.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_poll_vote WHERE poll_id = ?", Integer.class, poll.id())).isZero();

        jdbc.update("DELETE FROM party_settings WHERE party_code = ?", moved);
        assertThat(polls.pollsOf(moved)).isEmpty();
    }

    private interface Numbered<T> {
        T call(int n) throws Exception;
    }

    private static <T> List<T> atOnce(int threads, Numbered<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                Callable<T> callable = () -> {
                    start.await();
                    return task.call(n);
                };
                futures.add(pool.submit(callable));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
