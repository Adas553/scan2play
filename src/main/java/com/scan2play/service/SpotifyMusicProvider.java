package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.search.simplified.SearchTracksRequest;

import java.io.IOException;

@Service
@Slf4j
public class SpotifyMusicProvider implements MusicProvider {
    private final SpotifyApi spotifyApi;
    private final SpotifyAuthService spotifyAuthService;

    /**
     * Constructs the SpotifyService with credentials from application properties.
     *
     * @param clientId     Spotify application client ID
     * @param clientSecret Spotify application client secret
     * @param spotifyAuthService Service to handle user-specific auth
     */
    public SpotifyMusicProvider(
            @Value("${spotify.client-id}") String clientId,
            @Value("${spotify.client-secret}") String clientSecret,
            SpotifyAuthService spotifyAuthService) {

        this.spotifyAuthService = spotifyAuthService;
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .build();
    }

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.SPOTIFY;
    }

    /**
     * Authenticates the application using Client Credentials flow
     * and sets the access token for subsequent API calls.
     * <p>
     * This method should be called before any API requests that require authentication.
     */
    private void authenticate() {
        try {
            // Obtain an access token using client credentials
            var clientCredentialsRequest = spotifyApi.clientCredentials().build();
            var clientCredentials = clientCredentialsRequest.execute();
            // Set the obtained token on the SpotifyApi instance
            spotifyApi.setAccessToken(clientCredentials.getAccessToken());
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            log.error("Spotify authentication failed", e);
        }
    }

    /**
     * Searches for a track on Spotify using the provided query
     * and returns the Spotify URL of the best matching track (if found).
     *
     * @param query search phrase
     * @return Spotify track URL
     * or {@code null} if no track was found or an error occurred
     */
    @Override
    public String findTrackUrl(String query) {
        // Ensure we have a valid access token before making the search
        authenticate();
        try {
            SearchTracksRequest searchRequest = spotifyApi.searchTracks(query)
                    .limit(1) // We only need the top result
                    .build();

            var searchResult = searchRequest.execute();
            if (searchResult.getItems().length > 0) {
                Track track = searchResult.getItems()[0];
                // Return the Spotify open link (web / app compatible)
                return track.getExternalUrls().get("spotify");
            }
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            log.error("Spotify track search failed for query: '{}'", query, e);
        }
        return null;
    }

    @Override
    public void addToQueue(String partyCode, String trackUri) {
        if (trackUri == null || trackUri.isBlank()) {
            log.warn("Party [{}]: Cannot add empty track URI to queue", partyCode);
            return;
        }

        // Basic validation/conversion if we get a URL instead of URI
        // Example URL: https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT
        // Example URI: spotify:track:4cOdK2wGLETKBW3PvgPWqT
        if (trackUri.startsWith("https://open.spotify.com/track/")) {
            String id = trackUri.substring(trackUri.lastIndexOf('/') + 1);
            // Remove query params if any
            if (id.contains("?")) {
                id = id.substring(0, id.indexOf("?"));
            }
            trackUri = "spotify:track:" + id;
        }

        try {
            // Retrieve fresh token for the current party/DJ
            String userToken = spotifyAuthService.getRefreshedAccessToken(partyCode);

            SpotifyApi userApi = new SpotifyApi.Builder()
                    .setAccessToken(userToken)
                    .build();

            userApi.addItemToUsersPlaybackQueue(trackUri).build().execute();
            log.info("Party [{}]: Successfully added track to Spotify queue: {}", partyCode, trackUri);
        } catch (Exception e) {
            log.error("Party [{}]: Failed to add track to Spotify queue: {}", partyCode, trackUri, e);
        }
    }
}
