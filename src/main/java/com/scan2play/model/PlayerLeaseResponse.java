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
 * @param queueVersion       changes whenever the "up next" list would look different (a track taken, a move, a new
 *                           order, the shuffle switch, another playlist). A window that sees it change fetches the
 *                           list again — the DJ may have changed the queue in another window, and no timer refreshes it.
 * @param command            a command the DJ gave from another window, for the window that plays ({@code null} for
 *                           everyone else, and when there is none). It is handed out once.
 * @param playing            whether the player of the window that plays is making sound ({@code true}) or is paused
 *                           ({@code false}), as that window last said; {@code null} when nobody plays or it has not
 *                           said. A window that does not play shows "pause" or "resume" by it.
 * @param playbackMode       the party's Auto-Pilot setting ({@code AUTO} / {@code MANUAL}) — one setting for every window.
 *                           Each window follows it (its switch and what its player does when a track ends), so a change
 *                           made on another device reaches it within one report, not only the value the window was
 *                           loaded with.
 */
public record PlayerLeaseResponse(boolean holder, boolean free, String fallbackPlaylistId, String queueVersion,
                                  PlayerCommand command, Boolean playing, PlaybackMode playbackMode) {
}
