package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.SongNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * Service responsible for song queue management and direct song actions.
 * <p>
 * Key responsibilities:
 * <ul>
 *     <li>Queue queries (Dashboard, History, Public)</li>
 *     <li>Song status changes (mark as played, skip, clear)</li>
 * </ul>
 * <p>
 * AI evaluation is handled by {@link SongEvaluationService}.
 * Party settings are managed by {@link PartySettingsCommandService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DjService {

    public static final String DECISION_ACCEPTED = "accepted";
    public static final String DECISION_REJECTED = "rejected";
    public static final String DECISION_PLAYED = "played";

    /** The cache of a party's waiting requests, by party code (AppConfig: 3 s); evicted after a song leaves the queue. */
    public static final String QUEUE_CACHE = "dashboardQueue";

    private final SongRequestRepository songRequestRepository;
    private final CacheManager cacheManager;

    // ---- Queue Queries ----

    /**
     * Computes a lightweight fingerprint of the active queue.
     * Used for ETag-based 304 Not Modified responses — avoids full DB fetch
     * and Thymeleaf rendering when the queue hasn't changed between polls.
     *
     * @param partyCode The unique code of the party.
     * @return A fingerprint string (e.g. "12-487-15": count, max id, votes).
     */
    public String getQueueFingerprint(String partyCode) {
        String raw = songRequestRepository.computeFingerprint(partyCode, List.of(DECISION_ACCEPTED));
        return raw != null ? raw : "0-0-0";
    }

    /**
     * Returns the accepted songs (waiting in queue) for the dashboard in FIFO order.
     * Oldest request is at the top — DJ sees what will be played next immediately.
     * Limited to the 300 oldest pending entries (a day's requests of a party, see the repository).
     *
     * @param partyCode The unique code of the party.
     * @return List of accepted song requests (max 300), oldest first.
     */
    @Cacheable(value = QUEUE_CACHE, key = "#partyCode")
    public List<SongRequestEntity> getDashboardQueue(String partyCode) {
        return songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(
                partyCode, List.of(DECISION_ACCEPTED)
        );
    }

    // ---- Song Actions ----

    /**
     * The one place a request becomes "played": the decision, and the moment it happened (kept when it is already
     * set, so a second confirmation does not move a song in the history).
     */
    static void markPlayed(SongRequestEntity song, Instant now) {
        song.setDecision(DECISION_PLAYED);
        if (song.getPlayedAt() == null) {
            song.setPlayedAt(now);
        }
    }

    /**
     * Marks a specific song request as "played" in the database.
     * Validates that the song belongs to the given party (IDOR protection).
     *
     * @param id             The ID of the song request.
     * @param ownerPartyCode The partyCode of the authenticated DJ (from session).
     */
    @Transactional
    public void markSongAsPlayed(Long id, String ownerPartyCode) {
        songRequestRepository.findById(id).ifPresent(song -> {
            if (!song.getPartyCode().equals(ownerPartyCode)) {
                log.warn("IDOR blocked: DJ party {} tried to mark song {} (belongs to party {})",
                        ownerPartyCode, id, song.getPartyCode());
                return;
            }
            markPlayed(song, Instant.now());
            songRequestRepository.save(song);
            evictDashboardQueueAfterCommit(song.getPartyCode());
            log.info("Marked song ID={} as PLAYED for party {}", id, song.getPartyCode());
        });
    }

    /**
     * The DJ skips a waiting request (a song they do not have, or do not want to play now): it leaves the queue as rejected, with
     * {@code skippedAt} set and the AI's comment kept, and shows in the history's rejected requests as skipped by the DJ. Only a waiting (accepted) request;
     * one that played stays played. Validates that the song belongs to the given party (IDOR protection).
     *
     * @param id             The ID of the song request.
     * @param ownerPartyCode The partyCode of the authenticated DJ (from session).
     */
    @Transactional
    public void dismissSong(Long id, String ownerPartyCode) {
        songRequestRepository.findById(id).ifPresent(song -> {
            if (!song.getPartyCode().equals(ownerPartyCode)) {
                log.warn("IDOR blocked: DJ party {} tried to skip song {} (belongs to party {})",
                        ownerPartyCode, id, song.getPartyCode());
                return;
            }
            if (!DECISION_ACCEPTED.equals(song.getDecision())) {
                return;
            }
            song.setDecision(DECISION_REJECTED);
            song.setSkippedAt(Instant.now());   // the AI's comment stays: a restore gives the request back with it
            songRequestRepository.save(song);
            evictDashboardQueueAfterCommit(song.getPartyCode());
            log.info("Song ID={} skipped by the DJ of party {}", id, song.getPartyCode());
        });
    }

    /**
     * Undoes a skip ("Cofnij" right after it, "↩ Przywróć" in the history): the request the DJ skipped waits in the queue again, in
     * its old place (by its request time), with its votes and the guest's words, and the song is no longer kept out of the queue.
     * Only a request skipped by the DJ — not one the AI rejected, not one cleared with the queue — and only the DJ's own.
     * <p>
     * Under the party's lock of the guests' requests ({@code SongRequestCommandService}): when the same song waits in the queue
     * already (asked for again once the skip was no longer remembered), nothing changes — the song is in the queue.
     *
     * @return whether the request went back to the queue
     */
    @Transactional
    public boolean restoreSkippedSong(Long id, String ownerPartyCode) {
        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(ownerPartyCode));
        SongRequestEntity song = songRequestRepository.findById(id).orElse(null);
        if (song == null || !song.getPartyCode().equals(ownerPartyCode)) {
            log.warn("Restore refused: DJ party {} asked for song {} (not theirs, or gone)", ownerPartyCode, id);
            return false;
        }
        if (!DECISION_REJECTED.equals(song.getDecision()) || song.getSkippedAt() == null) {
            return false;
        }
        boolean alreadyWaiting = getWaiting(ownerPartyCode).stream()
                .anyMatch(waiting -> SongNames.same(waiting.getSongName(), song.getSongName()));
        if (alreadyWaiting) {
            log.info("Party [{}]: '{}' not restored — the same song waits already", ownerPartyCode, song.getSongName());
            return false;
        }
        song.setDecision(DECISION_ACCEPTED);
        song.setSkippedAt(null);   // with the AI's comment, as before the skip
        songRequestRepository.save(song);
        evictDashboardQueueAfterCommit(ownerPartyCode);
        log.info("Song ID={} restored to the queue by the DJ of party {}", id, ownerPartyCode);
        return true;
    }

    /** The party's waiting requests, read now (not through the 3 s cache of {@link #getDashboardQueue}). */
    private List<SongRequestEntity> getWaiting(String partyCode) {
        return songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(partyCode, List.of(DECISION_ACCEPTED));
    }

    /**
     * The DJ clears the queue ("Wyczyść kolejkę"): every waiting request leaves it as rejected, with {@code clearedAt} set and the
     * AI's comment kept, and shows in the history's rejected requests as cleared by the DJ — in the page's language (V25; the fixed
     * English note "Cleared by the DJ 🧹" it wrote over the AI's comment before is gone, as the skips' notes went with V23). Unlike a
     * skip, the song may be asked for again at once. Songs that played stay played; the one playing now has been confirmed played already.
     *
     * @param ownerPartyCode the partyCode of the authenticated DJ (from the session) — only their own queue
     * @return number of requests taken out of the queue
     */
    @Transactional
    public int clearQueue(String ownerPartyCode) {
        int cleared = songRequestRepository.rejectWaiting(ownerPartyCode, Instant.now());
        evictDashboardQueueAfterCommit(ownerPartyCode);
        log.info("Party [{}]: the DJ cleared the queue — {} waiting request(s) rejected", ownerPartyCode, cleared);
        return cleared;
    }

    /**
     * The DJ clears the history ("Wyczyść historię" — a weekend's guests' words should not stay on the list): the party's requests
     * that played or were rejected are deleted. The waiting ones stay, and so do the ones the DJ skipped within
     * {@link SongRequestCommandService#SKIP_REMEMBERED} — deleting them would let the song back into the queue. The AI's duplicate
     * rule reads the songs that played, so after this it no longer knows what played before.
     * <p>
     * Under the party's lock of the guests' requests, as {@link #restoreSkippedSong}: a skip put back at the same moment is either
     * back in the queue or deleted, never half of each.
     *
     * @param ownerPartyCode the partyCode of the authenticated DJ (from the session) — only their own history
     * @return number of deleted requests
     */
    @Transactional
    public int clearHistory(String ownerPartyCode) {
        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(ownerPartyCode));
        int deleted = songRequestRepository.deleteHistory(ownerPartyCode,
                Instant.now().minus(SongRequestCommandService.SKIP_REMEMBERED));
        log.info("Party [{}]: the DJ cleared the history — {} request(s) deleted", ownerPartyCode, deleted);
        return deleted;
    }

    /** The party's waiting requests changed outside this class (a guest's 👍): read them again, not from the 3 s cache. */
    public void refreshQueue(String partyCode) {
        evictDashboardQueueAfterCommit(partyCode);
    }

    /**
     * A song that has played (or was skipped) leaves the queue at once, not when the 3 s cache of {@link #getDashboardQueue}
     * expires: the dashboard asks for the queue right after the click. After the commit, not
     * before: a request that read the queue while this transaction was still open would put the old list back. Without a
     * transaction the cache is evicted at once.
     */
    private void evictDashboardQueueAfterCommit(String partyCode) {
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache(QUEUE_CACHE);
            if (cache != null) cache.evict(partyCode);
        };
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
