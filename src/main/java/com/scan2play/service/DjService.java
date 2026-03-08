package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor // Lombok
public class DjService {

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository repository;
    private final SpotifyService spotifyService;

    @Value("${google.ai.model-name}")
    private String modelName;

    @Value("classpath:prompt-template.txt")
    private Resource promptResource;

    private final PartySettingsRepository partySettingsRepository;

    /**
     * Reads the prompt template from the classpath resource.
     *
     * @return the content of the prompt template as a String
     */
    private String getPromptTemplate() {
        try {
            return new String(promptResource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }

    /**
     * Core business method:
     * 1. Asks Gemini AI to evaluate if the song fits the requested style/vibe
     * 2. Saves the evaluation result to the database
     * 3. Returns the AI verdict to the caller
     *
     * @param songName title of the song (usually from QR scan or user input)
     * @param style    desired style / mood selected by the user
     * @return complete AI response (decision + comment + energy level)
     * @throws RuntimeException if AI client is not properly configured
     */
    public DjResponse evaluateAndSaveSong(String songName, String style) {
        // Step 1: Get classification from Gemini AI
        DjResponse aiResponse = this.evaluateSong(songName, style);

        // Step 2: Build persistent entity
        SongRequestEntity entity = SongRequestEntity.builder()
                .songName(aiResponse.songName())
                .style(style)
                .decision(aiResponse.decision())
                .djComment(aiResponse.comment())
                .energyLevel(aiResponse.energyLevel())
                .requestedAt(LocalDateTime.now())
                .build();

        // Step 3: Add Spotify link only for songs approved by the AI DJ
        if ("accepted".equals(aiResponse.decision())) {
            String url = spotifyService.findTrackUrl(aiResponse.songName());
            entity.setSpotifyUrl(url);  // null is acceptable if track not found
        }

        // Step 4: Persist to database
        repository.save(entity);

        return aiResponse;
    }

    /**
     * Internal helper method that asks Gemini AI to classify a song into the requested vibe/style.
     * <p>
     * The model is instructed to return a structured JSON response that is then mapped
     * to the {@link DjResponse} record.
     *
     * @param songName the song title / artist – song combination provided by user
     * @param style    target style or atmosphere the user wants to match
     * @return parsed AI judgment or error fallback object
     */
    public DjResponse evaluateSong(String songName, String style) {
        String prompt = String.format(getPromptTemplate(), songName, style);

        try {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .build();

            GenerateContentResponse response = client.models.generateContent(modelName, prompt, config);
            return objectMapper.readValue(response.text(), DjResponse.class);
        } catch (Exception e) {
            return new DjResponse("error", "AI failed: " + e.getMessage(), songName, 0);
        }
    }

    /**
     * Retrieves the current global party vibe from the database.
     *
     * @return the current vibe string, or "Dowolny" (Any) if no settings are found.
     */
    public VibeType getCurrentGlobalVibe() {
        return partySettingsRepository.findById(1L)
                .map(PartySettingsEntity::getGlobalVibe)
                .orElse(VibeType.ANY);
    }

    /**
     * Updates the global party vibe in the database.
     *
     * @param newVibe the new vibe VibeType to be set for the party
     */
    public void setCurrentGlobalVibe(VibeType newVibe) {
        PartySettingsEntity settings = partySettingsRepository.findById(1L)
                .orElse(new PartySettingsEntity(1L, VibeType.ANY));

        settings.setGlobalVibe(newVibe);
        partySettingsRepository.save(settings);
    }
}
