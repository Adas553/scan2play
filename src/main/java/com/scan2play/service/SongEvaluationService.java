package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.repository.SongRequestRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * Handles the full AI-powered song evaluation pipeline:
 * <ol>
 *     <li>Asks Google Gemini to evaluate the song request against the party vibe.</li>
 *     <li>Resolves a playable track URL via the active music provider.</li>
 *     <li>Persists the evaluation result.</li>
 *     <li>Optionally queues the song for auto-playback (Spotify only — YouTube is client-side).</li>
 * </ol>
 *
 * Extracted from {@link DjService} to keep each service focused on a single responsibility.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SongEvaluationService {

    /** Config for AI evaluation requests — default temperature allows creative DJ comments. */
    private static final GenerateContentConfig AI_JSON_CONFIG = GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .build();

    /**
     * Config for song name normalization — temperature 0.0 ensures deterministic output.
     * The task is purely factual: map raw input to canonical "ARTIST - TITLE" format.
     */
    private static final GenerateContentConfig AI_NORMALIZE_CONFIG = GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .temperature(0.0f)
            .build();

    private static final String DEFAULT_LANG = "en";
    private static final List<String> SUPPORTED_LANGS = List.of("en", "pl");

    @Value("${google.ai.model-name}")
    private String modelName;

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final QueueService queueService;
    private final MessageSource messageSource;
    private final ResourceLoader resourceLoader;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    /** Prompt template per language code (e.g. "en" → english prompt, "pl" → polish prompt). */
    private Map<String, String> promptTemplates;
    /** Duplicate rule template per language code. */
    private Map<String, String> duplicateRuleTemplates;
    /** Lightweight prompt for normalizing raw song names to "ARTIST - TITLE" format. */
    private String normalizePromptTemplate;

    /**
     * Loads AI prompt templates for all supported languages and sets up the transaction template.
     */
    @PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        try {
            var prompts = new java.util.HashMap<String, String>();
            var duplicates = new java.util.HashMap<String, String>();
            for (String lang : SUPPORTED_LANGS) {
                prompts.put(lang, loadResource("classpath:prompts/prompt-template_" + lang + ".txt"));
                duplicates.put(lang, loadResource("classpath:prompts/prompt-duplicate-rule_" + lang + ".txt"));
            }
            this.promptTemplates = Map.copyOf(prompts);
            this.duplicateRuleTemplates = Map.copyOf(duplicates);
            this.normalizePromptTemplate = loadResource("classpath:prompts/prompt-normalize.txt");
        } catch (IOException e) {
            log.error("Failed to load prompt templates", e);
            throw new RuntimeException("System configuration error: prompt templates missing", e);
        }
    }

    private String loadResource(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Core evaluation pipeline: AI evaluation → track resolution → save → optional auto-queue.
     *
     * @param partyCode The unique code of the party.
     * @param songName  Title of the song (from guest input).
     * @param style     Desired style / mood selected by the guest.
     * @return Complete AI response (decision + comment + energy level).
     */
    public DjResponse evaluateAndSaveSong(String partyCode, String songName, String style) {
        log.info("Party [{}]: Evaluating song: '{}' with style: '{}'", partyCode, songName, style);

        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String recentSongs = getRecentSongsContext(partyCode, settings.getDuplicateCheckWindow());

        // Capture locale in the main thread (where LocaleContextHolder is available)
        Locale locale = LocaleContextHolder.getLocale();

        // Fetch i18n error messages in the main thread
        String autopilotErrorMsg = messageSource.getMessage("dashboard.error.autopilot_failed", null, locale);
        String aiOfflineMsg = messageSource.getMessage("ai.error.offline", null, locale);

        // 1. External API Call: AI Evaluation (prompt language matches guest's locale)
        DjResponse aiResponse = evaluateWithAi(songName, style, recentSongs, aiOfflineMsg, locale);

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

    // ---- Private helpers ----

    private String getRecentSongsContext(String partyCode, int duplicateCheckWindow) {
        if (duplicateCheckWindow <= 0) {
            return null;
        }

        List<SongRequestEntity> recentRequests = songRequestRepository.findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, List.of(DECISION_ACCEPTED, DECISION_PLAYED), PageRequest.of(0, duplicateCheckWindow)
        );

        String recentSongs = recentRequests.stream()
                .map(SongRequestEntity::getSongName)
                .collect(Collectors.joining(", "));

        return recentSongs.isEmpty() ? "None" : recentSongs;
    }

    private DjResponse evaluateWithAi(String songName, String style, String recentSongs, String aiOfflineMsg, Locale locale) {
        try {
            String lang = resolvePromptLanguage(locale);
            String duplicateRule = (recentSongs != null)
                    ? String.format(duplicateRuleTemplates.get(lang), recentSongs)
                    : "";
            String prompt = String.format(promptTemplates.get(lang), songName, style, duplicateRule);

            GenerateContentResponse response = client.models.generateContent(modelName, prompt, AI_JSON_CONFIG);
            return objectMapper.readValue(response.text(), DjResponse.class);
        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            return new DjResponse(DECISION_REJECTED, aiOfflineMsg, songName, 0);
        }
    }

    /**
     * Maps a Locale to a supported prompt language. Falls back to English for unsupported locales.
     */
    private String resolvePromptLanguage(Locale locale) {
        String lang = locale.getLanguage();
        return promptTemplates.containsKey(lang) ? lang : DEFAULT_LANG;
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

        return transactionTemplate.execute(status -> songRequestRepository.save(entity));
    }

    /**
     * Auto-Pilot of a Spotify party: puts an accepted song straight into the DJ's Spotify queue. The song counts as played only
     * when that worked; when it failed it stays in the DJ's queue (accepted) with a note, so the DJ can still push it by hand.
     */
    void handleAutoQueue(PartySettingsEntity settings, SongRequestEntity savedRequest, String trackUrl, String autopilotErrorMsg) {
        if (trackUrl == null || !DECISION_ACCEPTED.equalsIgnoreCase(savedRequest.getDecision()) || settings.getPlaybackMode() != PlaybackMode.AUTO) {
            return;
        }

        // YouTube Auto-Pilot is handled entirely client-side via IFrame API
        if (settings.getActiveProvider() == MusicProviderType.YOUTUBE) {
            return;
        }

        // whenComplete, not exceptionally + thenAccept: exceptionally recovers the future, so a thenAccept after it ran on a failure
        // too and marked the song as played although it never reached Spotify.
        queueService.addToQueue(settings.getPartyCode(), trackUrl, settings.getActiveProvider())
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        log.error("Failed to Auto-Queue track {} for party {}. Updating song status to indicate failure.", trackUrl, settings.getPartyCode(), ex);
                    }
                    transactionTemplate.executeWithoutResult(status ->
                            songRequestRepository.findById(savedRequest.getId()).ifPresent(song -> {
                                if (ex == null) {
                                    DjService.markPlayed(song, LocalDateTime.now());
                                } else {
                                    song.setDjComment(song.getDjComment() + " " + autopilotErrorMsg);
                                }
                            })
                    );
                });
    }

    /**
     * Normalizes a raw song name to canonical "ARTIST - TITLE" format using AI.
     * Used for DJ picks to ensure consistent YouTube cache keys
     * (e.g., "nirvanna smells" → "Nirvana - Smells Like Teen Spirit").
     * <p>
     * Falls back to the raw input if AI is unavailable or returns an invalid response.
     *
     * @param rawInput The raw song name typed by the DJ.
     * @return The normalized song name, or the raw input as fallback.
     */
    public String normalizeSongName(String rawInput) {
        try {
            String prompt = String.format(normalizePromptTemplate, rawInput);
            GenerateContentResponse response = client.models.generateContent(modelName, prompt, AI_NORMALIZE_CONFIG);
            JsonNode json = objectMapper.readTree(response.text());
            String normalized = json.path("songName").asText(null);
            if (normalized != null && !normalized.isBlank()) {
                log.info("Song name normalized: '{}' → '{}'", rawInput, normalized);
                return normalized;
            }
        } catch (Exception e) {
            log.warn("Song name normalization failed for '{}', using raw input", rawInput, e);
        }
        return rawInput;
    }
}

