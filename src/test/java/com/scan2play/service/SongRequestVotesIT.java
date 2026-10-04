package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.SongRequestCommandService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Votes on a real PostgreSQL ({@link SongRequestCommandService}): the same song asked for while it waits is one row with more votes
 * — also when many guests ask at the same moment, which only the advisory lock keeps from becoming several rows.
 */
class SongRequestVotesIT extends PostgresIntegrationTest {

    private static final int GUESTS = 16;
    private static final int ROUNDS = 20;
    private static final String LINK = "https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka";

    @Autowired SongRequestCommandService commands;
    @Autowired JdbcTemplate jdbc;

    private static SongRequestEntity request(String party, String name, String decision, String trackUrl) {
        return SongRequestEntity.builder().partyCode(party).songName(name).style("ANY").decision(decision).trackUrl(trackUrl)
                .requestedAt(Instant.now()).build();
    }

    private List<Map<String, Object>> rows(String party) {
        return jdbc.queryForList("SELECT song_name, decision, votes FROM song_requests WHERE party_code = ? ORDER BY id", party);
    }

    @Test
    void manyGuestsAskingForTheSameSongAtOnce_makeOneRowWithAllTheirVotes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(GUESTS);
        try {
            // a few rounds, each a new party: one round alone may happen to run in turns even without the lock
            for (int round = 0; round < ROUNDS; round++) {
                String party = newPartyCode();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Outcome>> asks = new ArrayList<>();
                for (int i = 0; i < GUESTS; i++) {
                    asks.add(pool.submit(() -> {
                        start.await();
                        return commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).outcome();
                    }));
                }
                start.countDown();
                List<Outcome> outcomes = new ArrayList<>();
                for (Future<Outcome> ask : asks) {
                    outcomes.add(ask.get(30, TimeUnit.SECONDS));
                }
                assertThat(rows(party)).as("round " + round).hasSize(1);
                assertThat(rows(party).getFirst()).containsEntry("votes", GUESTS);
                assertThat(outcomes).containsOnlyOnce(Outcome.NEW).filteredOn(o -> o == Outcome.VOTE).hasSize(GUESTS - 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameSongByName_isAVote_evenRejectedThisTime_theGuestsOwn_andAPlayedOne_areNot() {
        String party = newPartyCode();
        SongRequestEntity first = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).request();

        // the same name written another way (case, accents, punctuation), and the same name without a link
        assertThat(commands.saveOrVote(request(party, "WILKI – Baśka!", "accepted", LINK), Set.of()).outcome())
                .isEqualTo(Outcome.VOTE);
        assertThat(commands.saveOrVote(request(party, "wilki baśka", "accepted", null), Set.of()).outcome()).isEqualTo(Outcome.VOTE);
        // the guest who asked first asks again: nothing changes
        SongRequestCommandService.Saved own = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of(first.getId()));
        assertThat(own.outcome()).isEqualTo(Outcome.ALREADY_YOURS);
        assertThat(own.request().getVotes()).isEqualTo(3);
        // the AI rejected it this time (it does not judge a song the same way every time): the party took the song already — a vote
        assertThat(commands.saveOrVote(request(party, "Wilki - Baśka", "rejected", null), Set.of()).outcome()).isEqualTo(Outcome.VOTE);
        // a rejected request for a song that does not wait is a row of its own (the history shows it)
        assertThat(commands.saveOrVote(request(party, "Kult - Arahja", "rejected", null), Set.of()).outcome()).isEqualTo(Outcome.NEW);
        // played: the next request for it is a new row
        jdbc.update("UPDATE song_requests SET decision = 'played' WHERE id = ?", first.getId());
        assertThat(commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).outcome()).isEqualTo(Outcome.NEW);

        assertThat(rows(party)).extracting(row -> row.get("decision") + " " + row.get("votes"))
                .containsExactly("played 4", "rejected 1", "accepted 1");
    }

    @Test
    void aVoteNeverWritesTheWholeRowBack() {
        String party = newPartyCode();
        SongRequestEntity first = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).request();

        SongRequestCommandService.Saved vote = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of());

        assertThat(vote.outcome()).isEqualTo(Outcome.VOTE);
        assertThat(vote.request().getId()).isEqualTo(first.getId());
        assertThat(vote.request().getVotes()).isEqualTo(2);
        assertThat(rows(party)).extracting(row -> row.get("decision") + " " + row.get("votes")).containsExactly("accepted 2");
    }
}
