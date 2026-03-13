package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
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
    private final PartySettingsRepository partySettingsRepository;
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
     *
     * @param songName title of the song (usually from QR scan or user input)
     * @param style    desired style / mood selected by the user
     * @return complete AI response (decision + comment + energy level)
     */
    @Transactional
    public DjResponse evaluateAndSaveSong(String songName, String style) {
        log.info("Evaluating song: '{}' with style: '{}'", songName, style);

        // 1. Get AI Verdict
        DjResponse aiResponse = evaluateWithAi(songName, style);
        
        // 2. Resolve URL (only if accepted)
        String trackUrl = null;
        if (DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())) {
            trackUrl = resolveTrackUrl(aiResponse.songName());
        }

        // 3. Persist Request
        saveSongRequest(aiResponse, style, trackUrl);

        return aiResponse;
    }

    private DjResponse evaluateWithAi(String songName, String style) {
        try {
            String prompt = String.format(getPromptTemplate(), songName, style);
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .build();

            GenerateContentResponse response = client.models.generateContent(modelName, prompt, config);
            DjResponse djResponse = objectMapper.readValue(response.text(), DjResponse.class);
            log.info("AI Verdict for '{}': {}", songName, djResponse.decision());
            return djResponse;

        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            // Fallback response in case of AI failure
            return new DjResponse(DECISION_REJECTED, "AI is currently offline. Please try again.", songName, 0);
        }
    }

    private String resolveTrackUrl(String songName) {
        try {
            MusicProviderType provider = getActiveProvider();
            log.debug("Resolving track '{}' using provider: {}", songName, provider);
            return queueService.resolveTrack(songName, provider);
        } catch (Exception e) {
            log.warn("Failed to resolve track URL for '{}'", songName, e);
            return null; // Graceful degradation: save song without URL
        }
    }

    private void saveSongRequest(DjResponse aiResponse, String style, String trackUrl) {
        SongRequestEntity entity = SongRequestEntity.builder()
                .songName(aiResponse.songName())
                .style(style)
                .decision(aiResponse.decision())
                .djComment(aiResponse.comment())
                .energyLevel(aiResponse.energyLevel())
                .trackUrl(trackUrl)
                .requestedAt(LocalDateTime.now())
                .build();

        songRequestRepository.save(entity);
        log.info("Saved song request: ID={}", entity.getId());
    }

    // --- Public Data Accessors ---

    public List<SongRequestEntity> getPublicQueue() {
        return songRequestRepository.findTop5ByDecisionOrderByRequestedAtDesc(DECISION_ACCEPTED);
    }

    /**
     * Marks a song as played, effectively removing it from the public queue but keeping it in history.
     */
    @Transactional
    public void markSongAsPlayed(Long id) {
        songRequestRepository.findById(id).ifPresent(song -> {
            song.setDecision(DECISION_PLAYED);
            songRequestRepository.save(song);
            log.info("Marked song ID={} as PLAYED", id);
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

    public VibeType getCurrentGlobalVibe() {
        return getPartySettings().getGlobalVibe();
    }

    public void setCurrentGlobalVibe(VibeType newVibe) {
        updatePartySettings(settings -> settings.setGlobalVibe(newVibe));
        log.info("Global vibe updated to: {}", newVibe);
    }

    public MusicProviderType getActiveProvider() {
        return getPartySettings().getActiveProvider();
    }

    public void setActiveProvider(MusicProviderType newProvider) {
        updatePartySettings(settings -> settings.setActiveProvider(newProvider));
        log.info("Music provider updated to: {}", newProvider);
    }

    /**
     * Helper to get or create party settings (Singleton-like approach for MVP)
     */
    private PartySettingsEntity getPartySettings() {
        return partySettingsRepository.findById(1L)
                .orElse(new PartySettingsEntity(1L, VibeType.ANY, MusicProviderType.SPOTIFY));
    }

    /**
     * Helper to update party settings transactionally
     */
    private void updatePartySettings(java.util.function.Consumer<PartySettingsEntity> updater) {
        PartySettingsEntity settings = getPartySettings();
        updater.accept(settings);
        partySettingsRepository.save(settings);
    }
}
