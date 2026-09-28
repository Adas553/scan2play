package com.scan2play.model;

/**
 * Server-side "what plays next?" answer for the YouTube Auto-Pilot client
 * ({@code POST /dj/dashboard/next-track}, PROJECT_CONTEXT.md Section 14).
 * <p>
 * A waiting guest song always wins; otherwise the next fallback ("background music") track is chosen.
 *
 * @param source  where the track comes from
 * @param id      {@link Source#GUEST}: the {@code SongRequestEntity} id — the client still confirms
 *                playback with {@code POST /dj/dashboard/play}; {@link Source#BACKGROUND}: the
 *                {@code FallbackTrackEntity} id (already marked played by the server when handed out)
 * @param videoId the 11-character YouTube video ID, ready for {@code player.loadVideoById()}
 */
public record NextTrackResponse(Source source, Long id, String videoId) {

    public enum Source {
        GUEST,
        BACKGROUND
    }
}
