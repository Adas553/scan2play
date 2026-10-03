package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.FallbackPlayEntity;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side "what plays next?" decision for YouTube Auto-Pilot (PROJECT_CONTEXT.md Section 14):
 * <ol>
 *     <li>the oldest waiting guest song, if there is one;</li>
 *     <li>otherwise the next track of the party's fallback ("background music") playlist.</li>
 * </ol>
 * <p>
 * Background tracks normally exist already — they are imported when the DJ sets the playlist. This class
 * additionally imports <b>lazily</b> when there is nothing to play (a playlist set before server-side import existed, an
 * earlier import that failed) — on the request thread, as there is nothing else to play meanwhile — and <b>refreshes</b>
 * tracks older than {@value #REFRESH_AFTER_DAYS} days (before the 30-day retention limit purges them) on a background
 * thread, in place: the old tracks keep playing while the YouTube API answers, and the round goes on (review item 2.5).
 * <p>
 * Both are guarded: at most one import or refresh per party+playlist at a time, and after a failure no new attempt for
 * {@value #RETRY_COOLDOWN_MINUTES} minutes (a missing API key or an exhausted quota must not be re-tried on every request).
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

        boolean shuffle = settings.isFallbackShuffle();
        refreshIfStale(partyCode, playlistId, shuffle);

        Optional<FallbackPlayEntity> play = fallbackTrackCommandService.takeNextTrack(partyCode, playlistId, shuffle);
        if (play.isEmpty() && tryImport(partyCode, playlistId, shuffle)) {
            play = fallbackTrackCommandService.takeNextTrack(partyCode, playlistId, shuffle);
        }
        // The id is the play log row's, not the track's: the client keeps it as B:<id> and finds the track by it in the
        // history (recent-tracks), so it has to be the id the history entry of this very play has.
        return play.map(p -> new NextTrackResponse(Source.BACKGROUND, p.getId(), p.getVideoId(), playlistId));
    }

    /**
     * Starts a background refresh of a playlist whose tracks are close to the 30-day retention limit and returns at once:
     * the caller goes on with the tracks there are.
     */
    private void refreshIfStale(String partyCode, String playlistId, boolean shuffle) {
        Optional<Instant> fetchedAt = fallbackTrackRepository.findLatestFetchedAt(partyCode, playlistId);
        if (fetchedAt.isEmpty() || !fetchedAt.get().isBefore(Instant.now().minus(REFRESH_AFTER_DAYS, ChronoUnit.DAYS))) {
            return;
        }
        String key = partyCode + ':' + playlistId;
        if (recentImportFailures.getIfPresent(key) != null || !importsInFlight.add(key)) {
            return;
        }
        log.info("Party [{}]: fallback playlist {} was fetched at {} — refreshing in the background",
                partyCode, playlistId, fetchedAt.get());
        try {
            fallbackPlaylistService.refreshFallbackTracksInBackground(partyCode, playlistId, shuffle)
                    .whenComplete((count, failure) -> {
                        importsInFlight.remove(key);
                        if (failure != null) {
                            refreshFailed(key, partyCode, playlistId, failure);
                        }
                    });
        } catch (RuntimeException e) {
            // the executor refused the task (it is full) — the next request tries again
            importsInFlight.remove(key);
            log.warn("Party [{}]: the refresh of fallback playlist {} could not be started: {}", partyCode, playlistId, e.toString());
        }
    }

    private void refreshFailed(String key, String partyCode, String playlistId, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        recentImportFailures.put(key, Boolean.TRUE);
        if (cause instanceof FallbackImportException e) {
            log.warn("Party [{}]: the refresh of fallback playlist {} failed ({}): {} — the old tracks play on, not retrying for {} min",
                    partyCode, playlistId, e.getReason(), e.getMessage(), RETRY_COOLDOWN_MINUTES);
        } else {
            log.error("Party [{}]: the refresh of fallback playlist {} failed — the old tracks play on, not retrying for {} min",
                    partyCode, playlistId, RETRY_COOLDOWN_MINUTES, cause);
        }
    }

    /**
     * Imports the playlist unless an import is already running for it or one failed recently.
     *
     * @return true if tracks were imported
     */
    private boolean tryImport(String partyCode, String playlistId, boolean shuffle) {
        String key = partyCode + ':' + playlistId;
        if (recentImportFailures.getIfPresent(key) != null || !importsInFlight.add(key)) {
            return false;
        }
        try {
            log.info("Party [{}]: importing fallback playlist {} on demand", partyCode, playlistId);
            fallbackPlaylistService.syncFallbackTracks(partyCode, playlistId, shuffle);
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
