package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * Finds party settings by its unique code.
     * Caches the result using the party code as the key.
     *
     * @param partyCode The 5-character party code.
     * @return The found PartySettingsEntity or creates a new one (for now, mainly for testing/demo).
     */
    @Cacheable(value = "partySettings", key = "#partyCode")
    public PartySettingsEntity getSettings(String partyCode) {
        log.debug("Fetching party settings for code: {}", partyCode);
        return partySettingsRepository.findByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyCode));
    }

    /**
     * Creates a new party session.
     *
     * @return The newly created PartySettingsEntity with a unique code.
     */
    @Transactional
    public PartySettingsEntity createNewParty() {
        PartySettingsEntity party = new PartySettingsEntity();
        party.setPartyCode(CodeGenerator.generatePartyCode());
        party.setGlobalVibe(VibeType.ANY);
        party.setActiveProvider(MusicProviderType.SPOTIFY);
        party.setPlaybackMode(PlaybackMode.MANUAL);
        return partySettingsRepository.save(party);
    }

    /**
     * Updates the party settings and invalidates the cache for the specific party code.
     */
    @Transactional
    @CacheEvict(value = "partySettings", key = "#partyCode")
    public void updateSettings(String partyCode, Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = partySettingsRepository.findByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found for update: " + partyCode));

        updater.accept(settings);
        partySettingsRepository.save(settings);
        log.debug("Party settings updated for code: {}", partyCode);
    }

    /**
     * Specific method to update Spotify tokens, ensuring cache invalidation.
     */
    @Transactional
    @CacheEvict(value = "partySettings", key = "#partyCode")
    public void updateSpotifyTokens(String partyCode, String accessToken, String refreshToken, int expiresInSeconds) {
        updateSettings(partyCode, settings -> {
            settings.setSpotifyAccessToken(accessToken);
            if (refreshToken != null && !refreshToken.isEmpty()) {
                settings.setSpotifyRefreshToken(refreshToken);
            }
            settings.setSpotifyTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresInSeconds));
        });
        log.info("Spotify tokens updated for party: {}", partyCode);
    }

    /**
     * Finds all parties with pagination. Used by the dashboard to find a default party.
     */
    public Page<PartySettingsEntity> findAll(Pageable pageable) {
        return partySettingsRepository.findAll(pageable);
    }
}
