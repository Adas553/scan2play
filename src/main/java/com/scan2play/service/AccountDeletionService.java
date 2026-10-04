package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles complete deletion of all data associated with a DJ account.
 * Required by Google API Services User Data Policy — users must be able to delete their data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    /**
     * The caches that hold a party's data under its code (AppConfig). Without evicting them the deleted party lived on in
     * memory — its settings for up to 24 h — and guests could still send requests to it.
     */
    static final List<String> PARTY_CACHES = List.of("partySettings", "dashboardQueue");

    private final PartySettingsRepository partySettingsRepository;
    private final SongRequestRepository songRequestRepository;
    private final FeedbackRepository feedbackRepository;
    private final CacheManager cacheManager;

    /**
     * Deletes all data associated with the given DJ (owner).
     * This includes: song requests, feedback, and party settings.
     *
     * @param ownerId The OAuth2 owner ID (the Google subject).
     */
    @Transactional
    public void deleteAllUserData(String ownerId) {
        log.info("Starting account deletion for ownerId={}", ownerId);

        // 1. Find party settings to get partyCode (needed for song request cleanup)
        Optional<PartySettingsEntity> partyOpt = partySettingsRepository.findByOwnerId(ownerId);

        if (partyOpt.isPresent()) {
            String partyCode = partyOpt.get().getPartyCode();

            // 2. Delete all song requests for this party
            songRequestRepository.deleteByPartyCode(partyCode);
            log.info("Deleted song requests for partyCode={}", partyCode);

            // 3. Delete party settings
            partySettingsRepository.delete(partyOpt.get());
            log.info("Deleted party settings for partyCode={}", partyCode);

            // 3b. ... and forget the party in memory
            evictPartyCachesAfterCommit(partyCode);
        }

        // 4. Delete all feedback from this owner
        feedbackRepository.deleteByOwnerId(ownerId);
        log.info("Deleted feedback for ownerId={}", ownerId);

        log.info("Account deletion completed for ownerId={}", ownerId);
    }

    /**
     * After the commit, not before: a request that read the party while this transaction was still open would otherwise put
     * it back into the cache. Without a transaction (a unit test) the caches are evicted at once.
     */
    private void evictPartyCachesAfterCommit(String partyCode) {
        Runnable evict = () -> PARTY_CACHES.stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> cache.evict(partyCode));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }
}

