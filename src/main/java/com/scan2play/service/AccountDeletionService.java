package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Handles complete deletion of all data associated with a DJ account.
 * Required by Google API Services User Data Policy — users must be able to delete their data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    private final PartySettingsRepository partySettingsRepository;
    private final SongRequestRepository songRequestRepository;
    private final FeedbackRepository feedbackRepository;

    /**
     * Deletes all data associated with the given DJ (owner).
     * This includes: song requests, feedback, and party settings.
     *
     * @param ownerId The OAuth2 owner ID (Google or Spotify subject).
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
        }

        // 4. Delete all feedback from this owner
        feedbackRepository.deleteByOwnerId(ownerId);
        log.info("Deleted feedback for ownerId={}", ownerId);

        log.info("Account deletion completed for ownerId={}", ownerId);
    }
}

