package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

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

    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final QueueService queueService;

    // ---- Queue Queries ----

    /**
     * Computes a lightweight fingerprint of the active queue.
     * Used for ETag-based 304 Not Modified responses — avoids full DB fetch
     * and Thymeleaf rendering when the queue hasn't changed between polls.
     *
     * @param partyCode The unique code of the party.
     * @return A fingerprint string (e.g. "12-487").
     */
    public String getQueueFingerprint(String partyCode) {
        String raw = songRequestRepository.computeFingerprint(partyCode, List.of(DECISION_ACCEPTED));
        return raw != null ? raw : "0-0";
    }

    /**
     * Returns the accepted songs (waiting in queue) for the dashboard.
     * Limited to the 100 most recent entries for performance.
     *
     * @param partyCode The unique code of the party.
     * @return List of accepted song requests (max 100).
     */
    @Cacheable(value = "dashboardQueue", key = "#partyCode")
    public List<SongRequestEntity> getDashboardQueue(String partyCode) {
        return songRequestRepository.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, List.of(DECISION_ACCEPTED)
        );
    }

    /**
     * Returns historical songs (PLAYED and optionally REJECTED).
     * Limited to the 50 most recent entries.
     *
     * @param partyCode The unique code of the party.
     * @return List of played or rejected song requests.
     */
    public List<SongRequestEntity> getHistory(String partyCode) {
        return songRequestRepository.findTop50ByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, Arrays.asList(DECISION_PLAYED, DECISION_REJECTED)
        );
    }

    /**
     * Returns the public queue for guest view (top 5 accepted songs).
     *
     * @param partyCode The unique code of the party.
     * @return List of top 5 accepted song requests.
     */
    @Cacheable(value = "publicQueue", key = "#partyCode")
    public List<SongRequestEntity> getPublicQueue(String partyCode) {
        return songRequestRepository.findTop5ByPartyCodeAndDecisionOrderByRequestedAtDesc(partyCode, DECISION_ACCEPTED);
    }

    // ---- Song Actions ----

    /**
     * Marks a specific song request as "played" in the database.
     *
     * @param id The ID of the song request.
     */
    @Transactional
    public void markSongAsPlayed(Long id) {
        songRequestRepository.findById(id).ifPresent(song -> {
            song.setDecision(DECISION_PLAYED);
            songRequestRepository.save(song);
            log.info("Marked song ID={} as PLAYED for party {}", id, song.getPartyCode());
        });
    }

    /**
     * Pushes a specific song to the Spotify queue manually.
     * Only works if the active provider is Spotify.
     *
     * @param id The ID of the song request.
     */
    @Transactional
    public void pushToSpotify(Long id) {
        songRequestRepository.findById(id).ifPresent(song -> {
            String partyCode = song.getPartyCode();
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

            if (settings.getActiveProvider() == MusicProviderType.SPOTIFY && song.getTrackUrl() != null) {
                queueService.addToQueue(partyCode, song.getTrackUrl(), MusicProviderType.SPOTIFY);
                song.setDecision(DECISION_PLAYED);
                songRequestRepository.save(song);
                log.info("Manually pushed song ID={} to Spotify queue and marked as PLAYED", id);
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

        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String trackUrl = resolveTrackUrl(songName, settings.getActiveProvider());

        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode(partyCode)
                .songName(songName)
                .style(DJ_PICK_STYLE)
                .decision(DECISION_ACCEPTED)
                .djComment(DJ_PICK_COMMENT)
                .energyLevel(0)
                .trackUrl(trackUrl)
                .requestedAt(LocalDateTime.now())
                .build();

        songRequestRepository.save(entity);
        log.info("Party [{}]: DJ pick '{}' saved. Track URL: {}", partyCode, songName, trackUrl);
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
