package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.repository.YoutubeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link YouTubeMusicProvider}'s call of the YouTube Data API search.
 */
@ExtendWith(MockitoExtension.class)
class YouTubeMusicProviderTest {

    private static final String API_KEY = "test-key-123";

    @Mock
    private YoutubeCacheRepository youtubeCacheRepository;

    private YouTubeSearchBudget budget;
    private MockRestServiceServer server;
    private YouTubeMusicProvider provider;

    @BeforeEach
    void setUp() {
        budget = new YouTubeSearchBudget(2);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new YouTubeMusicProvider(builder.build(), new ObjectMapper(), youtubeCacheRepository, budget, API_KEY);
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
        YouTubeMusicProvider noKey = new YouTubeMusicProvider(RestClient.create(), new ObjectMapper(), youtubeCacheRepository,
                new YouTubeSearchBudget(80), "");
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());

        assertThat(noKey.findTrackUrl("Some Song")).isEqualTo("https://www.youtube.com/results?search_query=Some+Song");
    }

    @Test
    void findTrackUrl_returnsASearchLink_withoutCallingTheApi_onceTodaysBudgetIsSpent() {
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());
        budget.tryAcquire();
        budget.tryAcquire();

        assertThat(provider.findTrackUrl("Some Song")).isEqualTo("https://www.youtube.com/results?search_query=Some+Song");
        server.verify(); // no request was expected
    }

    @Test
    void findTrackUrl_tripsTheBudget_whenGoogleAnswersQuotaExceeded() {
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());
        server.expect(ExpectedCount.once(), method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));

        assertThat(provider.findTrackUrl("Some Song")).isEqualTo("https://www.youtube.com/results?search_query=Some+Song");
        assertThat(provider.findTrackUrl("Other Song")).as("no second call")
                .isEqualTo("https://www.youtube.com/results?search_query=Other+Song");
        assertThat(budget.tryAcquire()).isFalse();
        server.verify();
    }

    /** Review item 4.5: a search that found nothing was asked again on every request — 100 quota units each time. */
    @Test
    void findTrackUrl_remembersASearchThatFoundNothing() {
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());
        server.expect(ExpectedCount.once(), method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(provider.findTrackUrl("No Such Song")).isNull();
        assertThat(provider.findTrackUrl("  no such SONG ")).as("the same query, normalised: no second call").isNull();
        assertThat(budget.tryAcquire()).as("one search counted, one left").isTrue();
        server.verify();
    }

    @Test
    void findTrackUrl_asksAgainAfterAFailure() {
        when(youtubeCacheRepository.findBySearchQuery(anyString())).thenReturn(Optional.empty());
        server.expect(ExpectedCount.once(), method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[{\"id\":{\"videoId\":\"dQw4w9WgXcQ\"}}]}", MediaType.APPLICATION_JSON));

        assertThat(provider.findTrackUrl("Some Song")).as("a failure may be over in a moment: not remembered").isNull();
        assertThat(provider.findTrackUrl("Some Song")).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        server.verify();
    }
}
