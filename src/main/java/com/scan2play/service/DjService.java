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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DjService {

    public static final String DECISION_ACCEPTED = "accepted";
    public static final String DECISION_REJECTED = "rejected";
    public static final String DECISION_PLAYED = "played";

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsService partySettingsService;
    private final QueueService queueService;

    @Value("${google.ai.model-name}")
    private String modelName;

    @Value("classpath:prompt-template.txt")
    private Resource promptResource;

    /**
     * Core business method:
     * 1. Asks Gemini AI to evaluate if the song fits the requested style/vibe
     * 2. Resolves track URL if accepted
     * 3. Saves the evaluation result to the database
     * 4. Optionally adds the song to the playback queue if Auto-Pilot mode is active.
     *
     * @param songName title of the song (usually from QR scan or manual input)
     * @param style    desired style / mood selected by the user
     * @return complete AI response (decision + comment + energy level)
     */
    @Transactional
    public DjResponse evaluateAndSaveSong(String partyCode, String songName, String style) {
        log.info("Party [{}]: Evaluating song: '{}' with style: '{}'", partyCode, songName, style);

        PartySettingsEntity settings = partySettingsService.getSettings(partyCode);

        DjResponse aiResponse = evaluateWithAi(songName, style);

        String trackUrl = null;
        if (DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())) {
            trackUrl = resolveTrackUrl(aiResponse.songName(), settings.getActiveProvider());
        }

        saveSongRequest(partyCode, aiResponse, style, trackUrl);

        if (trackUrl != null && DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())) {
            if (settings.getPlaybackMode() == PlaybackMode.AUTO) {
                queueService.addToQueue(partyCode, trackUrl, settings.getActiveProvider());
            }
        }

        return aiResponse;
    }

    private DjResponse evaluateWithAi(String songName, String style) {
        try {
            String prompt = String.format(getPromptTemplate(), songName, style);
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .build();

            GenerateContentResponse response = client.models.generateContent(modelName, prompt, config);
            return objectMapper.readValue(response.text(), DjResponse.class);
        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            return new DjResponse(DECISION_REJECTED, "AI is currently offline. Please try again.", songName, 0);
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

    private void saveSongRequest(String partyCode, DjResponse aiResponse, String style, String trackUrl) {
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
        songRequestRepository.save(entity);
    }

    /**
     * Returns ONLY the accepted songs (waiting in queue) for the dashboard.
     */
    public List<SongRequestEntity> getDashboardQueue(String partyCode) {
        return songRequestRepository.findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, List.of(DECISION_ACCEPTED)
        );
    }

    /**
     * Returns historical songs (PLAYED and optionally REJECTED).
     */
    public List<SongRequestEntity> getHistory(String partyCode) {
        // Limit to 50 most recent PLAYED/REJECTED requests for performance
        return songRequestRepository.findTop50ByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, Arrays.asList(DECISION_PLAYED, DECISION_REJECTED)
        );
    }

    public List<SongRequestEntity> getPublicQueue(String partyCode) {
        return songRequestRepository.findTop5ByPartyCodeAndDecisionOrderByRequestedAtDesc(partyCode, DECISION_ACCEPTED);
    }

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
     */
    @Transactional
    public void pushToSpotify(Long id) {
        songRequestRepository.findById(id).ifPresent(song -> {
            String partyCode = song.getPartyCode();
            PartySettingsEntity settings = partySettingsService.getSettings(partyCode);

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

    // --- Configuration & Settings Helpers ---

    private String getPromptTemplate() {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template", e);
            throw new RuntimeException("System configuration error: prompt template missing", e);
        }
    }

    public void setCurrentGlobalVibe(String partyCode, VibeType newVibe) {
        partySettingsService.updateSettings(partyCode, settings -> settings.setGlobalVibe(newVibe));
        log.info("Party [{}]: Global vibe updated to: {}", partyCode, newVibe);
    }

    public void setActiveProvider(String partyCode, MusicProviderType newProvider) {
        partySettingsService.updateSettings(partyCode, settings -> settings.setActiveProvider(newProvider));
        log.info("Party [{}]: Music provider updated to: {}", partyCode, newProvider);
    }

    public PlaybackMode getCurrentPlaybackMode(String partyCode) {
        return partySettingsService.getSettings(partyCode).getPlaybackMode();
    }

    public void setPlaybackMode(String partyCode, PlaybackMode mode) {
        partySettingsService.updateSettings(partyCode, settings -> settings.setPlaybackMode(mode));
        log.info("Party [{}]: Playback mode updated to: {}", partyCode, mode);
    }
}
