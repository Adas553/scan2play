package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final SongRequestRepository songRequestRepository;

    /** Runs daily at 04:45. */
    @Scheduled(cron = "0 45 4 * * *")
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
        if (deleted > 0) {
            log.info("Song request cleanup: deleted {} request(s) made more than {} days ago", deleted, SongRequestEntity.MAX_AGE_DAYS);
        }
        return deleted;
    }
}
