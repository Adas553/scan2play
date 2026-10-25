package com.scan2play.service;

import com.scan2play.util.AfterCommit;
import com.scan2play.entity.SongRequestDedicationEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestDedicationRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.model.LibraryMark;
import com.scan2play.util.LibraryKey;
import com.scan2play.util.SongNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final SongRequestDedicationRepository dedicationRepository;
    private final CacheManager cacheManager;
    private final DjLibraryService libraryService;

    // ---- Queue Queries ----

    /**
     * Computes a lightweight fingerprint of the active queue.
     * Used for ETag-based 304 Not Modified responses — avoids full DB fetch
     * and Thymeleaf rendering when the queue hasn't changed between polls.
     *
     * @param partyCode The unique code of the party.
     * @return A fingerprint string (count, max id, a hash of the rows — see the repository).
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

    /**
     * The party's waiting requests, read now — not through the 3 s cache of {@link #getDashboardQueue}. The DJ's poll renders
     * from it when the queue's fingerprint changed (review 2026-10-14 K1): the cache, filled by a guest's page a moment before a
     * new request committed, gave the old list under the new ETag, and every later poll was a 304 over it. Read after the
     * fingerprint, so the list is never older than the ETag it is sent with.
     */
    public List<SongRequestEntity> getWaiting(String partyCode) {
        return songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(partyCode, List.of(DECISION_ACCEPTED));
    }

    /** How many of a song's dedications the queue shows: the newest; "+N" says how many more there are. */
    static final int DEDICATIONS_SHOWN = 3;

    /**
     * The newest {@value #DEDICATIONS_SHOWN} dedications of each of the songs (V38), by the song's id, the oldest of them first. No
     * query when none of the songs has one ({@code dedications} on the row) — the queue of most parties.
     */
    public Map<Long, List<SongRequestDedicationEntity>> dedicationsOf(List<SongRequestEntity> songs) {
        List<Long> ids = songs.stream().filter(song -> song.getDedications() > 0).map(SongRequestEntity::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dedicationRepository.findNewest(ids, DEDICATIONS_SHOWN).stream()
                .collect(Collectors.groupingBy(SongRequestDedicationEntity::getSongRequestId, LinkedHashMap::new, Collectors.toList()));
    }

    /** The party's library (V41) as the settings show it, and its version for the panel's ETag; empty when it has none. */
    public Optional<DjLibraryService.Info> libraryOf(String partyCode) {
        return libraryService.info(partyCode);
    }

    /**
     * For each of the songs, by its id: "✓", "?" or "✗" by the party's library (V41, V42; {@link LibraryKey#markIn}). One lookup
     * for all of them (the queue is at most its 300); none, and no marks, when the party has no library.
     */
    public Map<Long, LibraryMark> libraryMarksOf(String partyCode, List<SongRequestEntity> songs) {
        if (songs.isEmpty() || libraryService.info(partyCode).isEmpty()) {
            return Map.of();
        }
        Map<Long, LibraryKey> keys = new LinkedHashMap<>();
        songs.forEach(song -> keys.put(song.getId(), LibraryKey.of(song.getSongName())));
        Map<LibraryKey, LibraryMark> found = libraryService.marks(partyCode, new HashSet<>(keys.values()));
        Map<Long, LibraryMark> marks = new LinkedHashMap<>();
        keys.forEach((id, key) -> marks.put(id, found.getOrDefault(key, LibraryMark.MISSING)));
        return marks;
    }

    // ---- Song Actions ----

    /**
     * Marks a waiting song request as "played" — the moment it happened recorded, kept when it is set already, so a second
     * confirmation does not move a song in the history. One conditional {@code UPDATE} ({@link SongRequestRepository#markPlayed}):
     * only the given party's request (IDOR protection), and only one that waits or has played — a window with an old list cannot
     * play a request skipped, cleared or rejected meanwhile.
     *
     * @param id             The ID of the song request.
     * @param ownerPartyCode The partyCode of the authenticated DJ (from session).
     */
    @Transactional
    public void markSongAsPlayed(Long id, String ownerPartyCode) {
        if (songRequestRepository.markPlayed(id, ownerPartyCode, Instant.now()) > 0) {
            evictDashboardQueueAfterCommit(ownerPartyCode);
            log.info("Marked song ID={} as PLAYED for party {}", id, ownerPartyCode);
        } else {
            log.warn("Not marked played: DJ party {} asked for song {} (not theirs, gone, or no longer waiting)", ownerPartyCode, id);
        }
    }

    /**
     * The DJ skips a waiting request (a song they do not have, or do not want to play now): it leaves the queue as rejected, with
     * {@code skippedAt} set and the AI's comment kept, and shows in the history's rejected requests as skipped by the DJ. Only a waiting (accepted) request;
     * one that played stays played. One conditional {@code UPDATE} ({@link SongRequestRepository#skip}), as "Zagrane": only the given
     * party's request (IDOR protection), only while it waits — read and saved, a "Zagrane" in another window that committed in between
     * was turned into a skip (review 2026-10-14, {@code SongRequestVotesIT.pominRightAfterZagrane_leavesTheSongPlayed}).
     *
     * @param id             The ID of the song request.
     * @param ownerPartyCode The partyCode of the authenticated DJ (from session).
     */
    @Transactional
    public void dismissSong(Long id, String ownerPartyCode) {
        // the AI's comment stays: a restore gives the request back with it
        if (songRequestRepository.skip(id, ownerPartyCode, Instant.now()) > 0) {
            evictDashboardQueueAfterCommit(ownerPartyCode);
            log.info("Song ID={} skipped by the DJ of party {}", id, ownerPartyCode);
        } else {
            log.warn("Not skipped: DJ party {} asked for song {} (not theirs, gone, or no longer waiting)", ownerPartyCode, id);
        }
    }

    /**
     * Undoes a skip or a "Zagrane" ("Cofnij" right after it, "↩ Przywróć" in the history — at any time, the owner 2026-10-10): the
     * request waits in the queue again, in its old place (by its request time), with its votes, the guest's words and its tips. A skip
     * no longer keeps the song out of the queue; a "Zagrane" by mistake no longer counts as played — the AI's duplicate rule ("już
     * leciała") reads only the played ones, so the guests may ask for it again. Only a request skipped by the DJ or played — not one
     * the AI rejected, not one cleared with the queue — and only the DJ's own.
     * <p>
     * Under the party's lock of the guests' requests ({@code SongRequestCommandService}): when the same song waits in the queue
     * already (asked for again meanwhile), nothing changes — the song is in the queue.
     *
     * @return whether the request went back to the queue
     */
    @Transactional
    public boolean restoreSong(Long id, String ownerPartyCode) {
        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(ownerPartyCode));
        SongRequestEntity song = songRequestRepository.findById(id).orElse(null);
        if (song == null || !song.getPartyCode().equals(ownerPartyCode)) {
            log.warn("Restore refused: DJ party {} asked for song {} (not theirs, or gone)", ownerPartyCode, id);
            return false;
        }
        boolean skipped = DECISION_REJECTED.equals(song.getDecision()) && song.getSkippedAt() != null;
        if (!skipped && !DECISION_PLAYED.equals(song.getDecision())) {
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
        song.setPlayedAt(null);    // …or the "Zagrane"
        songRequestRepository.save(song);
        evictDashboardQueueAfterCommit(ownerPartyCode);
        log.info("Song ID={} ({}) restored to the queue by the DJ of party {}", id, skipped ? "skipped" : "played", ownerPartyCode);
        return true;
    }

    /**
     * The DJ counts a tip for a song ("💸", V28: a payment titled with the song's number came in) or takes one back ({@code add}
     * false) — one atomic {@code UPDATE}, only the DJ's own song with a number, never below 0. The queue's cache goes, so every
     * window of the DJ shows the new count with its next poll.
     *
     * @return whether the count changed
     */
    @Transactional
    public boolean countTip(Long id, String ownerPartyCode, boolean add) {
        int changed = add ? songRequestRepository.addTip(id, ownerPartyCode) : songRequestRepository.removeTip(id, ownerPartyCode);
        if (changed > 0) {
            evictDashboardQueueAfterCommit(ownerPartyCode);
            log.info("Party [{}]: the DJ {} a tip for song ID={}", ownerPartyCode, add ? "counted" : "took back", id);
        } else {
            log.warn("Tip not counted: DJ party {} asked for song {} (not theirs, no number, or no tip to take back)",
                    ownerPartyCode, id);
        }
        return changed > 0;
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
     * Under the party's lock of the guests' requests, as {@link #restoreSong}: a skip put back at the same moment is either
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
        // the songs' numbers start again from the highest one left — #1 with the queue empty (the owner, 2026-10-08)
        songRequestRepository.resetRequestCounter(ownerPartyCode);
        log.info("Party [{}]: the DJ cleared the history — {} request(s) deleted", ownerPartyCode, deleted);
        return deleted;
    }

    /**
     * The party's waiting requests changed outside this class (a guest's request or 👍): read them again, not from the 3 s cache —
     * after the commit, when there is a transaction.
     */
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
        AfterCommit.run(() -> {
            Cache cache = cacheManager.getCache(QUEUE_CACHE);
            if (cache != null) cache.evict(partyCode);
        });
    }
}
