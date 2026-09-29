package com.scan2play.model;

/**
 * Server-side "what plays next?" answer for the YouTube Auto-Pilot client
 * ({@code POST /dj/dashboard/next-track}, PROJECT_CONTEXT.md Section 14).
 * <p>
 * A waiting guest song always wins; otherwise the next fallback ("background music") track is chosen.
 *
 * @param source  where the track comes from
 * @param id      {@link Source#GUEST}: the {@code SongRequestEntity} id — the client still confirms
 *                playback with {@code POST /dj/dashboard/play}; {@link Source#BACKGROUND}: the id of the play log row
 *                ({@code FallbackPlayEntity}) that was written when the track was handed out — <em>not</em> the id of
 *                the queue's track, so that two plays of one video have two ids, and the client's key {@code B:<id>}
 *                is the key of exactly that entry of the history (see {@code HistoryEntry#key()})
 * @param videoId the 11-character YouTube video ID, ready for {@code player.loadVideoById()}
 * @param playlistId {@link Source#BACKGROUND}: the playlist the track was taken from (the same id the dashboard's
 *                player lease reports as the party's current playlist, so the window that plays can tell when the DJ
 *                has replaced or cleared it and stop the track); {@code null} for a guest song
 */
public record NextTrackResponse(Source source, Long id, String videoId, String playlistId) {

    /** A guest song: it belongs to no playlist. */
    public NextTrackResponse(Source source, Long id, String videoId) {
        this(source, id, videoId, null);
    }

    public enum Source {
        GUEST,
        BACKGROUND
    }
}
