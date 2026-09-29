package com.scan2play.model;

/**
 * A track that played recently and can be played again — what the dashboard's "previous track" button walks back
 * along ({@code GET /dj/dashboard/recent-tracks}).
 *
 * @param key     {@link HistoryEntry#key()}: {@code G:<request id>} or {@code B:<track id>} — the same string the player
 *                script keeps for the track it is playing, so it can find its place in the list
 * @param source  {@code GUEST} or {@code BACKGROUND}
 * @param id      the request or track id
 * @param videoId the YouTube video ID, ready for {@code player.loadVideoById()}
 * @param title   what to call it (the video ID when nothing better is known)
 */
public record RecentTrack(String key, String source, Long id, String videoId, String title) {
}
