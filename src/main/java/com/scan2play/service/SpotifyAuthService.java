package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PartySettingsEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static com.scan2play.integration.SpotifyApiConstants.*; // Static import for convenience

@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyAuthService {

    private final PartySettingsService partySettingsService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${spotify.client-id}")
    private String clientId;

    @Value("${spotify.client-secret}")
    private String clientSecret;

    @Value("${spotify.oauth.redirect-uri}")
    private String redirectUri;

    @Value("${spring.security.oauth2.client.provider.spotify.authorization-uri}")
    private String authorizationUri;

    @Value("${spring.security.oauth2.client.provider.spotify.token-uri}")
    private String tokenUri;

    private String basicAuthHeader;

    @PostConstruct
    public void init() {
        String auth = clientId + ":" + clientSecret;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        basicAuthHeader = "Basic " + new String(encodedAuth);
    }

    public String getAuthorizationUrl(String partyCode) {
        return UriComponentsBuilder.fromUriString(authorizationUri)
                .queryParam(PARAM_CLIENT_ID, clientId)
                .queryParam(PARAM_RESPONSE_TYPE, VALUE_RESPONSE_TYPE_CODE)
                .queryParam(PARAM_REDIRECT_URI, redirectUri)
                .queryParam(PARAM_SCOPE, Scopes.PLAYBACK_SCOPES)
                .queryParam(PARAM_STATE, partyCode)
                .encode()
                .build()
                .toUriString();
    }

    public void exchangeCodeForToken(String code, String partyCode) {
        log.info("Party [{}]: Exchanging authorization code for access token", partyCode);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(PARAM_GRANT_TYPE, GrantTypes.AUTHORIZATION_CODE);
        body.add(PARAM_CODE, code);
        body.add(PARAM_REDIRECT_URI, redirectUri);

        try {
            String responseBody = postToTokenEndpoint(body);
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                String accessToken = root.path("access_token").asText();
                String refreshToken = root.path("refresh_token").asText();
                int expiresIn = root.path("expires_in").asInt();

                partySettingsService.updateSpotifyTokens(partyCode, accessToken, refreshToken, expiresIn);
            }
        } catch (Exception e) {
            log.error("Party [{}]: Error exchanging code for token", partyCode, e);
            throw new RuntimeException("Failed to exchange code for token", e);
        }
    }

    public String getRefreshedAccessToken(String partyCode) {
        PartySettingsEntity settings = partySettingsService.getSettings(partyCode);

        if (settings.getSpotifyAccessToken() == null) {
            throw new IllegalStateException("Spotify not connected for party: " + partyCode);
        }

        if (settings.getSpotifyTokenExpiresAt() != null && settings.getSpotifyTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            return settings.getSpotifyAccessToken();
        }

        log.info("Party [{}]: Access token expired or about to expire. Refreshing...", partyCode);
        return refreshAccessToken(settings);
    }

    private String refreshAccessToken(PartySettingsEntity settings) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(PARAM_GRANT_TYPE, GrantTypes.REFRESH_TOKEN);
        body.add(PARAM_REFRESH_TOKEN, settings.getSpotifyRefreshToken());

        try {
            String responseBody = postToTokenEndpoint(body);
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                String accessToken = root.path("access_token").asText();
                int expiresIn = root.path("expires_in").asInt();

                String newRefreshToken = root.has("refresh_token") ? root.path("refresh_token").asText() : settings.getSpotifyRefreshToken();

                partySettingsService.updateSpotifyTokens(settings.getPartyCode(), accessToken, newRefreshToken, expiresIn);
                return accessToken;
            }
        } catch (Exception e) {
            log.error("Party [{}]: Error refreshing token", settings.getPartyCode(), e);
        }
        return null;
    }

    /**
     * Helper method to perform a POST request to Spotify's token endpoint.
     *
     * @param body The request body containing grant type and other parameters.
     * @return The response body as a string, or null if the request fails.
     */
    private String postToTokenEndpoint(MultiValueMap<String, String> body) {
        return restClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", basicAuthHeader)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
