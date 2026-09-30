package com.scan2play.model;

/**
 * A track that played recently and can be played again — what the dashboard's "previous track" button walks back
 * along ({@code GET /dj/dashboard/recent-tracks}).
 *
 * @param key     {@link HistoryEntry#key()}: {@code G:<request id>} or {@code B:<play id>} — the same string the player
 *                script keeps for the track it is playing, so it can find its place in the list
 * @param source  {@code GUEST} or {@code BACKGROUND}
 * @param id      the request id, or the id of the play log row of a background track
 * @param videoId the YouTube video ID, ready for {@code player.loadVideoById()}
 * @param title   what to call it (the video ID when nothing better is known)
 * @param secondsAgo how long ago it started to play, by the server's clock (so the browser's clock and time zone do not
 *                   matter); null when the moment is unknown. A dashboard that is loaded again brings the newest track back
 *                   only when it started recently (youtube-autopilot.js, "resume after a reload").
 */
public record RecentTrack(String key, String source, Long id, String videoId, String title, Long secondsAgo) {
}
