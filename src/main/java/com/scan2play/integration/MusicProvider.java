package com.scan2play.integration;

import com.scan2play.model.MusicProviderType;

/**
 * Common interface for all external music streaming services.
 */
public interface MusicProvider {

    /**
     * Returns the provider type (e.g., SPOTIFY, YOUTUBE).
     * Used by the factory/service to select the correct implementation.
     */
    MusicProviderType getType();

    /**
     * Searches for a track based on a search query.
     *
     * @param searchQuery The song title, artist, or mood description.
     * @return The external URL to the track or null if not found.
     */
    String findTrackUrl(String searchQuery);

    /**
     * Adds a track to the user's playback queue.
     *
     * @param trackId The unique identifier for the track (e.g., Spotify URI).
     */
    void addToQueue(String trackId);
}
