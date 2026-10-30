package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartySettingsCommandService {

    private final PartySettingsRepository partySettingsRepository;
    private final CacheManager cacheManager;
    private final PlanService planService;
    private final SiteCounter siteCounter;
    private final PartyCodeService partyCodeService;

    /**
     * Retrieves the existing party for the given DJ (ownerId) or creates a new one if it doesn't exist.
     *
     * @param ownerId  The unique identifier of the DJ (from OAuth2).
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
     * @param ownerId  The unique identifier of the DJ.
     * @return The newly created PartySettingsEntity with a unique code.
     */
    private PartySettingsEntity createNewParty(String ownerId) {
        PartySettingsEntity party = PartySettingsEntity.builder()
                .ownerId(ownerId)
                .partyCode(partyCodeService.freshCode())   // never a code another party has or had (V49)
                .globalVibe(VibeType.ANY)
                .active(true)
                .build();

        log.info("Creating new party for owner: {} with code: {}", ownerId, party.getPartyCode());
        PartySettingsEntity saved = partySettingsRepository.save(party);
        planService.startTrial(ownerId);   // only while the plans are enforced (V35); in this transaction, as the party
        // a new DJ (V40) — once the party is there: a first login in two tabs makes it in one, the other rolls back
        com.scan2play.util.AfterCommit.run(() -> siteCounter.count(SiteCounter.Event.NEW_DJ));
        return saved;
    }

    /**
     * Updates party settings. The cached settings of the party are dropped once the change is committed, so the next
     * {@link PartySettingsQueryService#getSettings} reads the new state (dropped before the commit, a concurrent read could
     * put the old one back).
     * <p>
     * The row is read under its lock ({@link PartySettingsRepository#lockByPartyCode}) and written whole: two writes at once — the
     * DJ's panel, the staff's, the hosts' page — take turns, and the second reads what the first wrote. Read without the lock, the
     * second wrote every column back as it had read it, and the first one's change was gone (review 2026-10-14 W8,
     * {@code PartySettingsWritesIT}). An {@code updater} that finds the row no longer fits (the hosts' old link) throws, and nothing
     * is written.
     *
     * @param partyCode The unique code of the party to update.
     * @param updater   A {@link Consumer} that applies the desired changes to the entity.
     * @return The updated {@link PartySettingsEntity} (the caller's own; it is not the cached one).
     */
    @Transactional
    public PartySettingsEntity updateSettings(String partyCode, Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = partySettingsRepository.lockByPartyCode(partyCode)
                .orElseThrow(() -> new IllegalArgumentException("Party not found for update: " + partyCode));

        updater.accept(settings);
        PartySettingsEntity saved = partySettingsRepository.save(settings);
        evictAfterCommit(partyCode);
        return saved;
    }

    /**
     * Opens or closes the parties whose planned moment (V55) has come by {@code now} — at most {@code limit} — in one statement
     * ({@link PartySettingsRepository#applyDueSchedule}); their cached settings dropped once it is committed, as after
     * {@link #updateSettings}.
     */
    @Transactional
    public List<ScheduleChange> applyDueSchedule(Instant now, int limit) {
        List<ScheduleChange> changes = partySettingsRepository.applyDueSchedule(now, limit).stream()
                .map(row -> new ScheduleChange((String) row[0], (Boolean) row[1]))
                .toList();
        changes.forEach(change -> evictAfterCommit(change.partyCode()));
        return changes;
    }

    /** A party the plan opened ({@code active}) or closed. */
    public record ScheduleChange(String partyCode, boolean active) {
    }

    private void evictAfterCommit(String partyCode) {
        AfterCommit.run(() -> {
            Cache cache = cacheManager.getCache(PartySettingsQueryService.CACHE);
            if (cache != null) {
                cache.evict(partyCode);
            }
        });
    }
}
