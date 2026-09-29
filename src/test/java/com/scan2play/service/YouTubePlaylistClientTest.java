package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.model.PlaylistTrack;
import com.scan2play.service.FallbackImportException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests {@link YouTubePlaylistClient} against a mocked YouTube Data API — no network, no quota.
 */
class YouTubePlaylistClientTest {

    private static final String KEY = "test-api-key-123";
    private static final String PLAYLIST = "PLtestPlaylist01";

    private MockRestServiceServer server;
    private YouTubePlaylistClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new YouTubePlaylistClient(builder.build(), new ObjectMapper(), KEY);
    }

    // ---- JSON helpers ----

    /** playlistItems.list page; an item spec is a video ID, or "id:private". */
    private static String playlistPage(String nextPageToken, String... itemSpecs) {
        List<String> items = new ArrayList<>();
        for (String spec : itemSpecs) {
            String[] parts = spec.split(":");
            String privacy = parts.length > 1 ? parts[1] : "public";
            items.add("{\"contentDetails\":{\"videoId\":\"" + parts[0] + "\"},\"status\":{\"privacyStatus\":\"" + privacy + "\"}}");
        }
        return "{\"items\":[" + String.join(",", items) + "]"
                + (nextPageToken != null ? ",\"nextPageToken\":\"" + nextPageToken + "\"" : "") + "}";
    }

    /** videos.list response; a spec is a video ID (embeddable, titled "Title <id>"), or "id:noembed". */
    private static String videosResponse(String... specs) {
        List<String> items = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":");
            boolean embeddable = parts.length == 1;
            items.add("{\"id\":\"" + parts[0] + "\",\"status\":{\"embeddable\":" + embeddable + ",\"privacyStatus\":\"public\"},"
                    + "\"snippet\":{\"title\":\"Title " + parts[0] + "\"}}");
        }
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String param(ClientHttpRequest request, String name) {
        return UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(name);
    }

    // ---- happy paths ----

    @Test
    @DisplayName("keeps playable videos in playlist order, dropping private, non-embeddable and deleted ones")
    void shouldFilterUnplayableVideos_andKeepOrder() {
        server.expect(requestTo(containsString("/youtube/v3/playlistItems")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(queryParam("playlistId", PLAYLIST))
                .andExpect(queryParam("part", "contentDetails,status"))
                .andExpect(queryParam("key", KEY))
                .andRespond(withSuccess(playlistPage(null, "v1", "v2:private", "v3", "v4", "v5"), MediaType.APPLICATION_JSON));
        // v2 (private) is not even asked for; v3 disallows embedding; v5 was deleted (missing from videos.list)
        server.expect(requestTo(containsString("/youtube/v3/videos")))
                .andExpect(queryParam("part", "status,snippet"))
                .andExpect(queryParam("id", "v1,v3,v4,v5"))
                .andExpect(queryParam("key", KEY))
                .andRespond(withSuccess(videosResponse("v4", "v3:noembed", "v1"), MediaType.APPLICATION_JSON));

        List<PlaylistTrack> result = client.fetchPlayableTracks(PLAYLIST);

        assertThat(result).containsExactly(new PlaylistTrack("v1", "Title v1"), new PlaylistTrack("v4", "Title v4"));
        server.verify();
    }

    @Test
    @DisplayName("follows nextPageToken across pages and keeps the overall order")
    void shouldFollowPagination() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andExpect(request -> assertThat(param(request, "pageToken")).isNull())
                .andRespond(withSuccess(playlistPage("T2", "a1", "a2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/playlistItems")))
                .andExpect(queryParam("pageToken", "T2"))
                .andRespond(withSuccess(playlistPage(null, "b1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/videos")))
                .andExpect(queryParam("id", "a1,a2,b1"))
                .andRespond(withSuccess(videosResponse("b1", "a2", "a1"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchPlayableTracks(PLAYLIST)).extracting(PlaylistTrack::videoId).containsExactly("a1", "a2", "b1");
        server.verify();
    }

    @Test
    @DisplayName("reads at most 500 items: 10 playlistItems pages + 10 videos calls, however long the playlist")
    void shouldStopAtMaxTracks() {
        server.expect(ExpectedCount.times(10), requestTo(containsString("/playlistItems")))
                .andRespond(request -> {
                    String token = param(request, "pageToken");
                    int page = token == null ? 0 : Integer.parseInt(token.substring(1));
                    String[] ids = new String[50];
                    for (int i = 0; i < 50; i++) {
                        ids[i] = String.format("vid%08d", page * 50 + i);
                    }
                    // an endless playlist: there is always a next page
                    return withSuccess(playlistPage("p" + (page + 1), ids), MediaType.APPLICATION_JSON).createResponse(request);
                });
        server.expect(ExpectedCount.times(10), requestTo(containsString("/videos")))
                .andRespond(request -> {
                    String[] ids = param(request, "id").split(",");
                    return withSuccess(videosResponse(ids), MediaType.APPLICATION_JSON).createResponse(request);
                });

        List<PlaylistTrack> result = client.fetchPlayableTracks(PLAYLIST);

        assertThat(result).hasSize(YouTubePlaylistClient.MAX_TRACKS);
        assertThat(result.get(0).videoId()).isEqualTo("vid00000000");
        assertThat(result.get(499).videoId()).isEqualTo("vid00000499");
        server.verify(); // exactly 10 + 10 calls, no more
    }

    @Test
    @DisplayName("an empty playlist yields an empty list without calling videos.list")
    void shouldReturnEmpty_forEmptyPlaylist() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(withSuccess(playlistPage(null), MediaType.APPLICATION_JSON));

        assertThat(client.fetchPlayableTracks(PLAYLIST)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a playlist where nothing is embeddable yields an empty list")
    void shouldReturnEmpty_whenNothingIsEmbeddable() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(withSuccess(playlistPage(null, "v1", "v2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/videos")))
                .andRespond(withSuccess(videosResponse("v1:noembed", "v2:noembed"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchPlayableTracks(PLAYLIST)).isEmpty();
    }

    @Test
    @DisplayName("a video without a title in the response is still kept — with no title")
    void shouldKeepVideoWithoutTitle() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(withSuccess(playlistPage(null, "v1", "v2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/videos")))
                .andRespond(withSuccess("{\"items\":["
                        + "{\"id\":\"v1\",\"status\":{\"embeddable\":true,\"privacyStatus\":\"public\"}},"
                        + "{\"id\":\"v2\",\"status\":{\"embeddable\":true,\"privacyStatus\":\"public\"},\"snippet\":{\"title\":\"  \"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchPlayableTracks(PLAYLIST))
                .containsExactly(new PlaylistTrack("v1", null), new PlaylistTrack("v2", null));
    }

    // ---- findTitle (single-video fallback) ----

    @Test
    @DisplayName("findTitle looks up one video with a single videos.list call")
    void findTitle_shouldReturnTitle() {
        server.expect(requestTo(containsString("/youtube/v3/videos")))
                .andExpect(queryParam("part", "snippet"))
                .andExpect(queryParam("id", "dQw4w9WgXcQ"))
                .andExpect(queryParam("key", KEY))
                .andRespond(withSuccess(videosResponse("dQw4w9WgXcQ"), MediaType.APPLICATION_JSON));

        assertThat(client.findTitle("dQw4w9WgXcQ")).contains("Title dQw4w9WgXcQ");
        server.verify();
    }

    @Test
    @DisplayName("findTitle is best-effort: no key, a malformed ID or an API failure just mean 'no title'")
    void findTitle_shouldNeverFail() {
        YouTubePlaylistClient noKey = new YouTubePlaylistClient(RestClient.builder().build(), new ObjectMapper(), "");
        assertThat(noKey.findTitle("dQw4w9WgXcQ")).isEmpty();

        assertThat(client.findTitle("bad&id")).isEmpty();
        assertThat(client.findTitle(null)).isEmpty();
        server.verify(); // none of the above made a request

        server.expect(requestTo(containsString("/videos"))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(client.findTitle("dQw4w9WgXcQ")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("findTitle returns nothing when the video does not exist")
    void findTitle_shouldReturnEmpty_whenVideoIsUnknown() {
        server.expect(requestTo(containsString("/videos")))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findTitle("dQw4w9WgXcQ")).isEmpty();
    }

    // ---- failures ----

    @Test
    @DisplayName("without an API key it fails fast, without any HTTP call")
    void shouldFailWithoutApiKey() {
        for (String blank : Arrays.asList("", "  ", null)) {
            YouTubePlaylistClient noKey = new YouTubePlaylistClient(RestClient.builder().build(), new ObjectMapper(), blank);

            assertThatThrownBy(() -> noKey.fetchPlayableTracks(PLAYLIST))
                    .isInstanceOf(FallbackImportException.class)
                    .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.NO_API_KEY);
        }
    }

    @Test
    @DisplayName("a malformed playlist ID is rejected before any HTTP call (no parameter injection)")
    void shouldRejectInvalidPlaylistId() {
        for (String bad : new String[]{null, "", "x", "PL&key=evil", "PL abc", "a/b", "PL{x}"}) {
            assertThatThrownBy(() -> client.fetchPlayableTracks(bad))
                    .isInstanceOf(FallbackImportException.class)
                    .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.INVALID_PLAYLIST);
        }
        server.verify(); // no requests were expected — and none were made
    }

    @Test
    @DisplayName("HTTP 404 (unknown or private playlist) becomes API_ERROR")
    void shouldMapNotFoundToApiError() {
        server.expect(requestTo(containsString("/playlistItems"))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchPlayableTracks(PLAYLIST))
                .isInstanceOf(FallbackImportException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("not found")
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.API_ERROR);
    }

    @Test
    @DisplayName("HTTP 403 (quota exceeded / bad key) becomes API_ERROR")
    void shouldMapForbiddenToApiError() {
        server.expect(requestTo(containsString("/playlistItems"))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.fetchPlayableTracks(PLAYLIST))
                .isInstanceOf(FallbackImportException.class)
                .hasMessageContaining("403")
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.API_ERROR);
    }

    @Test
    @DisplayName("a failing videos.list call fails the whole import (no half-filtered result)")
    void shouldFailWhenVideosListFails() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(withSuccess(playlistPage(null, "v1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/videos"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetchPlayableTracks(PLAYLIST))
                .isInstanceOf(FallbackImportException.class)
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.API_ERROR);
    }

    @Test
    @DisplayName("an unparsable response body becomes API_ERROR")
    void shouldFailOnGarbageResponse() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(withSuccess("<html>not json</html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPlayableTracks(PLAYLIST))
                .isInstanceOf(FallbackImportException.class)
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.API_ERROR);
    }

    @Test
    @DisplayName("network errors never leak the API key (it is part of the request URL) and carry no cause")
    void shouldNeverLeakApiKey() {
        server.expect(requestTo(containsString("/playlistItems")))
                .andRespond(request -> {
                    throw new IOException("Connection reset while calling " + request.getURI());
                });

        assertThatThrownBy(() -> client.fetchPlayableTracks(PLAYLIST))
                .isInstanceOf(FallbackImportException.class)
                .hasMessageNotContaining(KEY)
                .hasMessageContaining("***")
                .hasNoCause();
    }
}
