package com.scan2play.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * A global daily fuse for the YouTube Data API {@code search.list} calls.
 * <p>
 * {@code search.list} has a limit of its own per Google project (100 calls a day by default), shared by every party. Once it is
 * spent, no new guest request on any party gets a video ID until the quota resets at midnight Pacific time. This fuse stops
 * calling the API a little before that ({@code youtube.search.daily-budget}), so the rest of the day is not spent on 403s, and it
 * trips at once when Google answers {@code quotaExceeded}.
 * <p>
 * The count of the day is kept in the database ({@code youtube_search_budget}, one row per day, V10), so a restart — a deploy, a
 * devtools restart — does not give the day's budget back, and two instances would share it: taking a search is one atomic
 * statement. Whether the day is spent is also kept in memory, as the DJ's dashboard asks on every poll.
 * <p>
 * The day is Google's: it starts at midnight in {@code America/Los_Angeles}. A budget of 0 or less switches the fuse off.
 */
@Service
@Slf4j
public class YouTubeSearchBudget {

    /** Google resets the YouTube Data API quota at midnight Pacific time. */
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    /** How long the rows of past days are kept (they only say how many searches a day took). */
    static final int KEEP_DAYS = 30;

    private final int dailyBudget;
    private final Clock clock;
    private final Counter counter;

    /** The day whose state is known in memory, and whether it is spent (read by {@link #isSpent} without the database). */
    private LocalDate knownDay;
    private boolean knownSpent;

    @Autowired
    public YouTubeSearchBudget(@Value("${youtube.search.daily-budget:80}") int dailyBudget, JdbcTemplate jdbc) {
        this(dailyBudget, Clock.systemUTC(), new DatabaseCounter(jdbc));
    }

    /** In memory only, for the tests: every instance starts the day afresh. */
    YouTubeSearchBudget(int dailyBudget) {
        this(dailyBudget, Clock.systemUTC());
    }

    YouTubeSearchBudget(int dailyBudget, Clock clock) {
        this(dailyBudget, clock, new MemoryCounter());
    }

    YouTubeSearchBudget(int dailyBudget, Clock clock, Counter counter) {
        this.dailyBudget = dailyBudget;
        this.clock = clock;
        this.counter = counter;
    }

    /**
     * Takes one search from today's budget.
     *
     * @return {@code true} if the API may be called, {@code false} if today's budget is spent or Google said the quota is exceeded
     */
    public synchronized boolean tryAcquire() {
        LocalDate today = today();
        if (isSpent(today)) {
            return false;
        }
        int used = counter.tryTake(today, dailyBudget);
        if (used < 0) {
            remember(today, true);
            log.warn("YouTube search budget of {} calls for {} is spent: new songs get a search link until midnight Pacific time",
                    dailyBudget, today);
            return false;
        }
        boolean spentNow = dailyBudget > 0 && used >= dailyBudget;
        if (spentNow) {
            log.warn("YouTube search budget of {} calls for {} is spent with this one: new songs get a search link until midnight "
                    + "Pacific time", dailyBudget, today);
        }
        remember(today, spentNow);
        return true;
    }

    /** Whether today's searches are spent (for the DJ's dashboard); takes nothing from the budget. */
    public synchronized boolean isSpent() {
        return isSpent(today());
    }

    /** Google answered {@code quotaExceeded}: stop calling the API until the quota resets. */
    public synchronized void markQuotaExceeded() {
        LocalDate today = today();
        if (!isSpent(today)) {
            log.warn("YouTube answered quotaExceeded on {}: no more searches until midnight Pacific time", today);
        }
        counter.markExhausted(today);
        remember(today, true);
    }

    private boolean isSpent(LocalDate day) {
        if (!day.equals(knownDay)) {   // a new day (or the first ask since the start): once from the database
            remember(day, counter.isSpent(day, dailyBudget));
        }
        return knownSpent;
    }

    private void remember(LocalDate day, boolean spent) {
        knownDay = day;
        knownSpent = spent;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), QUOTA_ZONE);
    }

    /** Where the count of a day is kept. */
    interface Counter {

        /**
         * Takes one search of the day unless the day is exhausted or {@code budget} searches are taken already
         * ({@code budget <= 0}: no limit).
         *
         * @return the number of searches taken that day, this one included, or -1 if refused
         */
        int tryTake(LocalDate day, int budget);

        void markExhausted(LocalDate day);

        boolean isSpent(LocalDate day, int budget);
    }

    /** The count of one instance, lost at a restart (the tests). */
    static final class MemoryCounter implements Counter {
        private LocalDate day;
        private int used;
        private boolean exhausted;

        @Override
        public int tryTake(LocalDate day, int budget) {
            startDay(day);
            if (exhausted || (budget > 0 && used >= budget)) {
                return -1;
            }
            return ++used;
        }

        @Override
        public void markExhausted(LocalDate day) {
            startDay(day);
            exhausted = true;
        }

        @Override
        public boolean isSpent(LocalDate day, int budget) {
            startDay(day);
            return exhausted || (budget > 0 && used >= budget);
        }

        private void startDay(LocalDate day) {
            if (!day.equals(this.day)) {
                this.day = day;
                used = 0;
                exhausted = false;
            }
        }
    }

    /** The count in {@code youtube_search_budget}: it survives a restart and is shared by every instance. */
    static final class DatabaseCounter implements Counter {
        private final JdbcTemplate jdbc;

        DatabaseCounter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public int tryTake(LocalDate day, int budget) {
            // one statement: the first search of the day inserts the row, the others count up while the day allows it
            List<Integer> used = jdbc.queryForList("INSERT INTO youtube_search_budget (day, used, exhausted) VALUES (?, 1, false) "
                    + "ON CONFLICT (day) DO UPDATE SET used = youtube_search_budget.used + 1 "
                    + "WHERE NOT youtube_search_budget.exhausted AND (? <= 0 OR youtube_search_budget.used < ?) "
                    + "RETURNING used", Integer.class, Date.valueOf(day), budget, budget);
            return used.isEmpty() ? -1 : used.get(0);
        }

        @Override
        public void markExhausted(LocalDate day) {
            jdbc.update("INSERT INTO youtube_search_budget (day, used, exhausted) VALUES (?, 0, true) "
                    + "ON CONFLICT (day) DO UPDATE SET exhausted = true", Date.valueOf(day));
        }

        @Override
        public boolean isSpent(LocalDate day, int budget) {
            jdbc.update("DELETE FROM youtube_search_budget WHERE day < ?", Date.valueOf(day.minusDays(KEEP_DAYS)));
            List<Boolean> spent = jdbc.queryForList("SELECT exhausted OR (? > 0 AND used >= ?) FROM youtube_search_budget WHERE day = ?",
                    Boolean.class, budget, budget, Date.valueOf(day));
            return !spent.isEmpty() && spent.get(0);
        }
    }
}
