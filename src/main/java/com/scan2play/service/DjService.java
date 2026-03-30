package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.SongRequestRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service responsible for managing song requests, AI evaluation, and playback settings.
 * <p>
 * Key responsibilities:
 * <ul>
 *     <li>AI evaluation of song requests</li>
 *     <li>Queue management (Active/History)</li>
 *     <li>Integration with music providers via QueueService</li>
 *     <li>Party configuration updates</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DjService {

    public static final String DECISION_ACCEPTED = "accepted";
    public static final String DECISION_REJECTED = "rejected";
    public static final String DECISION_PLAYED = "played";

    /** Cached config for AI requests – always the same, no need to rebuild per call. */
    private static final GenerateContentConfig AI_JSON_CONFIG = GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .build();

    @Value("${google.ai.model-name}")
    private String modelName;

    @Value("classpath:prompt-template.txt")
    private Resource promptResource;

    @Value("classpath:prompt-duplicate-rule.txt")
    private Resource duplicateRuleResource;

    private String cachedPromptTemplate;
    private String cachedDuplicateRuleTemplate;

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final QueueService queueService;
    private final MessageSource messageSource;

    /**
     * Initializes the service by loading the AI prompt template from resources.
     * This avoids File I/O during request processing.
     */
    @PostConstruct
    public void init() {
        try {
            this.cachedPromptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
            this.cachedDuplicateRuleTemplate = duplicateRuleResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template", e);
            throw new RuntimeException("System configuration error: prompt template missing", e);
        }
    }

    /**
     * Core business method:
     * <ol>
     *     <li>Asks Gemini AI to evaluate if the song fits the requested style/vibe (Outside Transaction).</li>
     *     <li>Resolves track URL if accepted.</li>
     *     <li>Saves the evaluation result to the database (Transactional).</li>
     *     <li>Optionally adds the song to the playback queue if Auto-Pilot mode is active.</li>
     * </ol>
     *
     * @param partyCode The unique code of the party.
     * @param songName  Title of the song (usually from QR scan or manual input).
     * @param style     Desired style / mood selected by the user.
     * @return Complete AI response (decision + comment + energy level).
     */
    public DjResponse evaluateAndSaveSong(String partyCode, String songName, String style) {
        log.info("Party [{}]: Evaluating song: '{}' with style: '{}'", partyCode, songName, style);

        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String recentSongs = getRecentSongsContext(partyCode, settings.getDuplicateCheckWindow());

        // Fetch i18n error messages in the main thread (where Locale is available)
        String autopilotErrorMsg = messageSource.getMessage("dashboard.error.autopilot_failed", null, LocaleContextHolder.getLocale());
        String aiOfflineMsg = messageSource.getMessage("ai.error.offline", null, LocaleContextHolder.getLocale());

        // 1. External API Call: AI Evaluation
        DjResponse aiResponse = evaluateWithAi(songName, style, recentSongs, aiOfflineMsg);

        // 2. External API Call: Spotify/YouTube Track Resolution (if accepted)
        String trackUrl = null;
        if (DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())) {
            trackUrl = resolveTrackUrl(aiResponse.songName(), settings.getActiveProvider());
        }

        // 3. Database Operations: Safe, quick transaction
        SongRequestEntity savedRequest = saveSongRequest(partyCode, aiResponse, style, trackUrl);

        // 4. External API Call: Add to Queue (If Accepted & Auto-Pilot is enabled)
        handleAutoQueue(settings, savedRequest, trackUrl, autopilotErrorMsg);

        return aiResponse;
    }

    private String getRecentSongsContext(String partyCode, int duplicateCheckWindow) {
        if (duplicateCheckWindow <= 0) {
            return null; // Return null when feature is disabled
        }

        List<SongRequestEntity> recentRequests = songRequestRepository.findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, List.of(DECISION_ACCEPTED, DECISION_PLAYED), PageRequest.of(0, duplicateCheckWindow)
        );

        String recentSongs = recentRequests.stream()
                .map(SongRequestEntity::getSongName)
                .collect(Collectors.joining(", "));

        return recentSongs.isEmpty() ? "None" : recentSongs;
    }

    private void handleAutoQueue(PartySettingsEntity settings, SongRequestEntity savedRequest, String trackUrl, String autopilotErrorMsg) {
        if (trackUrl == null || !DECISION_ACCEPTED.equalsIgnoreCase(savedRequest.getDecision()) || settings.getPlaybackMode() != PlaybackMode.AUTO) {
            return;
        }

        queueService.addToQueue(settings.getPartyCode(), trackUrl, settings.getActiveProvider())
                .exceptionally(ex -> {
                    log.error("Failed to Auto-Queue track {} for party {}. Updating song status to indicate failure.", trackUrl, settings.getPartyCode(), ex);
                    songRequestRepository.findById(savedRequest.getId()).ifPresent(song -> {
                        song.setDjComment(song.getDjComment() + " " + autopilotErrorMsg);
                        songRequestRepository.save(song);
                    });
                    return null;
                })
                .thenAccept(v -> songRequestRepository.findById(savedRequest.getId()).ifPresent(song -> {
                    song.setDecision(DECISION_PLAYED);
                    songRequestRepository.save(song);
                }));
    }

    private DjResponse evaluateWithAi(String songName, String style, String recentSongs, String aiOfflineMsg) {
        try {
            String duplicateRule = (recentSongs != null) ? String.format(cachedDuplicateRuleTemplate, recentSongs) : "";
            String prompt = String.format(cachedPromptTemplate, songName, style, duplicateRule);

            GenerateContentResponse response = client.models.generateContent(modelName, prompt, AI_JSON_CONFIG);
            return objectMapper.readValue(response.text(), DjResponse.class);
        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            return new DjResponse(DECISION_REJECTED, aiOfflineMsg, songName, 0);
        }
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

    private SongRequestEntity saveSongRequest(String partyCode, DjResponse aiResponse, String style, String trackUrl) {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode(partyCode)
                .songName(aiResponse.songName())
                .style(style)
                .decision(aiResponse.decision())
                .djComment(aiResponse.comment())
                .energyLevel(aiResponse.energyLevel())
                .trackUrl(trackUrl)
                .requestedAt(LocalDateTime.now())
                .build();
        return songRequestRepository.save(entity);
    }

    /**
     * Returns ONLY the accepted songs (waiting in queue) for the dashboard.
     *
     * @param partyCode The unique code of the party.
     * @return List of accepted song requests.
     */
    public List<SongRequestEntity> getDashboardQueue(String partyCode) {
        return songRequestRepository.findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(
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
        // Limit to 50 most recent PLAYED/REJECTED requests for performance
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
    public List<SongRequestEntity> getPublicQueue(String partyCode) {
        return songRequestRepository.findTop5ByPartyCodeAndDecisionOrderByRequestedAtDesc(partyCode, DECISION_ACCEPTED);
    }

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
     * Updates the global vibe (theme) for the party.
     *
     * @param partyCode The unique code of the party.
     * @param newVibe   The new vibe to set.
     */
    public void setCurrentGlobalVibe(String partyCode, VibeType newVibe) {
        partySettingsCommandService.updateSettings(partyCode, settings -> settings.setGlobalVibe(newVibe));
        log.info("Party [{}]: Global vibe updated to: {}", partyCode, newVibe);
    }

    /**
     * Updates the active music provider (e.g., Spotify, YouTube).
     *
     * @param partyCode   The unique code of the party.
     * @param newProvider The new music provider.
     */
    public void setActiveProvider(String partyCode, MusicProviderType newProvider) {
        partySettingsCommandService.updateSettings(partyCode, settings -> settings.setActiveProvider(newProvider));
        log.info("Party [{}]: Music provider updated to: {}", partyCode, newProvider);
    }

    /**
     * Sets the playback mode (AUTO or MANUAL).
     *
     * @param partyCode The unique code of the party.
     * @param mode      The new playback mode.
     */
    public void setPlaybackMode(String partyCode, PlaybackMode mode) {
        partySettingsCommandService.updateSettings(partyCode, settings -> settings.setPlaybackMode(mode));
        log.info("Party [{}]: Playback mode updated to: {}", partyCode, mode);
    }

    /**
     * Updates the rate limiting and duplicate check parameters for the party.
     *
     * @param partyCode             The unique code of the party.
     * @param requestLimit          Maximum number of requests.
     * @param cooldownMinutes       Window size in minutes.
     * @param duplicateCheckWindow  Number of recent songs to check.
     */
    public void setPartyLimits(String partyCode, int requestLimit, int cooldownMinutes, int duplicateCheckWindow) {
        partySettingsCommandService.updateSettings(partyCode, settings -> {
            settings.setRequestLimit(requestLimit);
            settings.setCooldownMinutes(cooldownMinutes);
            settings.setDuplicateCheckWindow(duplicateCheckWindow);
        });
        log.info("Party [{}]: Limits updated to: {} requests per {} minutes, {} duplicate window", 
                partyCode, requestLimit, cooldownMinutes, duplicateCheckWindow);
    }

    /**
     * Toggles the playback mode for the party.
     * <p>
     * Logic:
     * <ul>
     *     <li>If provider is NOT Spotify, force MANUAL mode.</li>
     *     <li>If provider IS Spotify, toggle between AUTO and MANUAL.</li>
     * </ul>
     *
     * @param partyCode The party code.
     */
    public void togglePlaybackMode(String partyCode) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

        if (settings.getActiveProvider() != MusicProviderType.SPOTIFY) {
            setPlaybackMode(partyCode, PlaybackMode.MANUAL);
            return;
        }

        PlaybackMode newMode = (settings.getPlaybackMode() == PlaybackMode.AUTO)
                ? PlaybackMode.MANUAL
                : PlaybackMode.AUTO;
        setPlaybackMode(partyCode, newMode);
    }
}
