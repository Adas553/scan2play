package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsQueryService {

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
}
