package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A party whose DJ plays from their own software ({@link MusicProviderType#REQUESTS_ONLY}): nothing is searched and nothing is
 * queued. A song gets a link to YouTube's search results for its name, for the DJ to look at a song they do not know — a page the
 * DJ's browser opens, so no YouTube API call and no share of the daily search budget.
 */
@Service
public class RequestsOnlyMusicProvider implements MusicProvider {

    static final String YOUTUBE_SEARCH = "https://www.youtube.com/results?search_query=";

    @Override
    public MusicProviderType getType() {
        return MusicProviderType.REQUESTS_ONLY;
    }

    @Override
    public String findTrackUrl(String searchQuery) {
        if (searchQuery == null || searchQuery.isBlank()) {
            return null;
        }
        return YOUTUBE_SEARCH + URLEncoder.encode(searchQuery.strip(), StandardCharsets.UTF_8);
    }

    /** The DJ's software plays the songs: there is no queue to add to. */
    @Override
    public void addToQueue(String partyCode, String trackId) {
        throw new UnsupportedOperationException("A requests-only party has no player queue");
    }
}
