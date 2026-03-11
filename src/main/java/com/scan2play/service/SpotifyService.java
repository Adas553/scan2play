package com.scan2play.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.search.simplified.SearchTracksRequest;

/**
 * Service responsible for interacting with the Spotify API.
 */
@Service
@Slf4j
public class SpotifyService {

    private final SpotifyApi spotifyApi;

    /**
     * Constructs the SpotifyService with credentials from application properties.
     *
     * @param clientId     Spotify application client ID
     * @param clientSecret Spotify application client secret
     */
    public SpotifyService(
            @Value("${spotify.client-id}") String clientId,
            @Value("${spotify.client-secret}") String clientSecret) {

        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .build();
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
        } catch (Exception e) {
            log.error("Spotify authentication failed", e);
        }
    }

    /**
     * Searches for a track on Spotify using the provided query
     * and returns the Spotify URL of the best matching track (if found).
     *
     * @param query search phrase (e.g. "artist - title", "song name", "The Weeknd Blinding Lights")
     * @return Spotify track URL (e.g. "https://open.spotify.com/track/...")
     * or {@code null} if no track was found or an error occurred
     */
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
        } catch (Exception e) {
            log.error("Spotify track search failed for query: '{}'", query, e);
        }
        return null;
    }
}