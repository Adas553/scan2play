package com.scan2play.model;

/**
 * The next guest song to play, as {@code DjService.findNextPlayableGuestTrack} finds it; {@code NextTrackService} turns it
 * into the answer of {@code POST /dj/dashboard/next-track} (there is no endpoint of its own any more).
 * <p>
 * Replaces the old client-side DOM scan of the queue table (see PROJECT_CONTEXT.md
 * Section 14): the backend is now the single source of truth for which guest song
 * plays next. The client never marks it played by itself — it still confirms via the
 * existing {@code POST /dj/dashboard/play} once the video actually reaches PLAYING.
 *
 * @param songId  The {@code SongRequestEntity} id, echoed back on {@code /dj/dashboard/play}.
 * @param videoId The 11-character YouTube video ID, ready for {@code player.loadVideoById()}.
 */
public record NextGuestTrackResponse(Long songId, String videoId) {}
