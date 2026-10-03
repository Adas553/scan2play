package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PartySettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The refresh of a party's Spotify token ({@link SpotifyAuthService#getRefreshedAccessToken}) against a mocked token endpoint.
 */
class SpotifyAuthServiceTest {

    private static final String PARTY = "ABC12";
    private static final String TOKEN_URI = "https://accounts.spotify.test/api/token";

    private MockRestServiceServer server;
    private SpotifyAuthService service;
    /** The party's settings as the database has them; the mocked query and command services read and write it. */
    private final AtomicReference<PartySettingsEntity> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        PartySettingsQueryService query = mock(PartySettingsQueryService.class);
        when(query.getSettings(PARTY)).thenAnswer(inv -> copy(stored.get()));
        PartySettingsCommandService command = mock(PartySettingsCommandService.class);
        when(command.updateSettings(eq(PARTY), any())).thenAnswer(inv -> {
            PartySettingsEntity settings = copy(stored.get());
            inv.<Consumer<PartySettingsEntity>>getArgument(1).accept(settings);
            stored.set(settings);
            return settings;
        });

        service = new SpotifyAuthService(query, command, builder.build(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "tokenUri", TOKEN_URI);
        ReflectionTestUtils.setField(service, "clientId", "id");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        service.init();
    }

    private static PartySettingsEntity copy(PartySettingsEntity s) {
        return PartySettingsEntity.builder().partyCode(s.getPartyCode()).spotifyAccessToken(s.getSpotifyAccessToken())
                .spotifyRefreshToken(s.getSpotifyRefreshToken()).spotifyTokenExpiresAt(s.getSpotifyTokenExpiresAt()).build();
    }

    private void givenTokenExpiringAt(Instant expiresAt) {
        stored.set(PartySettingsEntity.builder().partyCode(PARTY).spotifyAccessToken("old-access")
                .spotifyRefreshToken("old-refresh").spotifyTokenExpiresAt(expiresAt).build());
    }

    private static final String NEW_TOKENS =
            "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}";

    @Test
    @DisplayName("a token good for more than 5 minutes is used as it is")
    void shouldUseAFreshToken() {
        givenTokenExpiringAt(Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThat(service.getRefreshedAccessToken(PARTY)).isEqualTo("old-access");
        server.verify();
    }

    @Test
    @DisplayName("an expiring token is refreshed with the refresh token, and the new tokens are saved")
    void shouldRefreshAnExpiringToken() {
        givenTokenExpiringAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "refresh_token", "old-refresh")))
                .andRespond(withSuccess(NEW_TOKENS, MediaType.APPLICATION_JSON));

        assertThat(service.getRefreshedAccessToken(PARTY)).isEqualTo("new-access");
        assertThat(stored.get().getSpotifyRefreshToken()).isEqualTo("new-refresh");
        assertThat(stored.get().getSpotifyTokenExpiresAt()).isAfter(Instant.now().plus(50, ChronoUnit.MINUTES));
        server.verify();
    }

    @Test
    @DisplayName("two requests that find the token expiring refresh it once; the second one uses what the first saved (review 4.7)")
    void shouldRefreshOnce_whenTwoRequestsFindTheTokenExpiring() throws Exception {
        givenTokenExpiringAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        CountDownLatch refreshing = new CountDownLatch(1);
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI)).andRespond(request -> {
            refreshing.countDown();
            try {
                Thread.sleep(300); // Spotify answering; the second request comes meanwhile
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(NEW_TOKENS, MediaType.APPLICATION_JSON).createResponse(request);
        });

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> service.getRefreshedAccessToken(PARTY));
        assertThat(refreshing.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> service.getRefreshedAccessToken(PARTY));

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("new-access");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("new-access");
        assertThat(stored.get().getSpotifyRefreshToken()).isEqualTo("new-refresh");
        server.verify();
    }
}
