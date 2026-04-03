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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsCommandService {

    private final PartySettingsRepository partySettingsRepository;

    /**
     * Retrieves the existing party for the given DJ (ownerId) or creates a new one if it doesn't exist.
     * The provider is only used when creating a NEW party — existing parties keep their original provider.
     *
     * @param ownerId  The unique identifier of the DJ (from OAuth2).
     * @param provider The music provider chosen on the landing page.
     * @return The PartySettingsEntity associated with this DJ.
     */
    @Transactional
    public PartySettingsEntity getOrCreatePartyForDj(String ownerId, MusicProviderType provider) {
        return partySettingsRepository.findByOwnerId(ownerId)
                .orElseGet(() -> createNewParty(ownerId, provider));
    }

    /**
     * Creates a new party session for a specific owner.
     *
     * @param ownerId  The unique identifier of the DJ.
     * @param provider The music provider to use for this party.
     * @return The newly created PartySettingsEntity with a unique code.
     */
    private PartySettingsEntity createNewParty(String ownerId, MusicProviderType provider) {
        PartySettingsEntity party = PartySettingsEntity.builder()
                .ownerId(ownerId)
                .partyCode(CodeGenerator.generatePartyCode())
                .globalVibe(VibeType.ANY)
                .activeProvider(provider)
                .playbackMode(PlaybackMode.MANUAL)
                .active(true)
                .build();

        log.info("Creating new party for owner: {} with code: {}", ownerId, party.getPartyCode());
        return partySettingsRepository.save(party);
    }

    /**
     * Updates party settings and refreshes the cache with the new state.
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
}
