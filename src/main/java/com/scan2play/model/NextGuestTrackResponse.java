package com.scan2play.model;

/**
 * Server-side "what's next" answer for the YouTube Auto-Pilot client.
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
