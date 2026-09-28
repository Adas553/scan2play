package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.repository.FallbackTrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * Database writes for the server-side fallback playlist. Kept separate from
 * {@link FallbackPlaylistService} so the (slow) YouTube API calls happen <em>outside</em> any
 * transaction — a DB connection is only held for the short swap below.
 */
@Service
@Slf4j
public class FallbackTrackCommandService {

    /** Upper bound of retries when concurrent callers keep grabbing the track we picked. */
    private static final int MAX_TAKE_ATTEMPTS = 5;

    private final FallbackTrackRepository fallbackTrackRepository;
    /** Returns a random int in {@code [0, bound)} — injectable so shuffle is testable. */
    private final IntUnaryOperator randomIndex;

    @Autowired
    public FallbackTrackCommandService(FallbackTrackRepository fallbackTrackRepository) {
        this(fallbackTrackRepository, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    FallbackTrackCommandService(FallbackTrackRepository fallbackTrackRepository, IntUnaryOperator randomIndex) {
        this.fallbackTrackRepository = fallbackTrackRepository;
        this.randomIndex = randomIndex;
    }

    /**
     * Replaces the party's fallback playlist: still-QUEUED tracks are flipped to CANCELLED
     * (soft invalidation — PLAYED rows stay as history) and the new tracks are inserted as QUEUED,
     * all in one transaction.
     *
     * @param playlistId source of the tracks (playlist ID or {@code V:<videoId>})
     * @param videoIds   video IDs in playlist order
     * @return number of tracks inserted
     */
    @Transactional
    public int replaceTracks(String partyCode, String playlistId, List<String> videoIds) {
        int cancelled = fallbackTrackRepository.updateStatus(
                partyCode, FallbackTrackStatus.QUEUED, FallbackTrackStatus.CANCELLED);

        LocalDateTime fetchedAt = LocalDateTime.now();
        List<FallbackTrackEntity> tracks = IntStream.range(0, videoIds.size())
                .mapToObj(i -> FallbackTrackEntity.builder()
                        .partyCode(partyCode)
                        .playlistId(playlistId)
                        .videoId(videoIds.get(i))
                        .playlistPosition(i)
                        .status(FallbackTrackStatus.QUEUED)
                        .fetchedAt(fetchedAt)
                        .build())
                .toList();
        fallbackTrackRepository.saveAll(tracks);

        log.info("Party [{}]: fallback playlist {} imported — {} track(s) queued, {} previous track(s) cancelled",
                partyCode, playlistId, tracks.size(), cancelled);
        return tracks.size();
    }

    /** The DJ cleared the fallback playlist: nothing queued stays queued. */
    @Transactional
    public void cancelQueuedTracks(String partyCode) {
        int cancelled = fallbackTrackRepository.updateStatus(
                partyCode, FallbackTrackStatus.QUEUED, FallbackTrackStatus.CANCELLED);
        log.info("Party [{}]: fallback playlist cleared — {} queued track(s) cancelled", partyCode, cancelled);
    }

    /**
     * Hands out the next background track of the party's <em>current</em> playlist and marks it PLAYED.
     * <ul>
     *     <li>{@code shuffle}: a random still-queued track; otherwise the queued one with the lowest
     *         playlist position.</li>
     *     <li>When every track has been played the playlist loops: the played tracks of the party's newest
     *         import are put back in the queue (no API call).</li>
     *     <li>The track is claimed with a single conditional UPDATE, so two concurrent callers never get
     *         the same one.</li>
     * </ul>
     * Only tracks of {@code playlistId} are considered: after the DJ switches playlists, tracks of an old
     * playlist must never play — even if the import of the new one has not succeeded yet.
     *
     * @return the claimed track, or empty if the playlist has no tracks to play (never imported / all cancelled)
     */
    @Transactional
    public Optional<FallbackTrackEntity> takeNextTrack(String partyCode, String playlistId, boolean shuffle) {
        boolean requeued = false;
        for (int attempt = 0; attempt < MAX_TAKE_ATTEMPTS; attempt++) {
            long queued = fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(
                    partyCode, playlistId, FallbackTrackStatus.QUEUED);

            if (queued == 0) {
                if (requeued || !requeuePlayedTracks(partyCode, playlistId)) {
                    return Optional.empty();
                }
                requeued = true;
                continue;
            }

            int offset = shuffle ? randomIndex.applyAsInt((int) queued) : 0;
            List<FallbackTrackEntity> page = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                    partyCode, playlistId, FallbackTrackStatus.QUEUED,
                    PageRequest.of(offset, 1, Sort.by("playlistPosition")));
            if (page.isEmpty()) {
                continue; // the queue shrank under us — recount
            }

            FallbackTrackEntity track = page.get(0);
            int claimed = fallbackTrackRepository.claimQueuedTrack(
                    track.getId(), FallbackTrackStatus.QUEUED, FallbackTrackStatus.PLAYED, LocalDateTime.now());
            if (claimed == 1) {
                return Optional.of(track);
            }
            // lost the race for this track — pick again
        }
        return Optional.empty();
    }

    /**
     * Loops the playlist: re-queues the played tracks of the party's newest import — provided that import is
     * of this playlist (a superseded import is never revived, see the repository query).
     */
    private boolean requeuePlayedTracks(String partyCode, String playlistId) {
        int requeued = fallbackTrackRepository.requeuePlayedTracks(
                partyCode, playlistId, FallbackTrackStatus.PLAYED, FallbackTrackStatus.QUEUED);
        if (requeued > 0) {
            log.debug("Party [{}]: fallback playlist {} finished — {} track(s) back in the queue",
                    partyCode, playlistId, requeued);
        }
        return requeued > 0;
    }

    /**
     * Deletes tracks fetched more than {@value FallbackTrackEntity#MAX_AGE_DAYS} days ago — YouTube API data
     * must not be retained longer. Runs daily at 04:30.
     */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void purgeStaleTracks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);
        int deleted = fallbackTrackRepository.deleteFetchedBefore(cutoff);
        if (deleted > 0) {
            log.info("Fallback track cleanup: deleted {} track(s) older than {} days",
                    deleted, FallbackTrackEntity.MAX_AGE_DAYS);
        }
    }
}
