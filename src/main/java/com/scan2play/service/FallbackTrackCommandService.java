package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.repository.FallbackTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Database writes for the server-side fallback playlist. Kept separate from
 * {@link FallbackPlaylistService} so the (slow) YouTube API calls happen <em>outside</em> any
 * transaction — a DB connection is only held for the short swap below.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FallbackTrackCommandService {

    private final FallbackTrackRepository fallbackTrackRepository;

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
