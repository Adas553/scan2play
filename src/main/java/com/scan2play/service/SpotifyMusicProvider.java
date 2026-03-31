package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.ClientCredentials;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class SpotifyMusicProvider implements MusicProvider {

    private static final String SPOTIFY_URL_KEY = "spotify";
    private static final String SPOTIFY_TRACK_PREFIX = "spotify:track:";

    private final String clientId;
    private final String clientSecret;
    private final SpotifyAuthService spotifyAuthService;
    private final ReentrantLock tokenLock = new ReentrantLock();

    // Volatile for safe publication in double-checked locking
    private volatile String clientAccessToken;
    private volatile Instant clientTokenExpiration = Instant.MIN;

    public SpotifyMusicProvider(
            @Value("${spotify.client-id}") String clientId,
            @Value("${spotify.client-secret}") String clientSecret,
            SpotifyAuthService spotifyAuthService) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.spotifyAuthService = spotifyAuthService;
    }

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.SPOTIFY;
    }

    /**
     * Searches for a track using Application Client Credentials flow.
     * Thread-safe and uses cached token.
     *
     * @param query search phrase
     * @return Spotify open URL or null if not found
     */
    @Override
    public String findTrackUrl(String query) {
        try {
            String token = getClientAccessToken();
            
            // Create a lightweight API instance just for this request
            SpotifyApi api = new SpotifyApi.Builder()
                    .setAccessToken(token)
                    .build();

            var searchResult = api.searchTracks(query)
                    .limit(1)
                    .build()
                    .execute();

            if (searchResult.getItems() != null && searchResult.getItems().length > 0) {
                Track track = searchResult.getItems()[0];
                return track.getExternalUrls().get(SPOTIFY_URL_KEY);
            }
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            log.error("Spotify search failed for query: '{}'", query, e);
        }
        return null;
    }

    /**
     * Adds a track to the DJ's playback queue.
     * Requires User Authorization flow (on behalf of the DJ).
     *
     * @param partyCode The party context
     * @param trackUrl  The Spotify URL or URI of the track
     */
    @Override
    public void addToQueue(String partyCode, String trackUrl) {
        if (trackUrl == null || trackUrl.isBlank()) {
            return;
        }

        String trackUri = convertUrlToUri(trackUrl);
        if (trackUri == null) {
            log.warn("Party [{}]: Invalid Spotify URL format: {}", partyCode, trackUrl);
            return;
        }

        try {
            // 1. Get fresh user token for the DJ (handles refresh token logic internally)
            String userToken = spotifyAuthService.getRefreshedAccessToken(partyCode);

            // 2. Execute action on behalf of the user
            SpotifyApi userApi = new SpotifyApi.Builder()
                    .setAccessToken(userToken)
                    .build();

            userApi.addItemToUsersPlaybackQueue(trackUri).build().execute();
            log.info("Party [{}]: Added to queue: {}", partyCode, trackUri);

        } catch (Exception e) {
            log.error("Party [{}]: Failed to add to Spotify queue", partyCode, e);
        }
    }

    /**
     * Gets a valid Client Access Token, refreshing it only if expired.
     * Uses double-checked locking with {@link ReentrantLock} for high concurrency:
     * <ul>
     *     <li>Fast path (no lock): returns cached token if still valid</li>
     *     <li>Slow path (locked): refreshes token, only one thread performs the refresh</li>
     * </ul>
     */
    private String getClientAccessToken() {
        // Fast path: no lock needed when token is still valid
        if (clientAccessToken != null && Instant.now().isBefore(clientTokenExpiration)) {
            return clientAccessToken;
        }

        tokenLock.lock();
        try {
            // Double-check after acquiring lock (another thread may have refreshed already)
            if (clientAccessToken != null && Instant.now().isBefore(clientTokenExpiration)) {
                return clientAccessToken;
            }

            log.debug("Refreshing Spotify Client Credentials Token...");
            SpotifyApi api = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .build();

            ClientCredentials credentials = api.clientCredentials().build().execute();

            this.clientAccessToken = credentials.getAccessToken();
            // Buffer of 60 seconds to be safe
            this.clientTokenExpiration = Instant.now().plusSeconds(credentials.getExpiresIn() - 60);

            return this.clientAccessToken;
        } catch (Exception e) {
            log.error("Failed to obtain Spotify Client Credentials", e);
            throw new RuntimeException("Spotify Auth Failed", e);
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Helper to convert various Spotify URL formats to a standard URI.
     * Supported formats:
     * - spotify:track:ID
     * - https://open.spotify.com/track/ID
     * - https://open.spotify.com/track/ID?si=...
     */
    private String convertUrlToUri(String url) {
        if (url.startsWith(SPOTIFY_TRACK_PREFIX)) {
            return url;
        }
        
        if (url.contains("/track/")) {
            try {
                String id = url.substring(url.lastIndexOf("/track/") + 7);
                // Remove query parameters
                int queryParamIndex = id.indexOf("?");
                if (queryParamIndex != -1) {
                    id = id.substring(0, queryParamIndex);
                }
                // Remove any trailing slashes or path segments
                int slashIndex = id.indexOf("/");
                if (slashIndex != -1) {
                    id = id.substring(0, slashIndex);
                }
                
                if (!id.isBlank()) {
                    return SPOTIFY_TRACK_PREFIX + id;
                }
            } catch (Exception e) {
                log.warn("Failed to parse Spotify URL: {}", url);
            }
        }
        return null;
    }
}
