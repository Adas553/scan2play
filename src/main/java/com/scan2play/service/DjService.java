package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.YouTubeUrls;
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
import java.util.Optional;
import java.util.Set;

/**
 * Service responsible for song queue management and direct song actions.
 * <p>
 * Key responsibilities:
 * <ul>
 *     <li>Queue queries (Dashboard, History, Public)</li>
 *     <li>Song status changes (mark as played, push to Spotify)</li>
 *     <li>DJ manual picks (bypass AI)</li>
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

    /** Default style label for manually added DJ picks. */
    private static final String DJ_PICK_STYLE = "DJ Pick";

    /** Default comment attached to manually added DJ picks. */
    private static final String DJ_PICK_COMMENT = "DJ's Choice 🎧";
    /** The note of a request the DJ skipped (dismissSong), in place of the AI's comment. */
    static final String DJ_DISMISS_COMMENT = "Skipped by the DJ ⏭";
    static final String DJ_CLEAR_COMMENT = "Cleared by the DJ 🧹";

    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final QueueService queueService;
    private final SongEvaluationService songEvaluationService;
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
     * Limited to the 100 oldest pending entries for performance.
     *
     * @param partyCode The unique code of the party.
     * @return List of accepted song requests (max 100), oldest first.
     */
    @Cacheable(value = "dashboardQueue", key = "#partyCode")
    public List<SongRequestEntity> getDashboardQueue(String partyCode) {
        return songRequestRepository.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(
                partyCode, List.of(DECISION_ACCEPTED)
        );
    }

    /**
     * Finds the oldest accepted, not-yet-played guest song that has a playable YouTube
     * video ID — the server-side replacement for the YouTube Auto-Pilot's old client-side
     * DOM scan of the queue table (see PROJECT_CONTEXT.md Section 14).
     * <p>
     * Does <b>not</b> mark anything as played — the client still confirms that via the
     * existing {@link #markSongAsPlayed} once the video actually starts. That keeps this
     * method a safe, repeatable read: {@link NextTrackService} asks it whenever the player is about
     * to load a track, and it reuses {@link #getDashboardQueue} (already {@code @Cacheable}, 3s TTL)
     * so most calls are a cache hit rather than a fresh query.
     *
     * @param partyCode The unique code of the party.
     * @param excludeIds Song IDs to skip even though they're accepted+resolvable — the
     *                   client adds one here when {@code loadVideoById} itself errors on
     *                   it (video removed/private/region-blocked), so Auto-Pilot doesn't
     *                   retry the same broken video forever. Session-only on the client,
     *                   not persisted: a page reload will offer it again.
     * @return The next playable guest track, if one is waiting.
     */
    public Optional<NextGuestTrackResponse> findNextPlayableGuestTrack(String partyCode, Set<Long> excludeIds) {
        return getDashboardQueue(partyCode).stream()
                .filter(song -> !excludeIds.contains(song.getId()))
                // A song's trackUrl can also be a YouTube *search-results* URL (no video ID) when the Data API had no
                // key or failed to resolve it (PROJECT_CONTEXT.md Section 7.3): the player cannot play those, skip them.
                .flatMap(song -> YouTubeUrls.extractVideoId(song.getTrackUrl())
                        .map(videoId -> new NextGuestTrackResponse(song.getId(), videoId))
                        .stream())
                .findFirst();
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
     * the DJ's note instead of the AI's comment, and shows in the history's rejected requests. Only a waiting (accepted) request;
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
            song.setDjComment(DJ_DISMISS_COMMENT);
            songRequestRepository.save(song);
            evictDashboardQueueAfterCommit(song.getPartyCode());
            log.info("Song ID={} skipped by the DJ of party {}", id, song.getPartyCode());
        });
    }

    /**
     * The DJ clears the queue ("Wyczyść kolejkę"): every waiting request leaves it as rejected, as if skipped one by one, and shows
     * in the history's rejected requests. Songs that played stay played; the one playing now has been confirmed played already.
     *
     * @param ownerPartyCode the partyCode of the authenticated DJ (from the session) — only their own queue
     * @return number of requests taken out of the queue
     */
    @Transactional
    public int clearQueue(String ownerPartyCode) {
        int cleared = songRequestRepository.rejectWaiting(ownerPartyCode, DJ_CLEAR_COMMENT);
        evictDashboardQueueAfterCommit(ownerPartyCode);
        log.info("Party [{}]: the DJ cleared the queue — {} waiting request(s) rejected", ownerPartyCode, cleared);
        return cleared;
    }

    /**
     * A song that has played leaves the queue at once, not when the 3 s cache of {@link #getDashboardQueue} expires: next-track
     * reads that cache, and a ⏭ in those seconds handed the song that had just started out again. After the commit, not
     * before: a request that read the queue while this transaction was still open would put the old list back. Without a
     * transaction the cache is evicted at once.
     */
    private void evictDashboardQueueAfterCommit(String partyCode) {
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache("dashboardQueue");
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

    /**
     * Pushes a specific song to the Spotify queue manually.
     * Only works if the active provider is Spotify.
     * Validates that the song belongs to the given party (IDOR protection).
     * <p>
     * The song is marked as played once Spotify has taken it ({@link QueueService#addToQueue} runs asynchronously); when that
     * fails it stays in the queue, so the DJ sees it is still waiting and can press again.
     *
     * @param id             The ID of the song request.
     * @param ownerPartyCode The partyCode of the authenticated DJ (from session).
     */
    public void pushToSpotify(Long id, String ownerPartyCode) {
        songRequestRepository.findById(id).ifPresent(song -> {
            if (!song.getPartyCode().equals(ownerPartyCode)) {
                log.warn("IDOR blocked: DJ party {} tried to push song {} to Spotify (belongs to party {})",
                        ownerPartyCode, id, song.getPartyCode());
                return;
            }
            String partyCode = song.getPartyCode();
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

            if (settings.getActiveProvider() == MusicProviderType.SPOTIFY && song.getTrackUrl() != null) {
                queueService.addToQueue(partyCode, song.getTrackUrl(), MusicProviderType.SPOTIFY)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                log.warn("Could not push song ID={} to the Spotify queue — it stays in the queue", id, error);
                                return;
                            }
                            markPlayed(song, Instant.now());
                            songRequestRepository.save(song);
                            evictDashboardQueueAfterCommit(partyCode);
                            log.info("Manually pushed song ID={} to Spotify queue and marked as PLAYED", id);
                        });
            } else {
                log.warn("Cannot push to Spotify: Provider is {} or track URL is missing", settings.getActiveProvider());
            }
        });
    }

    /**
     * Adds a song directly to the party queue as a DJ Pick, bypassing AI evaluation.
     * The song is saved immediately as ACCEPTED so it appears in the next polling cycle
     * and the YouTube Auto-Pilot can pick it up.
     *
     * @param partyCode The unique code of the party.
     * @param songName  The name of the song to add (must not be blank).
     */
    @Transactional
    public void addDjPick(String partyCode, String songName) {
        log.info("Party [{}]: DJ manually adding track: '{}'", partyCode, songName);

        // Normalize raw input via AI to canonical "ARTIST - TITLE" format.
        // This ensures consistent YouTube cache keys (e.g. "nirvanna smells" → "Nirvana - Smells Like Teen Spirit").
        String normalizedName = songEvaluationService.normalizeSongName(songName);

        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String trackUrl = resolveTrackUrl(normalizedName, settings.getActiveProvider());
        // The queue shows what will play: the video's own title when there is one
        String playedName = songEvaluationService.nameOfTrack(normalizedName, trackUrl, settings.getActiveProvider());

        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode(partyCode)
                .songName(playedName)
                .style(DJ_PICK_STYLE)
                .decision(DECISION_ACCEPTED)
                .djComment(DJ_PICK_COMMENT)
                .energyLevel(0)
                .trackUrl(trackUrl)
                .requestedAt(Instant.now())
                .build();

        songRequestRepository.save(entity);
        log.info("Party [{}]: DJ pick '{}' → '{}' saved. Track URL: {}", partyCode, songName, normalizedName, trackUrl);
    }

    private String resolveTrackUrl(String songName, MusicProviderType provider) {
        try {
            log.debug("Resolving track '{}' using provider: {}", songName, provider);
            return queueService.resolveTrack(songName, provider);
        } catch (Exception e) {
            log.warn("Failed to resolve track URL for '{}'", songName, e);
            return null;
        }
    }
}
