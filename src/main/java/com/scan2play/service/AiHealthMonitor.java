package com.scan2play.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many guests' requests the AI answered and how many went on to the DJ unchecked (Gemini failed or timed out), written to the
 * log every {@value #REPORT_MINUTES} minutes when any went unchecked — so a Gemini outage shows in the log (Railway's log search:
 * "AI check") instead of in a DJ's message the next day. One error line per period, however many requests failed in it; each
 * failure also has its own line from {@code SongEvaluationService}. Nothing is written while all is well. In memory: a restart
 * starts the counts again.
 */
@Service
@Slf4j
public class AiHealthMonitor {

    static final int REPORT_MINUTES = 5;

    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong unchecked = new AtomicLong();

    /** The AI answered a request (whatever it decided). */
    public void recordAnswered() {
        answered.incrementAndGet();
    }

    /** The AI could not be asked: the request went to the DJ unchecked. */
    public void recordUnchecked() {
        unchecked.incrementAndGet();
    }

    @Scheduled(fixedRate = REPORT_MINUTES * 60_000L, initialDelay = REPORT_MINUTES * 60_000L)
    public void report() {
        String summary = takeSummary();
        if (summary != null) {
            log.error(summary);
        }
    }

    /**
     * The period's line, or null when no request went unchecked in it; the counts start again from zero either way. A request
     * counted while this runs lands in this period or the next, never in neither.
     */
    String takeSummary() {
        long failed = unchecked.getAndSet(0);
        long ok = answered.getAndSet(0);
        if (failed == 0) {
            return null;
        }
        return "AI check: " + failed + " of " + (failed + ok) + " guest requests in the last " + REPORT_MINUTES
                + " min went to the DJ unchecked — the AI (Gemini) did not answer";
    }
}
