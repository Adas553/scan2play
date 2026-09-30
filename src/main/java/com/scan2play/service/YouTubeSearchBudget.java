package com.scan2play.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A global daily fuse for the YouTube Data API {@code search.list} calls.
 * <p>
 * {@code search.list} has a limit of its own per Google project (100 calls a day by default), shared by every party. Once it is
 * spent, no new guest request on any party gets a video ID until the quota resets at midnight Pacific time. This fuse stops
 * calling the API a little before that ({@code youtube.search.daily-budget}), so the rest of the day is not spent on 403s, and it
 * trips at once when Google answers {@code quotaExceeded} (the count starts over at every restart; the 403 is the backstop).
 * <p>
 * The day is Google's: it starts at midnight in {@code America/Los_Angeles}. A budget of 0 or less switches the fuse off.
 */
@Service
@Slf4j
public class YouTubeSearchBudget {

    /** Google resets the YouTube Data API quota at midnight Pacific time. */
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final int dailyBudget;
    private final Clock clock;

    private LocalDate day;
    private int used;
    private boolean exhausted;

    @Autowired
    public YouTubeSearchBudget(@Value("${youtube.search.daily-budget:80}") int dailyBudget) {
        this(dailyBudget, Clock.systemUTC());
    }

    YouTubeSearchBudget(int dailyBudget, Clock clock) {
        this.dailyBudget = dailyBudget;
        this.clock = clock;
    }

    /**
     * Takes one search from today's budget.
     *
     * @return {@code true} if the API may be called, {@code false} if today's budget is spent or Google said the quota is exceeded
     */
    public synchronized boolean tryAcquire() {
        startNewDayIfNeeded();
        if (exhausted) {
            return false;
        }
        if (dailyBudget > 0 && used >= dailyBudget) {
            exhausted = true;
            log.warn("YouTube search budget of {} calls for {} is spent: new songs get a search link until midnight Pacific time",
                    dailyBudget, day);
            return false;
        }
        used++;
        return true;
    }

    /** Whether today's searches are spent (for the DJ's dashboard); takes nothing from the budget. */
    public synchronized boolean isSpent() {
        startNewDayIfNeeded();
        return exhausted || (dailyBudget > 0 && used >= dailyBudget);
    }

    /** Google answered {@code quotaExceeded}: stop calling the API until the quota resets. */
    public synchronized void markQuotaExceeded() {
        startNewDayIfNeeded();
        if (!exhausted) {
            log.warn("YouTube answered quotaExceeded after {} searches counted for {}: no more searches until midnight Pacific time",
                    used, day);
        }
        exhausted = true;
    }

    private void startNewDayIfNeeded() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), QUOTA_ZONE);
        if (!today.equals(day)) {
            day = today;
            used = 0;
            exhausted = false;
        }
    }
}
