package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.util.Texts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * "Pytanie do gości" (V74, Pro): the DJ asks the room a question of their own with two to four answers ("Disco polo — tak czy
 * nie?"), the guests answer on the party page — one vote each, which they may change while it is asked — and the DJ sees the results
 * live, the TV screen too. One question of a party is asked at a time: asking another ends the one before. Nothing of the guest is
 * kept: a token made of their session's id ({@link #voterOf}) and the answer they picked.
 * <p>
 * Plain SQL, as {@link WishService}: a vote is a few statements under the question's row lock — the question still asked, the
 * guest's answer changed or a new one counted under the ceiling — and the results one bounded {@code GROUP BY}.
 */
@Service
@Slf4j
public class PollService {

    /** Questions a party keeps, asked, ended or saved for later: the DJ deletes the old ones to make more. */
    public static final int MAX_POLLS = 30;
    /** The question as the guests read it, as the column {@code party_poll.question}. */
    public static final int QUESTION_MAX = 120;
    /** An answer as the guests tap it, as the column {@code party_poll.answers}. */
    public static final int ANSWER_MAX = 40;
    public static final int MIN_ANSWERS = 2;
    public static final int MAX_ANSWERS = 4;
    /** Voters of one question: a ball of a thousand guests, with room; a script dropping its cookie stops there. */
    public static final int MAX_VOTES = 5_000;
    /** A question is kept this many days after it was made — the privacy policy says so — and then deleted with its votes. */
    public static final int KEPT_DAYS = 90;
    /** An ended question's results stay on the guests' page and the TV this long: the room sees how it came out. */
    public static final Duration SHOWN_AFTER_END = Duration.ofMinutes(15);
    /**
     * The results are counted again at most this often: every guest who voted, the DJ's page and the TV ask for them; a vote counts
     * them again at once ({@link #vote}).
     */
    static final Duration RESULTS_FRESH = Duration.ofSeconds(2);

    /** Where a question is: saved for later, asked now, or ended. */
    public enum State { SAVED, ASKED, ENDED }

    /** One question of a party, its answers in their order and how many voted. */
    public record Poll(long id, String partyCode, String question, List<String> answers, int voteCount, Instant createdAt,
                       Instant openedAt, Instant closedAt) {

        public State state() {
            return openedAt == null ? State.SAVED : closedAt == null ? State.ASKED : State.ENDED;
        }

        public boolean asked() {
            return state() == State.ASKED;
        }
    }

    /** One answer's result: its place, its words, its votes and their share (whole percents), and whether it leads. */
    public record Row(int choice, String answer, int votes, int percent, boolean leads) {
    }

    /** A question's results: a row per answer, in the answers' order, and every vote. */
    public record Results(Poll poll, List<Row> rows, int total) {
    }

    /** What became of a guest's vote. */
    public enum Voted { VOTED, CHANGED, NOT_ASKED, FULL, INVALID }

    private static final RowMapper<Poll> POLL = (row, n) -> new Poll(row.getLong("id"), row.getString("party_code"),
            row.getString("question"), answers(row.getArray("answers")), row.getInt("vote_count"), instant(row.getTimestamp("created_at")),
            instant(row.getTimestamp("opened_at")), instant(row.getTimestamp("closed_at")));

    private static final String COLUMNS = "id, party_code, question, answers, vote_count, created_at, opened_at, closed_at";

    private final JdbcTemplate jdbc;
    /** A question's votes per answer, by its id — counted at most every {@link #RESULTS_FRESH}, at once after a vote. */
    private final Cache<Long, int[]> counts;

    public PollService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.counts = Caffeine.newBuilder().expireAfterWrite(RESULTS_FRESH).maximumSize(10_000).build();
    }

    /** The party's questions, the newest first — every one it keeps. */
    public List<Poll> pollsOf(String partyCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? ORDER BY id DESC LIMIT ?", POLL, partyCode,
                MAX_POLLS);
    }

    /** The party's question of that id; another party's is none. */
    public Optional<Poll> poll(String partyCode, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND id = ?", POLL, partyCode, id).stream()
                .findFirst();
    }

    /** The question the party asks now, if any. */
    public Optional<Poll> asked(String partyCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND opened_at IS NOT NULL AND closed_at IS NULL",
                POLL, partyCode).stream().findFirst();
    }

    /**
     * The question the guests' page and the TV show: the one asked now, else the one ended in the last
     * {@link #SHOWN_AFTER_END} with its results.
     */
    public Optional<Poll> shown(String partyCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND opened_at IS NOT NULL"
                + " AND (closed_at IS NULL OR closed_at > ?) ORDER BY closed_at IS NULL DESC, closed_at DESC LIMIT 1", POLL, partyCode,
                Timestamp.from(Instant.now().minus(SHOWN_AFTER_END))).stream().findFirst();
    }

    /** How many questions the party keeps. */
    public int count(String partyCode) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM party_poll WHERE party_code = ?", Integer.class, partyCode);
        return count == null ? 0 : count;
    }

    /**
     * A new question — asked at once ({@code ask}), or saved for later. None when the party keeps {@value #MAX_POLLS} already:
     * counted under the party row's lock, so two "Zadaj" at once make one too many no more.
     *
     * @param question a question {@link #tidyQuestion} kept
     * @param answers  answers {@link #tidyAnswers} kept
     */
    @Transactional
    public Optional<Poll> create(String partyCode, String question, List<String> answers, boolean ask) {
        if (!lockParty(partyCode) || count(partyCode) >= MAX_POLLS) {
            return Optional.empty();
        }
        Long id = jdbc.queryForObject("INSERT INTO party_poll (party_code, question, answers) VALUES (?, ?, ?) RETURNING id", Long.class,
                partyCode, question, answers.toArray(String[]::new));
        if (ask) {
            askLocked(partyCode, id);
        }
        log.info("Party [{}]: question {} made{}", partyCode, id, ask ? " and asked" : "");
        return poll(partyCode, id);
    }

    /**
     * "Zadaj teraz": the question is asked — the one asked before ends. One asked again after it ended keeps its votes and takes
     * more. Under the party row's lock: two "Zadaj" at once leave one asked, never two (the unique index would refuse the second).
     */
    @Transactional
    public boolean ask(String partyCode, long id) {
        if (!lockParty(partyCode) || poll(partyCode, id).isEmpty()) {
            return false;
        }
        askLocked(partyCode, id);
        return true;
    }

    private void askLocked(String partyCode, long id) {
        jdbc.update("UPDATE party_poll SET closed_at = now() WHERE party_code = ? AND opened_at IS NOT NULL AND closed_at IS NULL"
                + " AND id <> ?", partyCode, id);
        jdbc.update("UPDATE party_poll SET opened_at = coalesce(opened_at, now()), closed_at = NULL WHERE party_code = ? AND id = ?",
                partyCode, id);
    }

    /** "Zakończ pytanie": the guests answer it no more; its results stay. False for no such question; one not asked stays as it was. */
    public boolean end(String partyCode, long id) {
        jdbc.update("UPDATE party_poll SET closed_at = now() WHERE party_code = ? AND id = ? AND opened_at IS NOT NULL"
                + " AND closed_at IS NULL", partyCode, id);
        return poll(partyCode, id).isPresent();
    }

    /** The question and its votes, at once. */
    public boolean delete(String partyCode, long id) {
        boolean deleted = jdbc.update("DELETE FROM party_poll WHERE party_code = ? AND id = ?", partyCode, id) == 1;
        counts.invalidate(id);
        return deleted;
    }

    /**
     * A guest's answer to the question asked now: a new voter counted — only while it is asked and below {@value #MAX_VOTES} —, or
     * the voter's answer changed. Under the question's row lock: a double tap, or guests voting at once, are one at a time.
     *
     * @param voter the guest's token ({@link #voterOf})
     */
    @Transactional
    public Voted vote(String partyCode, long pollId, String voter, int choice) {
        List<Poll> locked = jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND id = ? FOR UPDATE", POLL,
                partyCode, pollId);
        if (locked.isEmpty() || !locked.get(0).asked()) {
            return Voted.NOT_ASKED;
        }
        Poll poll = locked.get(0);
        if (choice < 0 || choice >= poll.answers().size() || voter == null || voter.isEmpty()) {
            return Voted.INVALID;
        }
        Voted voted;
        if (jdbc.update("UPDATE party_poll_vote SET choice = ?, voted_at = now() WHERE poll_id = ? AND voter = ?", choice, pollId,
                voter) == 1) {
            voted = Voted.CHANGED;
        } else if (poll.voteCount() >= MAX_VOTES) {
            return Voted.FULL;
        } else {
            jdbc.update("INSERT INTO party_poll_vote (poll_id, voter, choice) VALUES (?, ?, ?)", pollId, voter, choice);
            jdbc.update("UPDATE party_poll SET vote_count = vote_count + 1 WHERE id = ?", pollId);
            voted = Voted.VOTED;
        }
        // counted again once the vote is in: a count made between this and the commit would keep the old numbers for a while
        com.scan2play.util.AfterCommit.run(() -> counts.invalidate(pollId));
        return voted;
    }

    /** The answer the voter gave to that question, if any. */
    public Optional<Integer> choiceOf(long pollId, String voter) {
        if (voter == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT choice FROM party_poll_vote WHERE poll_id = ? AND voter = ?", Integer.class, pollId, voter)
                .stream().findFirst();
    }

    /** The question's results, counted at most every {@link #RESULTS_FRESH}. */
    public Results results(Poll poll) {
        int[] votes = counts.get(poll.id(), id -> count(id, poll.answers().size()));
        return results(poll, votes);
    }

    /** The results of these votes per answer: the shares in whole percents, the answers with the most votes leading (none at 0). */
    static Results results(Poll poll, int[] votes) {
        int total = Arrays.stream(votes).sum();
        int most = Arrays.stream(votes).max().orElse(0);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < poll.answers().size(); i++) {
            int count = i < votes.length ? votes[i] : 0;
            rows.add(new Row(i, poll.answers().get(i), count, total == 0 ? 0 : Math.round(100f * count / total), most > 0 && count == most));
        }
        return new Results(poll, rows, total);
    }

    private int[] count(long pollId, int answers) {
        int[] votes = new int[answers];
        jdbc.query("SELECT choice, count(*) FROM party_poll_vote WHERE poll_id = ? GROUP BY choice", row -> {
            int choice = row.getInt(1);
            if (choice >= 0 && choice < answers) {
                votes[choice] = row.getInt(2);
            }
        }, pollId);
        return votes;
    }

    /** Deletes the questions made more than {@value #KEPT_DAYS} days ago, their votes with them. Daily at 04:55 Polish time. */
    @Scheduled(cron = "0 55 4 * * *", zone = com.scan2play.util.Times.SCHEDULE_ZONE)
    public void purge() {
        int deleted = purgeBefore(Instant.now().minus(Duration.ofDays(KEPT_DAYS)));
        // a line also for none: the log shows the purge ran
        log.info("Polls purge: {} question(s) deleted with their votes", deleted);
    }

    /** {@link #purge}: the questions made before {@code madeBefore}. */
    int purgeBefore(Instant madeBefore) {
        return jdbc.update("DELETE FROM party_poll WHERE created_at < ?", Timestamp.from(madeBefore));
    }

    /** The party's row locked for this transaction: what counts its questions, or asks one, is one at a time. False: no such party. */
    private boolean lockParty(String partyCode) {
        return !jdbc.queryForList("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode)
                .isEmpty();
    }

    /**
     * The guest as a voter: the first 128 bits of the SHA-256 of their session's id, in hex. Not the id itself (the table is no list
     * of live sessions), nothing written to the session (two first votes at once would each write a token of their own), and not the
     * address — a hall's guests share the venue's Wi-Fi. A guest who drops the cookie is a new voter: a question to the room is a
     * hint for the DJ, as a 👍 is; {@link #MAX_VOTES} bounds a script.
     */
    public static String voterOf(jakarta.servlet.http.HttpSession session) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java", e);
        }
    }

    // ---------------------------------------------------------------- the texts as they are kept

    /** The question as kept: one line, at most {@value #QUESTION_MAX}; null for none. */
    public static String tidyQuestion(String typed) {
        String question = Texts.oneLine(typed == null ? null : typed.replaceAll("\\p{Cc}+", " "), QUESTION_MAX);
        return question.isEmpty() ? null : question;
    }

    /**
     * The answers as kept: each on one line, at most {@value #ANSWER_MAX}, the empty ones and repeats (by their letters' case) left
     * out, at most {@value #MAX_ANSWERS}; null when fewer than {@value #MIN_ANSWERS} are left.
     */
    public static List<String> tidyAnswers(List<String> typed) {
        if (typed == null) {
            return null;
        }
        List<String> answers = new ArrayList<>();
        for (String one : typed) {
            String answer = Texts.oneLine(one == null ? null : one.replaceAll("\\p{Cc}+", " "), ANSWER_MAX);
            if (!answer.isEmpty() && answers.stream().noneMatch(answer::equalsIgnoreCase) && answers.size() < MAX_ANSWERS) {
                answers.add(answer);
            }
        }
        return answers.size() < MIN_ANSWERS ? null : List.copyOf(answers);
    }

    private static List<String> answers(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
