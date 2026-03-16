package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsService {

    private final PartySettingsRepository partySettingsRepository;

    /**
     * Retrieves the current party settings, cached for performance.
     */
    @Cacheable("partySettings")
    public PartySettingsEntity getSettings() {
        log.debug("Fetching party settings from DB");
        return partySettingsRepository.findById(1L)
                .orElseGet(() -> {
                    PartySettingsEntity entity = new PartySettingsEntity();
                    entity.setId(1L);
                    entity.setGlobalVibe(VibeType.ANY);
                    entity.setActiveProvider(MusicProviderType.SPOTIFY);
                    entity.setPlaybackMode(PlaybackMode.MANUAL);
                    return partySettingsRepository.save(entity);
                });
    }

    /**
     * Updates the party settings and invalidates the cache.
     */
    @Transactional
    @CacheEvict(value = "partySettings", allEntries = true)
    public void updateSettings(Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = partySettingsRepository.findById(1L)
                .orElseGet(this::getSettings); // Use getSettings to create if missing
        updater.accept(settings);
        partySettingsRepository.save(settings);
        log.debug("Party settings updated and cache evicted");
    }

    /**
     * Specific method to update Spotify tokens, ensuring cache invalidation.
     */
    @Transactional
    @CacheEvict(value = "partySettings", allEntries = true)
    public void updateSpotifyTokens(String accessToken, String refreshToken, int expiresInSeconds) {
        updateSettings(settings -> {
            settings.setSpotifyAccessToken(accessToken);
            if (refreshToken != null && !refreshToken.isEmpty()) {
                settings.setSpotifyRefreshToken(refreshToken);
            }
            settings.setSpotifyTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresInSeconds));
        });
        log.info("Spotify tokens updated");
    }
}
