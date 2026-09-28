package com.scan2play.service;

import com.scan2play.service.FallbackImportException.Reason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Keeps a party's server-side fallback tracks (table {@code fallback_track}) in sync with the
 * playlist the DJ configured.
 * <p>
 * Order of operations matters: the YouTube API is called <b>first</b>, and the database is only
 * written once a complete, non-empty result is in hand. A failed import therefore never disturbs
 * the tracks that are already queued (graceful degradation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FallbackPlaylistService {

    /** Prefix used by {@code DjDashboardController#extractPlaylistId} to mark a single video. */
    private static final String SINGLE_VIDEO_PREFIX = "V:";

    private final YouTubePlaylistClient playlistClient;
    private final FallbackTrackCommandService trackCommandService;

    /**
     * @param partyCode  the party
     * @param playlistId what {@code DjDashboardController#extractPlaylistId} returned: a playlist ID,
     *                   {@code V:<videoId>} for a single video, or {@code null}/blank when cleared
     * @return number of tracks now queued (0 when the playlist was cleared)
     * @throws FallbackImportException if the playlist could not be imported; existing tracks are untouched
     */
    public int syncFallbackTracks(String partyCode, String playlistId) {
        if (playlistId == null || playlistId.isBlank()) {
            trackCommandService.cancelQueuedTracks(partyCode);
            return 0;
        }

        // A single video needs no API call (and therefore no API key).
        List<String> videoIds = playlistId.startsWith(SINGLE_VIDEO_PREFIX)
                ? List.of(playlistId.substring(SINGLE_VIDEO_PREFIX.length()))
                : playlistClient.fetchPlayableVideoIds(playlistId);

        if (videoIds.isEmpty()) {
            throw new FallbackImportException(Reason.NO_PLAYABLE_TRACKS,
                    "Playlist has no public, embeddable videos");
        }
        return trackCommandService.replaceTracks(partyCode, playlistId, videoIds);
    }
}
