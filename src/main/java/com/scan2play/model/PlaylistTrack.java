package com.scan2play.model;

/**
 * One playable video of a fallback playlist as read from the YouTube API.
 *
 * @param videoId the 11-character YouTube video ID
 * @param title   the video title, or {@code null} if it is unknown; blank titles become {@code null} and
 *                titles longer than {@value #MAX_TITLE_LENGTH} characters are cut (the database column limit)
 */
public record PlaylistTrack(String videoId, String title) {

    public static final int MAX_TITLE_LENGTH = 255;

    public PlaylistTrack {
        if (title != null) {
            title = title.strip();
            if (title.isEmpty()) {
                title = null;
            } else if (title.length() > MAX_TITLE_LENGTH) {
                title = title.substring(0, MAX_TITLE_LENGTH);
            }
        }
    }
}
