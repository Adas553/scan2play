package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PartySettingsEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static com.scan2play.integration.SpotifyApiConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyAuthService {

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
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

    /**
     * Pre-calculates the Basic Auth header for Spotify API requests.
     */
    @PostConstruct
    public void init() {
        String auth = clientId + ":" + clientSecret;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        basicAuthHeader = "Basic " + new String(encodedAuth);
    }

    /**
     * Generates the Spotify Authorization URL for the DJ to connect their account.
     *
     * @param partyCode The unique code for the party session (sent as 'state').
     * @return The URL to redirect the DJ to.
     */
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

    /**
     * Exchanges an authorization code for Spotify access and refresh tokens.
     *
     * @param code      The authorization code received from the callback.
     * @param partyCode The party session identifier.
     */
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
                String accessToken = root.path(JsonKeys.ACCESS_TOKEN).asText();
                String refreshToken = root.path(JsonKeys.REFRESH_TOKEN).asText();
                int expiresIn = root.path(JsonKeys.EXPIRES_IN).asInt();

                partySettingsCommandService.updateSettings(partyCode, settings -> {
                    settings.setSpotifyAccessToken(accessToken);
                    if (refreshToken != null && !refreshToken.isEmpty()) {
                        settings.setSpotifyRefreshToken(refreshToken);
                    }
                    settings.setSpotifyTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
                });
                log.info("Spotify tokens updated for party: {}", partyCode);
            }
        } catch (Exception e) {
            log.error("Party [{}]: Error exchanging code for token", partyCode, e);
            throw new RuntimeException("Failed to exchange code for token", e);
        }
    }

    /**
     * Retrieves a valid access token for the given party.
     * Refreshes the token automatically if it is expired or close to expiration.
     *
     * @param partyCode The unique party identifier.
     * @return A valid Spotify access token.
     */
    public String getRefreshedAccessToken(String partyCode) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

        if (settings.getSpotifyAccessToken() == null) {
            throw new IllegalStateException("Spotify not connected for party: " + partyCode);
        }

        // Check if token is about to expire in less than 5 minutes
        if (settings.getSpotifyTokenExpiresAt() != null && settings.getSpotifyTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            return settings.getSpotifyAccessToken();
        }

        log.info("Party [{}]: Access token expired or about to expire. Refreshing...", partyCode);
        return refreshAccessToken(settings);
    }

    /**
     * Internal method to refresh the Spotify access token using the refresh token.
     */
    private String refreshAccessToken(PartySettingsEntity settings) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(PARAM_GRANT_TYPE, GrantTypes.REFRESH_TOKEN);
        body.add(PARAM_REFRESH_TOKEN, settings.getSpotifyRefreshToken());

        try {
            String responseBody = postToTokenEndpoint(body);
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                String accessToken = root.path(JsonKeys.ACCESS_TOKEN).asText();
                int expiresIn = root.path(JsonKeys.EXPIRES_IN).asInt();

                // Refresh token might be updated in the response, but if not, reuse the old one.
                String newRefreshToken = root.has(JsonKeys.REFRESH_TOKEN)
                        ? root.path(JsonKeys.REFRESH_TOKEN).asText() 
                        : settings.getSpotifyRefreshToken();

                partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> {
                    s.setSpotifyAccessToken(accessToken);
                    s.setSpotifyRefreshToken(newRefreshToken);
                    s.setSpotifyTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
                });
                return accessToken;
            }
        } catch (Exception e) {
            log.error("Party [{}]: Error refreshing token", settings.getPartyCode(), e);
        }
        return null;
    }

    /**
     * Performs a POST request to Spotify's token endpoint with form data and Basic Authentication.
     */
    private String postToTokenEndpoint(MultiValueMap<String, String> body) {
        return restClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
