package com.scan2play.service;

import com.scan2play.model.PlaylistTrack;
import com.scan2play.service.FallbackImportException.Reason;
import com.scan2play.util.YouTubeUrls;
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

    private static final String SINGLE_VIDEO_PREFIX = YouTubeUrls.SINGLE_VIDEO_PREFIX;

    private final YouTubePlaylistClient playlistClient;
    private final FallbackTrackCommandService trackCommandService;

    /**
     * @param partyCode  the party
     * @param playlistId what {@link YouTubeUrls#extractPlaylistId} returned: a playlist ID,
     *                   {@code V:<videoId>} for a single video, or {@code null}/blank when cleared
     * @param shuffle    whether the imported tracks are put in a random order (the DJ's shuffle setting)
     * @return number of tracks now queued (0 when the playlist was cleared)
     * @throws FallbackImportException if the playlist could not be imported; existing tracks are untouched
     */
    public int syncFallbackTracks(String partyCode, String playlistId, boolean shuffle) {
        if (playlistId == null || playlistId.isBlank()) {
            trackCommandService.cancelQueuedTracks(partyCode);
            return 0;
        }

        // A single video needs no API call to be played (and therefore no API key); its title is looked up
        // best-effort, so without a key it is simply stored without one.
        List<PlaylistTrack> tracks;
        if (playlistId.startsWith(SINGLE_VIDEO_PREFIX)) {
            String videoId = playlistId.substring(SINGLE_VIDEO_PREFIX.length());
            tracks = List.of(new PlaylistTrack(videoId, playlistClient.findTitle(videoId).orElse(null)));
        } else {
            tracks = playlistClient.fetchPlayableTracks(playlistId);
        }

        if (tracks.isEmpty()) {
            throw new FallbackImportException(Reason.NO_PLAYABLE_TRACKS,
                    "Playlist has no public, embeddable videos");
        }
        return trackCommandService.replaceTracks(partyCode, playlistId, tracks, shuffle);
    }

    /**
     * The DJ switched shuffle on or off: re-orders the tracks of the current playlist that are still queued.
     * No YouTube API call — only the order in the database changes.
     */
    public void applyShuffleSetting(String partyCode, String playlistId, boolean shuffle) {
        trackCommandService.applyShuffleSetting(partyCode, playlistId, shuffle);
    }
}
