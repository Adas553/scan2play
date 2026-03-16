package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class YouTubeMusicProvider implements MusicProvider {

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.YOUTUBE;
    }

    /**
     * Searches for a track on YouTube based on the provided search query.
     * Currently, this method constructs a simple YouTube search URL.
     * In a future iteration, this could integrate with the YouTube Data API
     * to find specific track URLs.
     *
     * @param searchQuery the text to search for (e.g., song title and artist)
     * @return a URL to YouTube search results for the given query.
     */
    @Override
    public String findTrackUrl(String searchQuery) {
        log.info("Searching for track on YouTube: {}", searchQuery);
        // Return a simple search URL as a fallback for now
        String encodedQuery = searchQuery.replace(" ", "+");
        return "https://www.youtube.com/results?search_query=" + encodedQuery;
    }

    @Override
    public void addToQueue(String trackId) {
        throw new UnsupportedOperationException("YouTube queue management not implemented yet");
    }
}
