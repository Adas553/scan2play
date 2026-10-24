package com.scan2play.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How many guests' requests the AI answered and how many went on to the DJ unchecked (Gemini failed or timed out), written to the
 * log every {@value #REPORT_MINUTES} minutes when any went unchecked — so a Gemini outage shows in the log (Railway's log search:
 * "AI check") instead of in a DJ's message the next day. One error line per period, however many requests failed in it; each
 * failure also has its own line from {@code SongEvaluationService}. Nothing is written while all is well. In memory: a restart
 * starts the counts again.
 * <p>
 * Nobody reads that log as it is written, so {@value #DOWN_AFTER} unchecked requests in a row — an outage, not one timeout: with
 * Gemini's prepaid credits at zero (402) every request goes unchecked — mean the AI is down (the review of 2026-10-21, O2; the owner,
 * 2026-10-24): the site's owner ({@code scan2play.admin-id}) gets one push on their devices, at most every
 * {@value #ALARM_EVERY_HOURS} hours, and every panel shows a bar above the queue ({@link #isAiDown}) until the AI answers again, or
 * for an hour after the last unchecked request when no request comes.
 */
@Service
@Slf4j
public class AiHealthMonitor {

    static final int REPORT_MINUTES = 5;
    /** Unchecked requests in a row that mean the AI is down. */
    static final int DOWN_AFTER = 3;
    static final int ALARM_EVERY_HOURS = 6;
    /** With no request since the last unchecked one, nothing says the AI is still down. */
    private static final Duration DOWN_FOR_AT_MOST = Duration.ofHours(1);

    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong unchecked = new AtomicLong();
    private final AtomicInteger uncheckedInARow = new AtomicInteger();
    private volatile Instant lastUnchecked = Instant.MIN;
    private Instant lastAlarm = Instant.MIN;

    private final PushNotificationService push;
    private final String ownerId;
    private final Clock clock;

    @Autowired
    public AiHealthMonitor(PushNotificationService push, @Value("${scan2play.admin-id:}") String ownerId) {
        this(push, ownerId, Clock.systemUTC());
    }

    AiHealthMonitor(PushNotificationService push, String ownerId, Clock clock) {
        this.push = push;
        this.ownerId = ownerId == null ? "" : ownerId.strip();
        this.clock = clock;
    }

    /** Without the alarm's push (the tests of the services and controllers that only count). */
    public AiHealthMonitor() {
        this(null, "", Clock.systemUTC());
    }

    /** The AI answered a request (whatever it decided): the row of unchecked ones is broken, the AI is up. */
    public void recordAnswered() {
        answered.incrementAndGet();
        uncheckedInARow.set(0);
    }

    /** The AI could not be asked: the request went to the DJ unchecked — the {@value #DOWN_AFTER}th in a row tells the owner. */
    public void recordUnchecked() {
        unchecked.incrementAndGet();
        Instant now = clock.instant();
        lastUnchecked = now;
        if (uncheckedInARow.incrementAndGet() == DOWN_AFTER) {
            alarm(now);
        }
    }

    /** Whether the panels show "AI chwilowo nie ocenia próśb": {@value #DOWN_AFTER} unchecked in a row, the last within an hour. */
    public boolean isAiDown() {
        return uncheckedInARow.get() >= DOWN_AFTER && clock.instant().isBefore(lastUnchecked.plus(DOWN_FOR_AT_MOST));
    }

    private synchronized void alarm(Instant now) {
        log.error("AI check: {} guest requests in a row went to the DJ unchecked — the AI (Gemini) is down", DOWN_AFTER);
        if (push == null || ownerId.isEmpty() || now.isBefore(lastAlarm.plus(Duration.ofHours(ALARM_EVERY_HOURS)))) {
            return;
        }
        lastAlarm = now;
        push.notifyAiDown(ownerId);
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
