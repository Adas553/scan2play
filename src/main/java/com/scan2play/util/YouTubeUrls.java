package com.scan2play.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing of the YouTube URLs / IDs a DJ pastes into the fallback-playlist field.
 */
public final class YouTubeUrls {

    /** Marks a single video in the string returned by {@link #extractPlaylistId(String)}. */
    public static final String SINGLE_VIDEO_PREFIX = "V:";

    /** Extracts YouTube playlist ID from a full URL (e.g. ?list=PLxxxxxx). */
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

    /** Extracts YouTube video ID from watch URLs (e.g. ?v=xxxxx). */
    private static final Pattern VIDEO_ID_V_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    /** Extracts YouTube video ID from short URLs (e.g. youtu.be/xxxxx). */
    private static final Pattern VIDEO_ID_SHORT_PATTERN = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");

    private YouTubeUrls() {
    }

    /**
     * The video ID of a track URL the app has stored: a watch URL ({@code ?v=xxxxxxxxxxx}) or a short one
     * ({@code youtu.be/xxxxxxxxxxx}). Empty for anything else — notably a YouTube <em>search</em> URL, which the app
     * stores when the Data API could not resolve a song, and which cannot be played by the embedded player.
     */
    public static Optional<String> extractVideoId(String trackUrl) {
        if (trackUrl == null) {
            return Optional.empty();
        }
        Matcher watch = VIDEO_ID_V_PATTERN.matcher(trackUrl);
        if (watch.find()) {
            return Optional.of(watch.group(1));
        }
        Matcher shortUrl = VIDEO_ID_SHORT_PATTERN.matcher(trackUrl);
        return shortUrl.find() ? Optional.of(shortUrl.group(1)) : Optional.empty();
    }

    /**
     * Extracts a YouTube playlist ID or video ID from a URL or raw input.
     * <p>
     * Supported formats:
     * <ul>
     *     <li>Playlist URL: {@code https://youtube.com/playlist?list=PLxxx} → {@code PLxxx}</li>
     *     <li>Watch URL with playlist: {@code https://youtube.com/watch?v=abc&list=PLxxx} → {@code PLxxx}</li>
     *     <li>Watch URL (single video): {@code https://youtube.com/watch?v=KD5fLb-WgBU} → {@code V:KD5fLb-WgBU}</li>
     *     <li>Short URL: {@code https://youtu.be/KD5fLb-WgBU?si=...} → {@code V:KD5fLb-WgBU}</li>
     *     <li>Raw playlist ID: {@code PLxxx} → {@code PLxxx}</li>
     *     <li>Raw video ID (11 chars): {@code KD5fLb-WgBU} → {@code V:KD5fLb-WgBU}</li>
     * </ul>
     * Video IDs are prefixed with {@code V:} so callers can distinguish them from playlist IDs.
     *
     * @return extracted ID (with {@code V:} prefix for single videos), or null if input is blank.
     */
    public static String extractPlaylistId(String input) {
        if (input == null || input.isBlank()) return null;

        // Priority 1: playlist ID from URL (?list=PLxxx)
        Matcher playlistMatcher = PLAYLIST_ID_PATTERN.matcher(input);
        if (playlistMatcher.find()) return playlistMatcher.group(1);

        // Priority 2: video ID from watch URL (?v=xxx)
        Matcher videoMatcher = VIDEO_ID_V_PATTERN.matcher(input);
        if (videoMatcher.find()) return SINGLE_VIDEO_PREFIX + videoMatcher.group(1);

        // Priority 3: video ID from short URL (youtu.be/xxx)
        Matcher shortMatcher = VIDEO_ID_SHORT_PATTERN.matcher(input);
        if (shortMatcher.find()) return SINGLE_VIDEO_PREFIX + shortMatcher.group(1);

        // Priority 4: raw input — check if it looks like a video ID (exactly 11 chars, valid charset)
        String trimmed = input.trim();
        if (trimmed.matches("[A-Za-z0-9_-]{11}")) return SINGLE_VIDEO_PREFIX + trimmed;

        // Otherwise treat as raw playlist ID (existing behavior)
        return trimmed;
    }
}
