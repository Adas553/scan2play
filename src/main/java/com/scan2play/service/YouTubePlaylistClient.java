package com.scan2play.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.model.PlaylistTrack;
import com.scan2play.service.FallbackImportException.Reason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a YouTube playlist through the YouTube Data API v3 and returns the videos (ID and title) that can
 * actually be played in the embedded player, in playlist order.
 * <p>
 * <b>Quota:</b> {@code playlistItems.list} and {@code videos.list} cost 1 unit per call (50 items),
 * drawn from the general 10,000-units/day pool — separate from the tight {@code search.list} limit.
 * At most {@value #MAX_TRACKS} playlist items are read (≤ 10 + 10 calls ≈ 20 units per import). The titles come
 * from the same {@code videos.list} calls (an extra {@code part} does not cost extra quota).
 * <p>
 * <b>Filtering:</b> private items are skipped up front; deleted videos and videos that disallow
 * embedding are dropped via {@code videos.list} (they would only cause player errors later).
 * <p>
 * <b>Security:</b> the API key is part of the request URL, so failures are reported without the
 * underlying exception (see {@link FallbackImportException}) and messages are scrubbed of the key.
 */
@Service
@Slf4j
public class YouTubePlaylistClient {

    /** Upper bound of playlist items read per import. */
    static final int MAX_TRACKS = 500;

    private static final int PAGE_SIZE = 50;
    private static final String PLAYLIST_ITEMS_URL = "https://www.googleapis.com/youtube/v3/playlistItems";
    private static final String VIDEOS_URL = "https://www.googleapis.com/youtube/v3/videos";
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public YouTubePlaylistClient(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 @Value("${youtube.api-key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    /**
     * @param playlistId a YouTube playlist ID (e.g. {@code PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf})
     * @return playable videos in playlist order, without duplicates, at most {@value #MAX_TRACKS} candidates
     *         considered; empty if the playlist has no public, embeddable videos
     * @throws FallbackImportException if the key is missing, the ID is malformed or the API call fails
     */
    public List<PlaylistTrack> fetchPlayableTracks(String playlistId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new FallbackImportException(Reason.NO_API_KEY,
                    "YouTube API key is not configured (youtube.api-key / YOUTUBE_API_KEY)");
        }
        if (playlistId == null || !PLAYLIST_ID_PATTERN.matcher(playlistId).matches()) {
            throw new FallbackImportException(Reason.INVALID_PLAYLIST, "Not a valid YouTube playlist ID");
        }

        List<String> candidates = fetchPlaylistVideoIds(playlistId);
        List<PlaylistTrack> playable = keepEmbeddable(candidates);
        log.info("YouTube playlist {}: {} item(s) read, {} playable", playlistId, candidates.size(), playable.size());
        return playable;
    }

    /**
     * Best-effort title lookup for a single video (one {@code videos.list} call, 1 quota unit). Used for a
     * single-video fallback, which needs no API call to be played — so a missing key or a failing API
     * only means "no title", never an error.
     */
    public Optional<String> findTitle(String videoId) {
        if (apiKey == null || apiKey.isBlank() || videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            return Optional.empty();
        }
        URI uri = UriComponentsBuilder.fromUriString(VIDEOS_URL)
                .queryParam("part", "snippet")
                .queryParam("id", videoId)
                .queryParam("key", apiKey)
                .build().encode().toUri();
        try {
            for (JsonNode video : getJson(uri).path("items")) {
                String title = video.path("snippet").path("title").asText("");
                if (!title.isBlank()) {
                    return Optional.of(title);
                }
            }
        } catch (FallbackImportException e) {
            log.debug("No title for video {}: {}", videoId, e.getMessage());
        }
        return Optional.empty();
    }

    // ---- playlistItems.list ----

    private List<String> fetchPlaylistVideoIds(String playlistId) {
        Set<String> videoIds = new LinkedHashSet<>();
        String pageToken = null;
        do {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(PLAYLIST_ITEMS_URL)
                    .queryParam("part", "contentDetails,status")
                    .queryParam("playlistId", playlistId)
                    .queryParam("maxResults", PAGE_SIZE);
            if (pageToken != null) {
                uri.queryParam("pageToken", pageToken);
            }
            JsonNode root = getJson(uri.queryParam("key", apiKey).build().encode().toUri());

            for (JsonNode item : root.path("items")) {
                String videoId = item.path("contentDetails").path("videoId").asText("");
                boolean isPrivate = "private".equals(item.path("status").path("privacyStatus").asText(""));
                if (!videoId.isBlank() && !isPrivate && videoIds.size() < MAX_TRACKS) {
                    videoIds.add(videoId);
                }
            }
            pageToken = root.path("nextPageToken").asText(null);
        } while (pageToken != null && videoIds.size() < MAX_TRACKS);

        return new ArrayList<>(videoIds);
    }

    // ---- videos.list ----

    /**
     * Keeps only videos that still exist, are not private and allow embedding — preserving order — and attaches
     * their titles.
     */
    private List<PlaylistTrack> keepEmbeddable(List<String> candidates) {
        Map<String, String> titles = new HashMap<>(); // video ID -> title (null if the API sent none)
        for (int from = 0; from < candidates.size(); from += PAGE_SIZE) {
            List<String> chunk = candidates.subList(from, Math.min(from + PAGE_SIZE, candidates.size()));
            URI uri = UriComponentsBuilder.fromUriString(VIDEOS_URL)
                    .queryParam("part", "status,snippet")
                    .queryParam("id", String.join(",", chunk))
                    .queryParam("key", apiKey)
                    .build().encode().toUri();
            for (JsonNode video : getJson(uri).path("items")) {
                boolean embeddable = video.path("status").path("embeddable").asBoolean(false);
                boolean isPrivate = "private".equals(video.path("status").path("privacyStatus").asText(""));
                if (embeddable && !isPrivate) {
                    titles.put(video.path("id").asText(""), video.path("snippet").path("title").asText(null));
                }
            }
        }
        return candidates.stream()
                .filter(titles::containsKey)
                .map(id -> new PlaylistTrack(id, titles.get(id)))
                .toList();
    }

    // ---- HTTP ----

    private JsonNode getJson(URI uri) {
        try {
            String body = restClient.get().uri(uri).retrieve().body(String.class);
            if (body == null) {
                throw new FallbackImportException(Reason.API_ERROR, "YouTube API returned an empty response");
            }
            return objectMapper.readTree(body);
        } catch (RestClientResponseException e) {
            throw new FallbackImportException(Reason.API_ERROR,
                    "YouTube API returned HTTP " + e.getStatusCode().value()
                            + (e.getStatusCode().value() == 404 ? " (playlist not found or private)" : ""));
        } catch (RestClientException | JsonProcessingException e) {
            throw new FallbackImportException(Reason.API_ERROR,
                    "YouTube API call failed: " + sanitize(e.getMessage()));
        }
    }

    /** Removes the API key from text that may originate from a request URL. */
    private String sanitize(String message) {
        return message == null ? "" : message.replace(apiKey, "***");
    }
}
