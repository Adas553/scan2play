package com.scan2play.service;

import java.time.Instant;
import java.util.List;

/**
 * Questions of "Pytanie do gości" (V74) for the tests, made with {@link PollService.Poll}'s one constructor: saved, asked or ended,
 * their results shown and on the TV (the columns' defaults) — and the same question with the results kept for later (V75) or off the
 * TV (V76).
 */
public final class TestPolls {

    private TestPolls() {
    }

    public static PollService.Poll saved(long id, String partyCode, String question, List<String> answers) {
        return new PollService.Poll(id, partyCode, question, answers, 0, Instant.now(), null, null, false, null, true);
    }

    public static PollService.Poll asked(long id, String partyCode, String question, List<String> answers, int voteCount) {
        return new PollService.Poll(id, partyCode, question, answers, voteCount, Instant.now(), Instant.now(), null, false, null, true);
    }

    public static PollService.Poll ended(long id, String partyCode, String question, List<String> answers, int voteCount) {
        Instant now = Instant.now();
        return new PollService.Poll(id, partyCode, question, answers, voteCount, now, now, now, false, null, true);
    }

    /** The question with its results kept for later (V75). */
    public static PollService.Poll resultsHidden(PollService.Poll poll) {
        return new PollService.Poll(poll.id(), poll.partyCode(), poll.question(), poll.answers(), poll.voteCount(), poll.createdAt(),
                poll.openedAt(), poll.closedAt(), true, poll.revealedAt(), poll.onTv());
    }

    /** The question while the TV keeps the most wanted songs (V76). */
    public static PollService.Poll offTv(PollService.Poll poll) {
        return new PollService.Poll(poll.id(), poll.partyCode(), poll.question(), poll.answers(), poll.voteCount(), poll.createdAt(),
                poll.openedAt(), poll.closedAt(), poll.resultsHidden(), poll.revealedAt(), false);
    }
}
