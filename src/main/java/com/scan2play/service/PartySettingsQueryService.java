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
     * <p>
     * The database is read inside the cache's own load of the key ({@code Cache.get(key, loader)}, atomic per key in Caffeine): an
     * eviction that comes while a read is on its way waits for it and then removes what it loaded. Read and put apart, a read that
     * took the row just before a change committed put the old copy back after the change's eviction — for 24 hours (review
     * 2026-10-14).
     *
     * @param partyCode The 5-character party code.
     * @return A copy of the party's settings.
     * @throws IllegalArgumentException if there is no such party
     */
    public PartySettingsEntity getSettings(String partyCode) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE);
        PartySettingsEntity cached;
        try {
            cached = cache.get(partyCode, () -> {
                log.debug("Fetching party settings for code: {}", partyCode);
                return partySettingsRepository.findByPartyCode(partyCode)
                        .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyCode));
            });
        } catch (Cache.ValueRetrievalException e) {
            if (e.getCause() instanceof IllegalArgumentException unknown) {
                throw unknown;   // nothing cached for an unknown party
            }
            throw e;
        }
        return Objects.requireNonNull(cached).toBuilder().build();
    }
}
