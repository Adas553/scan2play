package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.YoutubeCacheEntity;
import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import com.scan2play.repository.YoutubeCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * YouTube implementation of {@link MusicProvider}.
 * <p>
 * Uses the YouTube Data API v3 to resolve song names into direct video URLs
 * (e.g., {@code https://www.youtube.com/watch?v=dQw4w9WgXcQ}).
 * <p>
 * <b>Two-level cache for quota optimization:</b>
 * <ol>
 *     <li><b>L1 — Caffeine (in-memory, 24h)</b>: prevents repeated DB queries during a session</li>
 *     <li><b>L2 — PostgreSQL ({@code youtube_cache} table, 30-day TTL)</b>: survives restarts,
 *         ensures each unique song costs API quota only once per 30 days</li>
 * </ol>
 * <b>YouTube API ToS compliance:</b> Cached data expires after 30 days. Expired entries
 * are refreshed on next access and periodically cleaned up via a daily scheduled task.
 * <p>
 * If the API key is not configured, returns a YouTube search URL as a clickable fallback
 * (manual play only — Auto-Pilot requires direct video URLs).
 */
@Service
@Slf4j
public class YouTubeMusicProvider implements MusicProvider {

    private static final String YOUTUBE_API_URL =
            "https://www.googleapis.com/youtube/v3/search";
    private static final String YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final String YOUTUBE_SEARCH_FALLBACK = "https://www.youtube.com/results?search_query=";
    /** How Google APIs accept an API key other than the {@code key} query parameter. */
    static final String API_KEY_HEADER = "X-goog-api-key";
    /** The {@code reason} of the 403 Google answers once the day's quota is spent. */
    private static final String QUOTA_EXCEEDED_REASON = "quotaExceeded";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final YoutubeCacheRepository youtubeCacheRepository;
    private final YouTubeSearchBudget searchBudget;
    private final String apiKey;

    /**
     * Searches the API answered with no video, by normalised query, for {@value #NOT_FOUND_MINUTES} minutes: asked again they
     * would cost another 100 quota units for the same empty answer (a guest who sends the same request twice, several guests
     * with the same typo). A failed call (network, 5xx) is not remembered — it may be over in a moment.
     */
    private final Cache<String, Boolean> notFound = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(NOT_FOUND_MINUTES))
            .maximumSize(1000)
            .build();

    static final int NOT_FOUND_MINUTES = 10;

    public YouTubeMusicProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            YoutubeCacheRepository youtubeCacheRepository,
            YouTubeSearchBudget searchBudget,
            @Value("${youtube.api-key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.youtubeCacheRepository = youtubeCacheRepository;
        this.searchBudget = searchBudget;
        this.apiKey = apiKey;
    }

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.YOUTUBE;
    }

    /**
     * Resolves a song name to a YouTube video URL using a two-level cache.
     * <p>
     * Lookup order:
     * <ol>
     *     <li>L1 — Caffeine in-memory cache (24h TTL, zero cost)</li>
     *     <li>L2 — {@code youtube_cache} DB table (30-day TTL, ~1ms indexed SELECT)</li>
     *     <li>YouTube Data API v3 (100 quota units, result saved/updated in L2)</li>
     * </ol>
     * If a DB cache entry is found but older than 30 days (YouTube API ToS limit),
     * it is treated as expired — the API is called again and the row is updated in place.
     * <p>
     * When today's search budget is spent ({@link YouTubeSearchBudget}) the API is not called and the song gets the search link.
     * <p>
     * {@code null} results and search links are not cached here, so the song gets its video once the API can be asked; a search
     * that found nothing is not asked again for {@value #NOT_FOUND_MINUTES} minutes ({@link #notFound}).
     *
     * @param searchQuery the text to search for (e.g., song title and artist)
     * @return a YouTube video URL, or a search fallback URL, or null if search fails
     */
    @Cacheable(value = "youtubeSearch",
            key = "#searchQuery.strip().toLowerCase()",
            unless = "#result == null || #result.startsWith('" + YOUTUBE_SEARCH_FALLBACK + "')")
    @Override
    public String findTrackUrl(String searchQuery) {
        String normalizedQuery = searchQuery.strip().toLowerCase();

        // L2: Check persistent DB cache (survives restarts, costs ~1ms)
        var cached = youtubeCacheRepository.findBySearchQuery(normalizedQuery);
        if (cached.isPresent()) {
            YoutubeCacheEntity entry = cached.get();

            if (!entry.isExpired()) {
                String videoUrl = YOUTUBE_WATCH_URL + entry.getVideoId();
                log.debug("YouTube DB cache hit for '{}': {}", searchQuery, videoUrl);
                return videoUrl;
            }

            // Expired (>30 days) — must refresh per YouTube API ToS
            log.info("YouTube DB cache expired for '{}' (created {}), refreshing", searchQuery, entry.getCreatedAt());
        }

        // No API key → return clickable fallback (Auto-Pilot won't work)
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("YouTube API key not configured — returning search URL fallback for: '{}'", searchQuery);
            return buildSearchFallbackUrl(searchQuery);
        }

        if (notFound.getIfPresent(normalizedQuery) != null) {
            log.debug("YouTube search for '{}' found nothing a few minutes ago — not asked again", searchQuery);
            return null;
        }

        // The search limit is one per Google project, shared by every party: past today's budget, the song gets the search link
        if (!searchBudget.tryAcquire()) {
            log.debug("YouTube search budget spent — returning search URL fallback for: '{}'", searchQuery);
            return buildSearchFallbackUrl(searchQuery);
        }

        // L3: YouTube Data API call (100 quota units)
        return callYouTubeApi(searchQuery, normalizedQuery, cached.orElse(null));
    }


    /**
     * Daily cleanup of expired cache entries (YouTube API ToS: max 30-day retention).
     * Runs at 04:00 every day. Entries that are accessed before cleanup are refreshed
     * on-demand in {@link #findTrackUrl(String)}.
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupExpiredEntries() {
        Instant cutoff = Instant.now().minus(YoutubeCacheEntity.MAX_AGE_DAYS, ChronoUnit.DAYS);
        int deleted = youtubeCacheRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("YouTube cache cleanup: deleted {} expired entries (older than {} days)",
                    deleted, YoutubeCacheEntity.MAX_AGE_DAYS);
        }
    }

    // ---- Private helpers ----

    private String callYouTubeApi(String searchQuery, String normalizedQuery, YoutubeCacheEntity existingEntry) {
        try {
            log.debug("YouTube API call for: '{}' (cache miss or expired)", searchQuery);
            // The key goes in a header, not in the URL: an I/O error (timeout, reset) puts the whole URL into the exception
            // message, and that is logged below.
            String responseBody = restClient.get()
                    .uri(YOUTUBE_API_URL + "?part=id&q={q}&type=video&videoCategoryId=10&maxResults=1", searchQuery)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .body(String.class);

            JsonNode items = objectMapper.readTree(responseBody).path("items");
            if (items.isArray() && !items.isEmpty()) {
                String videoId = items.get(0).path("id").path("videoId").asText();
                if (!videoId.isBlank()) {
                    saveOrUpdateDbCache(normalizedQuery, videoId, existingEntry);
                    String videoUrl = YOUTUBE_WATCH_URL + videoId;
                    log.info("YouTube match for '{}': {} (saved to DB cache)", searchQuery, videoUrl);
                    return videoUrl;
                }
            }
            notFound.put(normalizedQuery, Boolean.TRUE);
            log.warn("No YouTube video found for: '{}'", searchQuery);
        } catch (HttpClientErrorException.Forbidden e) {
            if (e.getResponseBodyAsString().contains(QUOTA_EXCEEDED_REASON)) {
                searchBudget.markQuotaExceeded();
                return buildSearchFallbackUrl(searchQuery);
            }
            log.error("YouTube Data API search refused for: '{}'", searchQuery, e);
        } catch (Exception e) {
            log.error("YouTube Data API search failed for: '{}'", searchQuery, e);
        }
        return null;
    }

    /**
     * Saves a new cache entry or updates an existing expired one in place.
     */
    private void saveOrUpdateDbCache(String normalizedQuery, String videoId, YoutubeCacheEntity existingEntry) {
        try {
            if (existingEntry != null) {
                // Update expired row in place (same PK, fresh videoId + timestamp)
                existingEntry.setVideoId(videoId);
                existingEntry.setCreatedAt(Instant.now());
                youtubeCacheRepository.save(existingEntry);
                log.debug("YouTube DB cache refreshed for '{}'", normalizedQuery);
            } else {
                youtubeCacheRepository.save(YoutubeCacheEntity.builder()
                        .searchQuery(normalizedQuery)
                        .videoId(videoId)
                        .createdAt(Instant.now())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to persist YouTube cache for '{}': {}", normalizedQuery, e.getMessage());
        }
    }

    private String buildSearchFallbackUrl(String query) {
        return YOUTUBE_SEARCH_FALLBACK + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}
