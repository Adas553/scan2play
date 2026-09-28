package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.model.NextTrackResponse;
import com.scan2play.model.NextTrackResponse.Source;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.util.YouTubeUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side "what plays next?" decision for YouTube Auto-Pilot (PROJECT_CONTEXT.md Section 14):
 * <ol>
 *     <li>the oldest waiting guest song, if there is one;</li>
 *     <li>otherwise the next track of the party's fallback ("background music") playlist.</li>
 * </ol>
 * <p>
 * Background tracks normally exist already — they are imported when the DJ sets the playlist. This class
 * additionally imports <b>lazily</b> when there is nothing to play: a playlist set before server-side import
 * existed, an earlier import that failed, or tracks older than 29 days (they must be refreshed before the
 * 30-day retention limit purges them).
 * <p>
 * A lazy import calls the YouTube API from a request thread, so it is guarded: at most one import per
 * party+playlist at a time, and after a failure no new attempt for {@value #RETRY_COOLDOWN_MINUTES} minutes
 * (a missing API key or an exhausted quota must not be re-tried on every request).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NextTrackService {

    static final int RETRY_COOLDOWN_MINUTES = 5;
    /** One day before the 30-day retention limit, so a running party never loses its playlist. */
    static final int REFRESH_AFTER_DAYS = FallbackTrackEntity.MAX_AGE_DAYS - 1;

    private final DjService djService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final FallbackPlaylistService fallbackPlaylistService;
    private final FallbackTrackCommandService fallbackTrackCommandService;
    private final FallbackTrackRepository fallbackTrackRepository;

    private final Cache<String, Boolean> recentImportFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(RETRY_COOLDOWN_MINUTES))
            .maximumSize(1_000)
            .build();
    private final Set<String> importsInFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param excludedGuestSongIds guest songs the client knows are broken (its player errored on them)
     * @return the next track, or empty when neither a guest song nor a background track is available
     */
    public Optional<NextTrackResponse> findNextTrack(String partyCode, Set<Long> excludedGuestSongIds) {
        Optional<NextGuestTrackResponse> guest = djService.findNextPlayableGuestTrack(partyCode, excludedGuestSongIds);
        if (guest.isPresent()) {
            return guest.map(g -> new NextTrackResponse(Source.GUEST, g.songId(), g.videoId()));
        }
        return findNextBackgroundTrack(partyCode);
    }

    private Optional<NextTrackResponse> findNextBackgroundTrack(String partyCode) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        if (playlistId == null) {
            return Optional.empty(); // the DJ has no fallback playlist
        }

        refreshIfStale(partyCode, playlistId);

        boolean shuffle = settings.isFallbackShuffle();
        Optional<FallbackTrackEntity> track = fallbackTrackCommandService.takeNextTrack(partyCode, playlistId, shuffle);
        if (track.isEmpty() && tryImport(partyCode, playlistId)) {
            track = fallbackTrackCommandService.takeNextTrack(partyCode, playlistId, shuffle);
        }
        return track.map(t -> new NextTrackResponse(Source.BACKGROUND, t.getId(), t.getVideoId()));
    }

    /** Re-imports a playlist whose tracks are close to the 30-day retention limit. */
    private void refreshIfStale(String partyCode, String playlistId) {
        Optional<LocalDateTime> fetchedAt = fallbackTrackRepository.findLatestFetchedAt(partyCode, playlistId);
        if (fetchedAt.isPresent() && fetchedAt.get().isBefore(LocalDateTime.now().minusDays(REFRESH_AFTER_DAYS))) {
            log.info("Party [{}]: fallback playlist {} was fetched at {} — refreshing", partyCode, playlistId, fetchedAt.get());
            tryImport(partyCode, playlistId);
        }
    }

    /**
     * Imports the playlist unless an import is already running for it or one failed recently.
     *
     * @return true if tracks were imported
     */
    private boolean tryImport(String partyCode, String playlistId) {
        String key = partyCode + ':' + playlistId;
        if (recentImportFailures.getIfPresent(key) != null || !importsInFlight.add(key)) {
            return false;
        }
        try {
            log.info("Party [{}]: importing fallback playlist {} on demand", partyCode, playlistId);
            fallbackPlaylistService.syncFallbackTracks(partyCode, playlistId);
            return true;
        } catch (FallbackImportException e) {
            recentImportFailures.put(key, Boolean.TRUE);
            log.warn("Party [{}]: on-demand import of fallback playlist {} failed ({}): {} — not retrying for {} min",
                    partyCode, playlistId, e.getReason(), e.getMessage(), RETRY_COOLDOWN_MINUTES);
            return false;
        } finally {
            importsInFlight.remove(key);
        }
    }
}
