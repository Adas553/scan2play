package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsCommandService {

    private final PartySettingsRepository partySettingsRepository;
    private final CacheManager cacheManager;

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
     * Updates party settings. The cached settings of the party are dropped once the change is committed, so the next
     * {@link PartySettingsQueryService#getSettings} reads the new state (dropped before the commit, a concurrent read could
     * put the old one back).
     *
     * @param partyCode The unique code of the party to update.
     * @param updater   A {@link Consumer} that applies the desired changes to the entity.
     * @return The updated {@link PartySettingsEntity} (the caller's own; it is not the cached one).
     */
    @Transactional
    public PartySettingsEntity updateSettings(String partyCode, Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = partySettingsRepository.findByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found for update: " + partyCode));

        updater.accept(settings);
        PartySettingsEntity saved = partySettingsRepository.save(settings);
        evictAfterCommit(partyCode);
        return saved;
    }

    private void evictAfterCommit(String partyCode) {
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache(PartySettingsQueryService.CACHE);
            if (cache != null) {
                cache.evict(partyCode);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();   // no transaction (a unit test)
        }
    }
}
