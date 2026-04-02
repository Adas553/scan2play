package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * YouTube implementation of {@link MusicProvider}.
 * <p>
 * Uses the YouTube Data API v3 to search for tracks and return playable video URLs.
 * Auto-Pilot queue management is handled entirely client-side via the IFrame Player API.
 * <p>
 * Requires {@code youtube.api-key} to be configured. If missing, falls back to
 * a YouTube search URL (manual play only, Auto-Pilot won't work).
 */
@Service
@Slf4j
public class YouTubeMusicProvider implements MusicProvider {

    private static final String YOUTUBE_SEARCH_URL =
            "https://www.googleapis.com/youtube/v3/search";
    private static final String YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final String YOUTUBE_SEARCH_FALLBACK = "https://www.youtube.com/results?search_query=";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public YouTubeMusicProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            @Value("${youtube.api-key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.YOUTUBE;
    }

    /**
     * Searches for a music video on YouTube using the Data API v3.
     * Returns a direct video URL (e.g., {@code https://www.youtube.com/watch?v=dQw4w9WgXcQ}).
     * <p>
     * If the API key is not configured, returns a YouTube search results URL as a fallback.
     *
     * @param searchQuery the text to search for (e.g., song title and artist)
     * @return a YouTube video URL, or a search fallback URL, or null if search fails
     */
    @Override
    public String findTrackUrl(String searchQuery) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("YouTube API key not configured — returning search URL fallback for: '{}'", searchQuery);
            return YOUTUBE_SEARCH_FALLBACK + searchQuery.replace(" ", "+");
        }

        try {
            log.debug("Searching YouTube Data API for: '{}'", searchQuery);
            String responseBody = restClient.get()
                    .uri(YOUTUBE_SEARCH_URL
                                    + "?part=id&q={q}&type=video&videoCategoryId=10&maxResults=1&key={key}",
                            searchQuery, apiKey)
                    .retrieve()
                    .body(String.class);

            JsonNode items = objectMapper.readTree(responseBody).path("items");
            if (items.isArray() && !items.isEmpty()) {
                String videoId = items.get(0).path("id").path("videoId").asText();
                if (!videoId.isBlank()) {
                    String videoUrl = YOUTUBE_WATCH_URL + videoId;
                    log.info("YouTube match for '{}': {}", searchQuery, videoUrl);
                    return videoUrl;
                }
            }
            log.warn("No YouTube video found for: '{}'", searchQuery);
        } catch (Exception e) {
            log.error("YouTube Data API search failed for: '{}'", searchQuery, e);
        }
        return null;
    }

    /**
     * No-op for YouTube. Auto-Pilot queue is managed client-side via IFrame Player API.
     */
    @Override
    public void addToQueue(String partyCode, String trackId) {
        log.debug("Party [{}]: YouTube queue is managed client-side, skipping server-side push", partyCode);
    }
}
