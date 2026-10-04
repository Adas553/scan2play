package com.scan2play.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The "🔍 Podejrzyj" link of a song: YouTube's search results for its name, for the DJ to look at a song they do not know. A page
 * the DJ's browser opens — no YouTube API call, no key, no quota. The DJ plays from their own software.
 */
public final class YouTubeSearchLinks {

    static final String YOUTUBE_SEARCH = "https://www.youtube.com/results?search_query=";

    private YouTubeSearchLinks() {
    }

    /** The link to YouTube's search results for {@code query}, or null for an empty query. */
    public static String forQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return YOUTUBE_SEARCH + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
    }
}
