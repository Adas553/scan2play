package com.scan2play.service;

import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A party's code: a new party's, and a new one for a party ("Zmień kod imprezy", V49) — for a guest who keeps the code and spams every
 * party of the DJ's. The old code stops working (its posters, cards and TV screen: "Ten kod imprezy nie istnieje") and is kept in
 * {@code party_code_retired}, so no party gets it again and a guest with it never lands in another DJ's party.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartyCodeService {

    /** How many random codes are tried before giving up (of 36^5, a few taken: the first one is free but for once in millions). */
    static final int ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsRepository partySettingsRepository;
    private final CacheManager cacheManager;

    /** A code no party has and none had. */
    public String freshCode() {
        return freshCode(CodeGenerator::generatePartyCode);
    }

    String freshCode(Supplier<String> candidates) {
        for (int i = 0; i < ATTEMPTS; i++) {
            String code = candidates.get();
            Boolean taken = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM party_settings WHERE party_code = ?)"
                    + " OR EXISTS (SELECT 1 FROM party_code_retired WHERE party_code = ?)", Boolean.class, code, code);
            if (!Boolean.TRUE.equals(taken)) {
                return code;
            }
        }
        throw new IllegalStateException("No free party code in " + ATTEMPTS + " tries");
    }

    /**
     * Gives the party a new code and retires the old one; everything of the party moves with it — its requests (the history, the
     * queue), feedback, staff, invitations, library and logo (the foreign keys follow, V49). Returns the new code.
     * <p>
     * The locks as "Usuń konto" takes them ({@link AccountDeletionService}): the guests' requests' lock first, then the party's row. A
     * guest's request being saved finishes first and moves with the rest; one the AI was still judging waits, then finds no party
     * under the old code and saves nothing ({@link SongRequestCommandService}). A "Dołącz" waits for the row as for a deletion.
     *
     * @throws IllegalArgumentException when there is no party of that code
     */
    @Transactional
    public String changeCode(String oldCode) {
        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(oldCode));
        partySettingsRepository.lockByPartyCode(oldCode).orElseThrow(() -> new IllegalArgumentException("Party not found: " + oldCode));
        String newCode = freshCode();

        jdbc.update("UPDATE party_settings SET party_code = ? WHERE party_code = ?", newCode, oldCode);   // logo, staff, library follow
        jdbc.update("UPDATE song_requests SET party_code = ? WHERE party_code = ?", newCode, oldCode);
        jdbc.update("UPDATE feedback SET party_code = ? WHERE party_code = ?", newCode, oldCode);
        jdbc.update("INSERT INTO party_code_retired (party_code) VALUES (?)", oldCode);
        log.info("Party code changed: {} -> {}", oldCode, newCode);

        // after the commit, as a deletion: a read while this was open would otherwise put the old party back into the cache
        AfterCommit.run(() -> AccountDeletionService.PARTY_CACHES.stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> {
                    cache.evict(oldCode);
                    cache.evict(newCode);
                }));
        return newCode;
    }
}
