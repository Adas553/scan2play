package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.Texts;
import com.scan2play.util.Times;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Pytanie do gości" (V74, Pro): the DJ asks the room a question of their own with two to four answers ("Disco polo — tak czy
 * nie?"), the guests answer on the party page — one vote each, which they may change while it is asked — and the DJ sees the results
 * live, the TV screen too. One question of a party is asked at a time: asking another ends the one before. Nothing of the guest is
 * kept: a token made of their session's id ({@link #voterOf}) and the answer they picked. A question asked longer than
 * {@link #ASKED_FOR} counts as ended: a DJ who packs up without "Zakończ pytanie" does not leave it to the next party on the same code.
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
    /** A question is kept this many days after it was last used — the privacy policy says so — and then deleted with its votes. */
    public static final int KEPT_DAYS = 90;
    /** An ended question's results stay on the guests' page and the TV this long: the room sees how it came out. */
    public static final Duration SHOWN_AFTER_END = Duration.ofMinutes(15);
    /**
     * A question asked longer than this counts as ended at that moment — the longest evening, with room. Without it a question the
     * DJ forgot to end stayed asked for the next party on the same code (a party is kept from week to week), on its guests' page and
     * its TV. Not ended when the requests open again: a DJ may ask the room while they are paused and open them during the vote.
     */
    public static final Duration ASKED_FOR = Duration.ofHours(12);
    /** The moment an asked question ends by itself, in SQL: as {@link #endOf} in Java. */
    private static final String ENDS = "coalesce(closed_at, opened_at + interval '" + ASKED_FOR.toHours() + " hours')";
    /**
     * The results are counted again at most this often: every guest who voted, the DJ's page and the TV ask for them; a vote counts
     * them again at once ({@link #vote}).
     */
    static final Duration RESULTS_FRESH = Duration.ofSeconds(2);

    /** Where a question is: saved for later, asked now, or ended. */
    public enum State { SAVED, ASKED, ENDED }

    /**
     * One question of a party, its answers in their order and how many voted; when it was last asked ({@code openedAt}) and when
     * it ended ({@code closedAt} — the DJ's "Zakończ pytanie", or {@link #ASKED_FOR} after it was asked); whether the guests see
     * its results ({@code resultsHidden}: not until the DJ shows them, V75), when the DJ last showed them ({@code revealedAt}), and
     * whether the TV shows it ({@code onTv}, V76) or keeps the most wanted songs.
     */
    public record Poll(long id, String partyCode, String question, List<String> answers, int voteCount, Instant createdAt,
                       Instant openedAt, Instant closedAt, boolean resultsHidden, Instant revealedAt, boolean onTv) {

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

    private static final RowMapper<Poll> POLL = (row, n) -> {
        Instant openedAt = instant(row.getTimestamp("opened_at"));
        return new Poll(row.getLong("id"), row.getString("party_code"), row.getString("question"), answers(row.getArray("answers")),
                row.getInt("vote_count"), instant(row.getTimestamp("created_at")), openedAt,
                endOf(openedAt, instant(row.getTimestamp("closed_at")), Instant.now()), row.getBoolean("results_hidden"),
                instant(row.getTimestamp("revealed_at")), row.getBoolean("on_tv"));
    };

    private static final String COLUMNS = "id, party_code, question, answers, vote_count, created_at, opened_at, closed_at,"
            + " results_hidden, revealed_at, on_tv";

    private final JdbcTemplate jdbc;
    /** A question's votes per answer, by its id — counted at most every {@link #RESULTS_FRESH}, at once after a vote. */
    private final Cache<Long, int[]> counts;
    /**
     * The question a party's guests' page and TV show ({@link #shown}), by the party's code — asked for on every guest's page of a
     * Pro party, most of which never ask one: looked up at most every {@link #RESULTS_FRESH}, at once after anything the DJ does
     * to a question or a vote (its count is on the card).
     */
    private final Cache<String, Optional<Poll>> shownByParty;

    public PollService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.counts = Caffeine.newBuilder().expireAfterWrite(RESULTS_FRESH).maximumSize(10_000).build();
        this.shownByParty = Caffeine.newBuilder().expireAfterWrite(RESULTS_FRESH).maximumSize(10_000).build();
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
        return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND opened_at > ? AND closed_at IS NULL",
                POLL, partyCode, Timestamp.from(Instant.now().minus(ASKED_FOR))).stream().findFirst();
    }

    /**
     * The question the guests' page and the TV show: the one asked now, else the one that ended — or whose results the DJ showed —
     * in the last {@link #SHOWN_AFTER_END}, the latest of them. A party has at most {@value #MAX_POLLS}: the sort is of those.
     * Looked up at most every {@link #RESULTS_FRESH}.
     */
    public Optional<Poll> shown(String partyCode) {
        return shownByParty.get(partyCode, code -> {
            Instant now = Instant.now();
            return jdbc.query("SELECT " + COLUMNS + " FROM party_poll WHERE party_code = ? AND opened_at IS NOT NULL"
                    + " AND greatest(" + ENDS + ", revealed_at) > ?"
                    + " ORDER BY (closed_at IS NULL AND opened_at > ?) DESC, greatest(" + ENDS + ", revealed_at) DESC LIMIT 1", POLL, code,
                    Timestamp.from(now.minus(SHOWN_AFTER_END)), Timestamp.from(now.minus(ASKED_FOR))).stream().findFirst();
        });
    }

    /**
     * "Pokaż wyniki gościom" ({@code hidden=false}: the guests' page and the TV show the numbers from now, and the question stays
     * there a quarter of an hour after this) or "Ukryj wyniki przed gośćmi". False for no such question.
     */
    public boolean setResultsHidden(String partyCode, long id, boolean hidden) {
        boolean changed = jdbc.update("UPDATE party_poll SET results_hidden = ?, revealed_at = CASE WHEN ? THEN revealed_at ELSE now() END"
                + " WHERE party_code = ? AND id = ?", hidden, hidden, partyCode, id) == 1;
        forgetShown(partyCode);
        return changed;
    }

    /**
     * "Na TV: pytanie" ({@code onTv=true}) or "Na TV: piosenki" — the guests' most wanted stay on the screen while the question is
     * asked. Only the TV: the guests' phones and how long the question stays on the screens are not changed by it (no
     * {@code revealed_at}). False for no such question.
     */
    public boolean setOnTv(String partyCode, long id, boolean onTv) {
        boolean changed = jdbc.update("UPDATE party_poll SET on_tv = ? WHERE party_code = ? AND id = ?", onTv, partyCode, id) == 1;
        forgetShown(partyCode);
        return changed;
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
     * @param hideResults the guests see no numbers until the DJ shows them (V75)
     */
    @Transactional
    public Optional<Poll> create(String partyCode, String question, List<String> answers, boolean hideResults, boolean ask) {
        if (!lockParty(partyCode) || count(partyCode) >= MAX_POLLS) {
            return Optional.empty();
        }
        Long id = jdbc.queryForObject("INSERT INTO party_poll (party_code, question, answers, results_hidden) VALUES (?, ?, ?, ?)"
                + " RETURNING id", Long.class, partyCode, question, answers.toArray(String[]::new), hideResults);
        if (ask) {
            askLocked(partyCode, id);
        }
        log.info("Party [{}]: question {} made{}", partyCode, id, ask ? " and asked" : "");
        forgetShown(partyCode);
        return poll(partyCode, id);
    }

    /** What became of "Zapisz zmiany" on a question. */
    public enum Edited { EDITED, ASKED_BEFORE, NOT_FOUND }

    /**
     * "Edytuj" — "Zapisz zmiany": the words, the answers and whether the guests' results wait for the DJ, of a question saved for
     * later only. One asked (now or before) is never changed: a vote is the answer's place (0 … 3), so changed answers would make the
     * votes say what nobody chose, and a guest's card drawn before the change would send its tap to another answer — the DJ asks it
     * corrected as a new one ("Popraw jako nowe"). The {@code opened_at IS NULL} of the statement holds against a "Zadaj" at once: the
     * row's lock makes one wait, and the other's condition is read again after it.
     *
     * @param question a question {@link #tidyQuestion} kept
     * @param answers  answers {@link #tidyAnswers} kept
     */
    public Edited edit(String partyCode, long id, String question, List<String> answers, boolean hideResults) {
        int changed = jdbc.update("UPDATE party_poll SET question = ?, answers = ?, results_hidden = ? WHERE party_code = ? AND id = ?"
                + " AND opened_at IS NULL", question, answers.toArray(String[]::new), hideResults, partyCode, id);
        if (changed == 1) {
            log.info("Party [{}]: question {} edited", partyCode, id);
            return Edited.EDITED;
        }
        return poll(partyCode, id).isPresent() ? Edited.ASKED_BEFORE : Edited.NOT_FOUND;
    }

    /**
     * "Zadaj teraz": the question is asked — the one asked before ends. One asked again after it ended keeps its votes and takes
     * more, for another {@link #ASKED_FOR} from now. Under the party row's lock: two "Zadaj" at once leave one asked, never two (the
     * unique index would refuse the second).
     */
    @Transactional
    public boolean ask(String partyCode, long id) {
        if (!lockParty(partyCode) || poll(partyCode, id).isEmpty()) {
            return false;
        }
        askLocked(partyCode, id);
        forgetShown(partyCode);
        return true;
    }

    private void askLocked(String partyCode, long id) {
        jdbc.update("UPDATE party_poll SET closed_at = least(now(), " + ENDS + ") WHERE party_code = ? AND opened_at IS NOT NULL"
                + " AND closed_at IS NULL AND id <> ?", partyCode, id);
        jdbc.update("UPDATE party_poll SET opened_at = now(), closed_at = NULL WHERE party_code = ? AND id = ?", partyCode, id);
    }

    /**
     * "Zakończ pytanie": the guests answer it no more; its results stay. False for no such question; one not asked stays as it was
     * (one that ended by itself keeps that moment).
     */
    public boolean end(String partyCode, long id) {
        boolean ended = jdbc.update("UPDATE party_poll SET closed_at = least(now(), " + ENDS + ") WHERE party_code = ? AND id = ?"
                + " AND opened_at IS NOT NULL AND closed_at IS NULL", partyCode, id) == 1;
        forgetShown(partyCode);
        return ended || poll(partyCode, id).isPresent();
    }

    /** The question and its votes, at once. */
    public boolean delete(String partyCode, long id) {
        boolean deleted = jdbc.update("DELETE FROM party_poll WHERE party_code = ? AND id = ?", partyCode, id) == 1;
        counts.invalidate(id);
        forgetShown(partyCode);
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
        // counted again once the vote is in: a count made between this and the commit would keep the old numbers for a while — and
        // the question shown with its count of voters, which the card of a question whose results are kept for later says
        AfterCommit.run(() -> {
            counts.invalidate(pollId);
            shownByParty.invalidate(partyCode);
        });
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

    /**
     * The results of the party's questions, in their order, counted by one query: the ones without a vote are not asked for (a
     * question saved for later has none). The DJ's page lists up to {@value #MAX_POLLS}.
     */
    public List<Results> resultsOf(List<Poll> polls) {
        List<Long> counted = polls.stream().filter(poll -> poll.voteCount() > 0 && counts.getIfPresent(poll.id()) == null)
                .map(Poll::id).toList();
        Map<Long, int[]> fresh = new HashMap<>();
        if (!counted.isEmpty()) {
            Map<Long, Integer> sizes = new HashMap<>();
            polls.forEach(poll -> sizes.put(poll.id(), poll.answers().size()));
            jdbc.query("SELECT poll_id, choice, count(*) FROM party_poll_vote WHERE poll_id = ANY(?) GROUP BY poll_id, choice", row -> {
                long id = row.getLong(1);
                int choice = row.getInt(2);
                int[] votes = fresh.computeIfAbsent(id, key -> new int[sizes.get(key)]);
                if (choice >= 0 && choice < votes.length) {
                    votes[choice] = row.getInt(3);
                }
            }, (Object) counted.toArray(Long[]::new));
            counted.forEach(id -> counts.put(id, fresh.computeIfAbsent(id, key -> new int[sizes.get(key)])));
        }
        return polls.stream().map(poll -> {
            int[] votes = poll.voteCount() == 0 ? new int[poll.answers().size()] : counts.getIfPresent(poll.id());
            return votes == null ? results(poll) : results(poll, votes);
        }).toList();
    }

    /**
     * The results of these votes per answer: the shares in whole percents, the answers with the most votes leading (none at 0). Each
     * share is rounded on its own, so three of 33 % make 99: the room reads the bars and the leader, not the sum.
     */
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

    /**
     * Deletes the questions last used — made, asked or ended — more than {@value #KEPT_DAYS} days ago, their votes with them. Daily
     * at 04:55 Polish time.
     */
    @Scheduled(cron = "0 55 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void purge() {
        int deleted = purgeBefore(Instant.now().minus(Duration.ofDays(KEPT_DAYS)));
        // a line also for none: the log shows the purge ran
        log.info("Polls purge: {} question(s) deleted with their votes", deleted);
    }

    /**
     * {@link #purge}: the questions last used before {@code usedBefore}. The last use is the latest of the three moments, and each comes
     * after the one before (a question asked again has no {@code closed_at}): made before it ({@code idx_party_poll_created}) and the
     * last of the others before it too.
     */
    int purgeBefore(Instant usedBefore) {
        Timestamp before = Timestamp.from(usedBefore);
        return jdbc.update("DELETE FROM party_poll WHERE created_at < ? AND coalesce(closed_at, opened_at, created_at) < ?", before,
                before);
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
    public static String voterOf(HttpSession session) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(session.getId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
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

    /** The moment a question ended: the DJ's, or {@link #ASKED_FOR} after it was asked once that has passed; null while asked. */
    static Instant endOf(Instant openedAt, Instant closedAt, Instant now) {
        if (closedAt != null || openedAt == null) {
            return closedAt;
        }
        Instant ends = openedAt.plus(ASKED_FOR);
        return ends.isAfter(now) ? null : ends;
    }

    /** Everything counted or looked up again at once: for tests that change the rows by SQL, past this service. */
    void forgetCaches() {
        counts.invalidateAll();
        shownByParty.invalidateAll();
    }

    /** What the guests' page and the TV show is looked up again — once the change is in. */
    private void forgetShown(String partyCode) {
        AfterCommit.run(() -> shownByParty.invalidate(partyCode));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
