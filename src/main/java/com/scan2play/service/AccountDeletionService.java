package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.DjPlanRepository;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import com.scan2play.repository.PushSubscriptionRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.repository.StaffInvitationRepository;
import com.scan2play.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    static final List<String> PARTY_CACHES = List.of(PartySettingsQueryService.CACHE, DjService.QUEUE_CACHE, DjLibraryService.CACHE);

    private final PartySettingsRepository partySettingsRepository;
    private final SongRequestRepository songRequestRepository;
    private final FeedbackRepository feedbackRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final PartyStaffRepository partyStaffRepository;
    private final StaffInvitationRepository staffInvitationRepository;
    private final DjPlanRepository djPlanRepository;
    private final CacheManager cacheManager;

    /**
     * Deletes all data associated with the given DJ (owner).
     * This includes: song requests, feedback, party settings, the devices that took notifications and the DJ's plan (V35).
     * <p>
     * The party's locks come first, in the order the rest of the app takes them (review 2026-10-14 W9, {@code AccountDeletionIT}):
     * the guests' requests' lock ("S2PR", {@link SongRequestCommandService}), then the party's row. Holding the first, the deletion
     * waits for a guest's request being saved, and a request the AI was still judging waits for the deletion — then finds no party
     * and saves nothing. Taking the row before any of its staff's invitations, it waits for a "Dołącz" ({@code StaffInvitationService},
     * the row, then the invitation) instead of deadlocking with it.
     *
     * @param ownerId The OAuth2 owner ID (the Google subject).
     */
    @Transactional
    public void deleteAllUserData(String ownerId) {
        log.info("Starting account deletion for ownerId={}", ownerId);

        // 1. Find party settings to get partyCode (needed for song request cleanup) — and take its locks
        Optional<PartySettingsEntity> partyOpt = partySettingsRepository.findByOwnerId(ownerId);
        partyOpt.ifPresent(found -> songRequestRepository.lockRequests(SongRequestCommandService.lockKey(found.getPartyCode())));
        partyOpt = partyOpt.flatMap(found -> partySettingsRepository.lockByPartyCode(found.getPartyCode()));

        if (partyOpt.isPresent()) {
            String partyCode = partyOpt.get().getPartyCode();

            // 2. Delete all song requests for this party (one statement), its staff (V30) and its invitations by e-mail (V34)
            songRequestRepository.deleteByPartyCode(partyCode);
            partyStaffRepository.deleteByParty(partyCode);
            staffInvitationRepository.deleteByParty(partyCode);
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

        // 5. The devices that took notifications of new requests
        pushSubscriptionRepository.deleteByOwnerId(ownerId);

        // 6. Wherever the person worked on another party's staff (V30)
        partyStaffRepository.deleteByMember(ownerId);

        // 7. The DJ's plan (V35) — and its copy in memory
        djPlanRepository.deleteById(ownerId);
        AfterCommit.run(() -> {
            var plans = cacheManager.getCache(PlanService.CACHE);
            if (plans != null) {
                plans.evict(ownerId);
            }
        });

        log.info("Account deletion completed for ownerId={}", ownerId);
    }

    /**
     * After the commit, not before: a request that read the party while this transaction was still open would otherwise put
     * it back into the cache. Without a transaction (a unit test) the caches are evicted at once.
     */
    private void evictPartyCachesAfterCommit(String partyCode) {
        AfterCommit.run(() -> PARTY_CACHES.stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> cache.evict(partyCode)));
    }
}

