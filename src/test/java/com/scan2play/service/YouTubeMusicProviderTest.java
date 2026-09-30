package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.repository.YoutubeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link YouTubeMusicProvider}'s call of the YouTube Data API search.
 */
@ExtendWith(MockitoExtension.class)
class YouTubeMusicProviderTest {

    private static final String API_KEY = "test-key-123";

    @Mock
    private YoutubeCacheRepository youtubeCacheRepository;

    private MockRestServiceServer server;
    private YouTubeMusicProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new YouTubeMusicProvider(builder.build(), new ObjectMapper(), youtubeCacheRepository, API_KEY);
    }

    @Test
    void findTrackUrl_sendsTheApiKeyInAHeader_neverInTheUrl() {
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());
        server.expect(requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(API_KEY))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(YouTubeMusicProvider.API_KEY_HEADER, API_KEY))
                .andRespond(withSuccess("{\"items\":[{\"id\":{\"videoId\":\"dQw4w9WgXcQ\"}}]}", MediaType.APPLICATION_JSON));

        String url = provider.findTrackUrl("Rick Astley - Never Gonna Give You Up");

        assertThat(url).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        server.verify();
    }

    @Test
    void findTrackUrl_returnsASearchLink_withoutAnApiKey() {
        YouTubeMusicProvider noKey = new YouTubeMusicProvider(RestClient.create(), new ObjectMapper(), youtubeCacheRepository, "");
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());

        assertThat(noKey.findTrackUrl("Some Song")).isEqualTo("https://www.youtube.com/results?search_query=Some+Song");
    }
}
