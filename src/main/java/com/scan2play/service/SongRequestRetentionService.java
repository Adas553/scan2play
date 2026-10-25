package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes the guests' song requests {@value SongRequestEntity#MAX_AGE_DAYS} days after they were made — the retention the
 * privacy pages promise (the requests hold what guests typed; a party's history needs no more than a month).
 * <p>
 * The purge is counted from {@code requested_at} (the table has no other date) and is bounded: it deletes in batches of
 * {@value #BATCH_SIZE}, each in its own transaction, and at most {@value #MAX_BATCHES} batches a night — the rest waits for
 * the next night. So the first run over a long backlog cannot hold locks for long or run for ever.
 * <p>
 * It also removes the DJ history for good (played and rejected requests are the same rows), which is why that history reaches
 * back 30 days at most.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SongRequestRetentionService {

    /** Rows per DELETE: one short transaction each. */
    static final int BATCH_SIZE = 1000;

    /** Batches per run: at most {@code BATCH_SIZE * MAX_BATCHES} rows a night (200 000), the rest goes the next night. */
    static final int MAX_BATCHES = 200;

    /** How long a request may wait in the queue: past it, it is not the evening's any more (W10). */
    static final Duration WAITING_KEPT = Duration.ofHours(24);

    private final SongRequestRepository songRequestRepository;

    /**
     * Both cleanups after a start as well: a night the app was down at 04:40 (a deploy, a crash, a sleeping service) would leave
     * the old requests a day longer. Only what has waited a day or is a month old is touched — never tonight's queue. A failure is
     * logged and never stops the start; the night tries again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        runAtStart("Queue cleanup", this::clearOldWaitingRequests);
        runAtStart("Song request cleanup", this::purgeStaleRequests);
    }

    private static void runAtStart(String what, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.warn("{} at start failed, the night's run tries again", what, e);
        }
    }

    /**
     * Clears from every queue what has waited more than {@link #WAITING_KEPT} (review 2026-10-14 W10) — as "Wyczyść kolejkę" does:
     * rejected, cleared, kept in the history. The queue is the party's 300 oldest waiting requests and a DJ keeps one party code for
     * every event, so without "Wyczyść kolejkę" last Saturday's requests stood above tonight's and, past 300, hid them (and a song
     * asked for again among the hidden ones became a second row). A day is longer than any evening and shorter than the gap to the
     * next one. Runs daily at 04:40, before the purge.
     */
    @Scheduled(cron = "0 40 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void clearOldWaitingRequests() {
        Instant now = Instant.now();
        int cleared = songRequestRepository.clearWaitingRequestedBefore(now.minus(WAITING_KEPT), now);
        // a line also for none: the log shows that the cleanup ran (the privacy policy promises it)
        log.info("Queue cleanup: {} request(s) waiting more than {} h cleared from the queues", cleared, WAITING_KEPT.toHours());
    }

    /** Runs daily at 04:45. */
    @Scheduled(cron = "0 45 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void purgeStaleRequests() {
        purgeRequestedBefore(Instant.now().minus(SongRequestEntity.MAX_AGE_DAYS, ChronoUnit.DAYS));
    }

    /**
     * Deletes the requests made before {@code cutoff}, batch by batch.
     *
     * @return how many were deleted
     */
    int purgeRequestedBefore(Instant cutoff) {
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int n = songRequestRepository.deleteRequestedBefore(cutoff, BATCH_SIZE);
            deleted += n;
            if (n < BATCH_SIZE) {
                break;   // nothing older is left
            }
        }
        // a line also for none: the log shows that the 30-day purge the privacy policy promises ran
        log.info("Song request cleanup: deleted {} request(s) made more than {} days ago", deleted, SongRequestEntity.MAX_AGE_DAYS);
        return deleted;
    }
}
