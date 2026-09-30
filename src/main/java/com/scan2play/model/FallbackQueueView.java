package com.scan2play.model;

import java.util.List;

/**
 * What the DJ sees as "up next" from the fallback (background music) playlist.
 *
 * @param hasPlaylist whether the DJ has set a fallback playlist at all
 * @param shuffle     whether the queue is in random order (the DJ's shuffle setting)
 * @param manualOrder whether the DJ has moved tracks by hand in the current order (switching shuffle would undo that)
 * @param remaining   how many tracks are still queued in this round of the playlist
 * @param skipped     how many tracks the DJ has skipped in this round (they come back in the next one)
 * @param tracks      the queued tracks, in the order they will play; a waiting guest song still plays before them
 */
public record FallbackQueueView(boolean hasPlaylist, boolean shuffle, boolean manualOrder, long remaining, long skipped,
                                List<Track> tracks) {

    /** A round in which nothing was skipped. */
    public FallbackQueueView(boolean hasPlaylist, boolean shuffle, boolean manualOrder, long remaining, List<Track> tracks) {
        this(hasPlaylist, shuffle, manualOrder, remaining, 0, tracks);
    }

    /**
     * @param id      the {@code fallback_track} id
     * @param videoId the 11-character YouTube video ID
     * @param title   the video title, or {@code null} if unknown
     */
    public record Track(Long id, String videoId, String title) {
    }

    /** The DJ has no fallback playlist. */
    public static FallbackQueueView noPlaylist(boolean shuffle) {
        return new FallbackQueueView(false, shuffle, false, 0, 0, List.of());
    }
}
