package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Deletes the guests' song requests {@value SongRequestEntity#MAX_AGE_DAYS} days after they were made — the retention the
 * privacy pages promise. Before this only the deletion of a DJ account removed them, while the pages said "for the duration of
 * the party session"; and {@code song_requests.track_url} holds YouTube video IDs that came from the YouTube API, which may be
 * kept for 30 calendar days at most (API Services Developer Policies, III.E.4.d — the same rule as for the playlist tracks
 * and the play log, {@link FallbackTrackCommandService#purgeStaleTracks}).
 * <p>
 * The purge is counted from {@code requested_at} (the table has no other date) and is bounded: it deletes in batches of
 * {@value #BATCH_SIZE}, each in its own transaction, and at most {@value #MAX_BATCHES} batches a night — the rest waits for
 * the next night. So the first run over a long backlog cannot hold locks for long or run for ever.
 * <p>
 * It also removes the guests' part of the DJ history for good (played and rejected requests are the same rows), which is why
 * that history reaches back 30 days at most, like the playlist's.
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

    /** Runs daily at 04:45, after the YouTube cache cleanup (04:00) and the playlist purge (04:30). */
    @Scheduled(cron = "0 45 4 * * *")
    public void purgeStaleRequests() {
        purgeRequestedBefore(LocalDateTime.now().minusDays(SongRequestEntity.MAX_AGE_DAYS));
    }

    /**
     * Deletes the requests made before {@code cutoff}, batch by batch.
     *
     * @return how many were deleted
     */
    int purgeRequestedBefore(LocalDateTime cutoff) {
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
