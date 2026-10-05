package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsQueryService {

    /** The cache of the settings by party code (AppConfig); emptied for a party by every change of its settings. */
    public static final String CACHE = "partySettings";

    private final PartySettingsRepository partySettingsRepository;
    private final CacheManager cacheManager;

    /**
     * Finds party settings by its unique code — from the cache after the first read.
     * <p>
     * Every caller gets its <em>own copy</em>: the cached entity is never handed out, so a {@code setX(...)} on the result
     * cannot change what every other request sees without anything being saved. Changes go through
     * {@link PartySettingsCommandService#updateSettings}, which empties the cache entry after its commit.
     *
     * @param partyCode The 5-character party code.
     * @return A copy of the party's settings.
     * @throws IllegalArgumentException if there is no such party
     */
    public PartySettingsEntity getSettings(String partyCode) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE);
        PartySettingsEntity cached = cache.get(partyCode, PartySettingsEntity.class);
        if (cached == null) {
            log.debug("Fetching party settings for code: {}", partyCode);
            cached = partySettingsRepository.findByPartyCode(partyCode)
                    .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyCode));
            cache.put(partyCode, cached);
        }
        return cached.toBuilder().build();
    }
}
