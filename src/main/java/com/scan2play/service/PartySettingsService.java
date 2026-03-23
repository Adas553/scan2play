package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachePut;
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
     * Finds party settings by its unique code.
     * Caches the result using the party code as the key.
     *
     * @param partyCode The 5-character party code.
     * @return The found PartySettingsEntity or throws exception if not found.
     */
    @Cacheable(value = "partySettings", key = "#partyCode")
    public PartySettingsEntity getSettings(String partyCode) {
        log.debug("Fetching party settings for code: {}", partyCode);
        return partySettingsRepository.findByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyCode));
    }

    /**
     * Retrieves the existing party for the given DJ (ownerId) or creates a new one if it doesn't exist.
     *
     * @param ownerId The unique identifier of the DJ (from OAuth2).
     * @return The PartySettingsEntity associated with this DJ.
     */
    @Transactional
    public PartySettingsEntity getOrCreatePartyForDj(String ownerId) {
        return partySettingsRepository.findByOwnerId(ownerId)
                .orElseGet(() -> createNewParty(ownerId));
    }

    /**
     * Creates a new party session for a specific owner.
     *
     * @param ownerId The unique identifier of the DJ.
     * @return The newly created PartySettingsEntity with a unique code.
     */
    private PartySettingsEntity createNewParty(String ownerId) {
        PartySettingsEntity party = PartySettingsEntity.builder()
                .ownerId(ownerId)
                .partyCode(CodeGenerator.generatePartyCode())
                .globalVibe(VibeType.ANY)
                .activeProvider(MusicProviderType.SPOTIFY)
                .playbackMode(PlaybackMode.MANUAL)
                .active(true)
                .build();

        log.info("Creating new party for owner: {} with code: {}", ownerId, party.getPartyCode());
        return partySettingsRepository.save(party);
    }

    /**
     * Updates party settings and refreshes the cache with the new state.
     * This is the preferred method for all modifications to ensure cache consistency.
     *
     * @param partyCode The unique code of the party to update.
     * @param updater   A {@link Consumer} that applies the desired changes to the entity.
     * @return The updated and re-cached {@link PartySettingsEntity}.
     */
    @Transactional
    @CachePut(value = "partySettings", key = "#partyCode")
    public PartySettingsEntity updateSettings(String partyCode, Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = partySettingsRepository.findByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found for update: " + partyCode));

        updater.accept(settings);
        return partySettingsRepository.save(settings);
    }

    /**
     * Specific method to update Spotify tokens, ensuring cache is updated.
     * It delegates the update logic to the central updateSettings method.
     */
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
     * Closes the active party for the given DJ by setting its 'active' flag to false.
     *
     * @param ownerId The unique identifier of the DJ.
     */
    @Transactional
    public void closeParty(String ownerId) {
        PartySettingsEntity party = getOrCreatePartyForDj(ownerId);
        if (party.isActive()) {
            log.info("Closing party for owner: {}", ownerId);
            updateSettings(party.getPartyCode(), p -> p.setActive(false));
        }
    }
}