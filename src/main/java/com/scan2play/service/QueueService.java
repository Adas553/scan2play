package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QueueService {

    private final Map<MusicProviderType, MusicProvider> providers;

    // Spring automatically injects all beans implementing MusicProvider
    public QueueService(List<MusicProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(MusicProvider::getType, Function.identity()));
    }

    /**
     * Resolves a track URL using the specified music provider.
     * <p>
     * This method delegates the search to the appropriate implementation of {@link MusicProvider}
     * based on the {@code preferredProvider} type.
     *
     * @param query             the search query (e.g., song title, artist)
     * @param preferredProvider the type of music provider to use (e.g., SPOTIFY, YOUTUBE)
     * @return the URL of the track found by the provider
     * @throws IllegalArgumentException if the requested provider is not supported or not configured
     */
    public String resolveTrack(String query, MusicProviderType preferredProvider) {
        MusicProvider provider = providers.get(preferredProvider);

        if (provider == null) {
            log.error("Provider not found: {}", preferredProvider);
            throw new IllegalArgumentException("Unsupported music provider");
        }

        return provider.findTrackUrl(query);
    }

    /**
     * Asynchronously adds a track to the playback queue of the active provider.
     *
     * @param partyCode         The unique code of the party.
     * @param trackUrl          The URL or ID of the track to add.
     * @param preferredProvider The provider to use.
     */
    @Async
    public void addToQueue(String partyCode, String trackUrl, MusicProviderType preferredProvider) {
        MusicProvider provider = providers.get(preferredProvider);
        if (provider != null) {
            try {
                log.info("Party [{}]: Attempting to add track to queue: {} (Provider: {})", partyCode, trackUrl, preferredProvider);
                provider.addToQueue(partyCode, trackUrl);
            } catch (Exception e) {
                // Catching exception to prevent thread crash, although @Async handles it gracefully mostly
                log.error("Party [{}]: Failed to add track to queue asynchronously", partyCode, e);
            }
        } else {
            log.warn("Party [{}]: Provider not found for auto-queue: {}", partyCode, preferredProvider);
        }
    }
}
