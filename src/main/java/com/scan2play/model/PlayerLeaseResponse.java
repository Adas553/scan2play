package com.scan2play.model;

/**
 * The answer to a lease report ({@code POST /dj/dashboard/player-lease}).
 *
 * @param holder             {@code true} when the reporting window is the one that plays
 * @param free               {@code true} when no window holds the lease at all (the one that played has gone away)
 * @param fallbackPlaylistId the party's current fallback playlist (or {@code V:<videoId>} for a single video), or
 *                           {@code null} when there is none. The window that plays compares it with the playlist of
 *                           the background track it is playing: the DJ may have replaced or cleared the playlist in
 *                           another window, and then that track has to stop.
 */
public record PlayerLeaseResponse(boolean holder, boolean free, String fallbackPlaylistId) {
}
