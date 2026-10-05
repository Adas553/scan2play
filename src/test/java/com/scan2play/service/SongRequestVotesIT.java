package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.SongRequestCommandService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired SongRequestRepository requests;
    @Autowired DjService dj;
    @Autowired PlatformTransactionManager transactions;

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

    /**
     * Option A: a song the DJ skipped ("Pomiń") within the last two hours is not saved again — another guest's request for it does
     * not put it back in the queue. Counted from the skip, not from the request: a song that waited three hours and was skipped now
     * stays out. A song only cleared with the whole queue, one skipped more than two hours ago, and one skipped at another party may
     * be asked for again.
     */
    @Test
    void aSongTheDjSkippedLately_isNotSavedAgain_aClearedOldOrOtherPartysOneIs() {
        String party = newPartyCode();
        Long wilki = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).request().getId();
        Long sanah = commands.saveOrVote(request(party, "sanah - Szampan", "accepted", LINK), Set.of()).request().getId();
        jdbc.update("UPDATE song_requests SET requested_at = now() - interval '3 hours' WHERE id = ?", wilki);
        dj.dismissSong(wilki, party);
        dj.dismissSong(sanah, party);
        jdbc.update("UPDATE song_requests SET skipped_at = now() - interval '3 hours' WHERE id = ?", sanah);   // skipped long ago
        commands.saveOrVote(request(party, "Kult - Arahja", "accepted", LINK), Set.of());
        dj.clearQueue(party);

        SongRequestCommandService.Saved again = commands.saveOrVote(request(party, "wilki baśka", "accepted", LINK), Set.of());
        assertThat(again.outcome()).isEqualTo(Outcome.SKIPPED_BY_DJ);
        assertThat(again.request().getId()).isEqualTo(wilki);
        assertThat(commands.saveOrVote(request(party, "Kult - Arahja", "accepted", LINK), Set.of()).outcome()).isEqualTo(Outcome.NEW);
        assertThat(commands.saveOrVote(request(party, "sanah - Szampan", "accepted", LINK), Set.of()).outcome()).isEqualTo(Outcome.NEW);
        assertThat(commands.saveOrVote(request(newPartyCode(), "Wilki - Baśka", "accepted", LINK), Set.of()).outcome())
                .isEqualTo(Outcome.NEW);

        assertThat(rows(party)).extracting(row -> row.get("song_name") + " " + row.get("decision"))
                .containsExactly("Wilki - Baśka rejected", "sanah - Szampan rejected", "Kult - Arahja rejected",
                        "Kult - Arahja accepted", "sanah - Szampan accepted");
    }

    /**
     * "Cofnij" / "↩ Przywróć": a skipped request goes back to the queue, and the song is no longer kept out — the next guest's
     * request for it is a vote on it. Not a request the AI rejected, not another party's, and not when the same song waits already.
     */
    @Test
    void aSkippedRequestPutBack_waitsAgain_andTheNextRequestIsAVoteOnIt() {
        String party = newPartyCode();
        Long wilki = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).request().getId();
        dj.dismissSong(wilki, party);

        assertThat(dj.restoreSkippedSong(wilki, newPartyCode())).as("another party's DJ").isFalse();
        assertThat(dj.restoreSkippedSong(wilki, party)).isTrue();
        assertThat(jdbc.queryForObject("SELECT decision || ' ' || (skipped_at IS NULL) FROM song_requests WHERE id = ?", String.class, wilki))
                .isEqualTo("accepted true");
        assertThat(commands.saveOrVote(request(party, "wilki baśka", "accepted", LINK), Set.of()).outcome()).isEqualTo(Outcome.VOTE);
        assertThat(dj.restoreSkippedSong(wilki, party)).as("waiting already: nothing to put back").isFalse();

        Long rejectedByTheAi = commands.saveOrVote(request(party, "Kult - Arahja", "rejected", null), Set.of()).request().getId();
        assertThat(dj.restoreSkippedSong(rejectedByTheAi, party)).as("only the DJ's skips").isFalse();

        // the same song asked for again once the skip was forgotten, then the old skipped request: the song waits already
        Long sanah = commands.saveOrVote(request(party, "sanah - Szampan", "accepted", LINK), Set.of()).request().getId();
        dj.dismissSong(sanah, party);
        jdbc.update("UPDATE song_requests SET skipped_at = now() - interval '3 hours' WHERE id = ?", sanah);
        commands.saveOrVote(request(party, "sanah - Szampan", "accepted", LINK), Set.of());
        assertThat(dj.restoreSkippedSong(sanah, party)).isFalse();

        assertThat(rows(party)).extracting(row -> row.get("song_name") + " " + row.get("decision") + " " + row.get("votes"))
                .containsExactly("Wilki - Baśka accepted 2", "Kult - Arahja rejected 1", "sanah - Szampan rejected 1",
                        "sanah - Szampan accepted 1");
    }

    /**
     * The DJ marks a song played (DjService: read the row, change it, save it) while a guest's vote on it commits in between: the
     * DJ's save writes only what it changed, so the vote stays — it once wrote the whole row back, votes as read before the vote.
     */
    @Test
    void aVoteThatCommitsWhileTheDjMarksTheSongPlayed_isKept() throws Exception {
        String party = newPartyCode();
        Long id = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).request().getId();
        ExecutorService guest = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                SongRequestEntity song = requests.findById(id).orElseThrow();   // the DJ's read: votes 1
                try {
                    Outcome vote = guest.submit(() ->
                            commands.saveOrVote(request(party, "Wilki - Baśka", "accepted", LINK), Set.of()).outcome())
                            .get(30, TimeUnit.SECONDS);
                    assertThat(vote).isEqualTo(Outcome.VOTE);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                DjService.markPlayed(song, Instant.now());
                requests.save(song);
            });
        } finally {
            guest.shutdownNow();
        }

        assertThat(rows(party)).extracting(row -> row.get("decision") + " " + row.get("votes")).containsExactly("played 2");
    }
}
