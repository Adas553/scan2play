package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.GenerateContentResponse;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.Texts;
import com.scan2play.util.YouTubeSearchLinks;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;

/**
 * Handles the full AI-powered song evaluation pipeline:
 * <ol>
 *     <li>Asks Google Gemini to evaluate the song request against the party vibe.</li>
 *     <li>Gives an accepted song its "🔍 Podejrzyj" link (YouTube's search results — no API).</li>
 *     <li>Persists the evaluation result.</li>
 * </ol>
 *
 * Extracted from {@link DjService} to keep each service focused on a single responsibility.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SongEvaluationService {

    /** The longest guest's text that reaches the prompt ({@link #forPrompt}). */
    static final int GUEST_TEXT_MAX = 150;

    private static final String DEFAULT_LANG = "en";
    private static final List<String> SUPPORTED_LANGS = List.of("en", "pl");

    @Value("${google.ai.model-name}")
    private String modelName;

    /**
     * How many tokens the model may think before it answers a guest's request ({@code google.ai.thinking-budget}): working out
     * which song a line of lyrics comes from needs a little, and every thinking token is paid as output and adds to the guest's
     * wait (the call times out after 10 s, {@code GeminiConfig}). 0 = no thinking, -1 = the model decides.
     */
    @Value("${google.ai.thinking-budget:1024}")
    private int thinkingBudget;

    /** Config for AI evaluation requests — default temperature allows creative DJ comments; built in {@link #init()}. */
    private GenerateContentConfig aiJsonConfig;

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final MessageSource messageSource;
    private final ResourceLoader resourceLoader;
    private final SongRequestCommandService songRequestCommandService;


    /** Prompt template per language code (e.g. "en" → english prompt, "pl" → polish prompt). */
    private Map<String, String> promptTemplates;
    /** Duplicate rule template per language code. */
    private Map<String, String> duplicateRuleTemplates;
    /** The DJ's vibe note in the prompt, per language code. */
    private Map<String, String> vibeNoteTemplates;

    /**
     * Loads AI prompt templates for all supported languages.
     */
    @PostConstruct
    public void init() {
        this.aiJsonConfig = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(thinkingBudget).build())
                .build();
        try {
            var prompts = new java.util.HashMap<String, String>();
            var duplicates = new java.util.HashMap<String, String>();
            var vibeNotes = new java.util.HashMap<String, String>();
            for (String lang : SUPPORTED_LANGS) {
                vibeNotes.put(lang, loadResource("classpath:prompts/prompt-vibe-note_" + lang + ".txt"));
                prompts.put(lang, loadResource("classpath:prompts/prompt-template_" + lang + ".txt"));
                duplicates.put(lang, loadResource("classpath:prompts/prompt-duplicate-rule_" + lang + ".txt"));
            }
            this.promptTemplates = Map.copyOf(prompts);
            this.duplicateRuleTemplates = Map.copyOf(duplicates);
            this.vibeNoteTemplates = Map.copyOf(vibeNotes);
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
     * Core evaluation pipeline: AI evaluation → the song's link → save.
     *
     * @param partyCode The unique code of the party.
     * @param guestText What the guest typed: a title, an artist or a line of the lyrics; cut to one short line before the AI sees
     *                  it ({@link #forPrompt}).
     * @param style     The party's vibe the AI judges the request against.
     * @return Complete AI response (decision + comment + energy level). A request the AI reads as a mood is <b>not saved</b> — the
     *         response says so ({@link DjResponse#isMood()}) and the caller asks the guest for a song.
     */
    public DjResponse evaluateAndSaveSong(String partyCode, String guestText, String style) {
        return evaluateAndSaveSong(partyCode, guestText, style, Set.of());
    }

    /**
     * The same, for a guest whose earlier requests at the party are known: an accepted song that already waits in the queue is
     * counted as one more vote on it ({@link SongRequestCommandService}), unless it is one of {@code guestsOwnIds}.
     */
    public DjResponse evaluateAndSaveSong(String partyCode, String guestText, String style, Set<Long> guestsOwnIds) {
        String songName = forPrompt(guestText);
        log.info("Party [{}]: Evaluating request: '{}' with style: '{}'", partyCode, songName, style);

        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String recentSongs = getRecentSongsContext(partyCode, settings.getDuplicateCheckWindow());

        // Capture locale in the main thread (where LocaleContextHolder is available)
        Locale locale = LocaleContextHolder.getLocale();

        // 1. External API Call: AI Evaluation (prompt language matches guest's locale)
        DjResponse aiResponse = evaluateWithAi(songName, style, recentSongs, settings.getVibeNote(), locale);
        if (aiResponse == null) {
            aiResponse = withoutTheAi(partyCode, songName, locale);
        }
        if (aiResponse.isMood()) {
            log.info("Party [{}]: '{}' reads as a mood, not a song — not saved", partyCode, songName);
            return aiResponse;
        }

        // 2. The "🔍 Podejrzyj" link of an accepted song: YouTube's search results (a page the DJ's browser opens, no API)
        String trackUrl = DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())
                ? YouTubeSearchLinks.forQuery(searchQueryFor(aiResponse, songName))
                : null;

        // 3. Database Operations: a new row, or one more vote on the same song waiting in the queue
        SongRequestCommandService.Saved saved = saveSongRequest(partyCode, aiResponse, asTyped(guestText), style, trackUrl, guestsOwnIds);
        SongRequestEntity savedRequest = saved.request();

        return aiResponse.savedAs(savedRequest.getId(), savedRequest.getSongName(), savedRequest.getVotes(),
                saved.outcome() == SongRequestCommandService.Outcome.ALREADY_YOURS);
    }

    // ---- Private helpers ----

    /**
     * The songs the AI must not accept again: the party's recently PLAYED ones. A song that still waits is not on the list — a
     * request for it becomes one more vote on it ({@link SongRequestCommandService}).
     */
    private String getRecentSongsContext(String partyCode, int duplicateCheckWindow) {
        if (duplicateCheckWindow <= 0) {
            return null;
        }

        List<SongRequestEntity> recentRequests = songRequestRepository.findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(
                partyCode, List.of(DECISION_PLAYED), PageRequest.of(0, duplicateCheckWindow)
        );

        String recentSongs = recentRequests.stream()
                .map(SongRequestEntity::getSongName)
                .collect(Collectors.joining(", "));

        return recentSongs.isEmpty() ? "None" : recentSongs;
    }

    /** The AI's answer, or null when the AI could not be asked (an error, a timeout, an answer that is not JSON). */
    private DjResponse evaluateWithAi(String songName, String style, String recentSongs, String vibeNote, Locale locale) {
        try {
            String prompt = buildPrompt(songName, style, recentSongs, vibeNote, locale);
            DjResponse answer = objectMapper.readValue(askAi(prompt, aiJsonConfig), DjResponse.class);
            // The AI may leave the name of a rejected song empty (the Polish prompt once allowed it): the history would show a
            // row without a song, so it keeps what the guest asked for.
            return (answer.songName() == null || answer.songName().isBlank()) ? answer.withSongName(songName) : answer;
        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            return null;
        }
    }

    /**
     * The answer when the AI could not be asked: the request goes on to the DJ unchecked (accepted, the guest's own words, a note
     * instead of the AI's comment) — the DJ looks at every request anyway, and a wedding should not lose requests while the AI is
     * down.
     */
    DjResponse withoutTheAi(String partyCode, String songName, Locale locale) {
        log.warn("Party [{}]: the AI could not be asked — '{}' goes to the DJ unchecked", partyCode, songName);
        return new DjResponse(DECISION_ACCEPTED, messageSource.getMessage("ai.unavailable.to_dj", null, locale), songName, 0,
                DjResponse.KIND_UNCHECKED);
    }

    /**
     * What the link searches for: the AI's name of the song — except when the guest typed a line of the lyrics, then the guest's
     * own words. The AI does not know lyrics reliably (a line of a well-known Polish song got a different made-up artist and title
     * on each try), while YouTube's search matches lyrics well.
     *
     * @param guestText what the guest typed
     */
    static String searchQueryFor(DjResponse aiResponse, String guestText) {
        if (aiResponse.isLyrics() && guestText != null && !guestText.isBlank()) {
            return guestText.strip();
        }
        return aiResponse.songName();
    }

    /** One call to Gemini; the text of its answer. Package-private so a test can answer instead of Gemini. */
    String askAi(String prompt, GenerateContentConfig config) {
        GenerateContentResponse response = client.models.generateContent(modelName, prompt, config);
        return response.text();
    }

    /**
     * What the guest typed, as it goes into the prompt (review item 4.6): one line, at most {@value #GUEST_TEXT_MAX} characters,
     * without the double quotes the prompt puts around it — a song name needs no more, and a longer text is only a way to steer
     * the AI (or to pay for its tokens). The form allows up to 10 KB.
     */
    static String forPrompt(String guestText) {
        return asTyped(guestText).replace('"', '\'');
    }

    /**
     * What the guest typed as the DJ is shown it beside the AI's song: the line the AI is given ({@link #forPrompt}), with the
     * guest's double quotes.
     */
    static String asTyped(String guestText) {
        return Texts.oneLine(guestText, GUEST_TEXT_MAX);
    }

    /** The prompt in the guest's language, with the request, the style and the duplicate rule filled in. */
    String buildPrompt(String songName, String style, String recentSongs, Locale locale) {
        return buildPrompt(songName, style, recentSongs, null, locale);
    }

    /**
     * The same, with the DJ's vibe note (V16) before the duplicate rule: one line of at most
     * {@value PartySettingsEntity#VIBE_NOTE_MAX} characters, without double quotes (the prompt puts it in quotes); nothing when
     * the DJ wrote none.
     */
    String buildPrompt(String songName, String style, String recentSongs, String vibeNote, Locale locale) {
        String lang = resolvePromptLanguage(locale);
        String note = Texts.oneLine(vibeNote, PartySettingsEntity.VIBE_NOTE_MAX).replace('"', '\'');
        String vibeRule = note.isEmpty() ? "" : String.format(vibeNoteTemplates.get(lang), note);
        String duplicateRule = (recentSongs != null)
                ? String.format(duplicateRuleTemplates.get(lang), recentSongs)
                : "";
        return String.format(promptTemplates.get(lang), songName, style, vibeRule + duplicateRule);
    }

    /**
     * Maps a Locale to a supported prompt language. Falls back to English for unsupported locales.
     */
    private String resolvePromptLanguage(Locale locale) {
        String lang = locale.getLanguage();
        return promptTemplates.containsKey(lang) ? lang : DEFAULT_LANG;
    }

    private SongRequestCommandService.Saved saveSongRequest(String partyCode, DjResponse aiResponse, String guestText, String style,
                                                            String trackUrl, Set<Long> guestsOwnIds) {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode(partyCode)
                .songName(aiResponse.songName())
                .guestText(guestText.isEmpty() ? null : guestText)
                .style(style)
                .decision(aiResponse.decision())
                .djComment(aiResponse.comment())
                .energyLevel(aiResponse.energyLevel())
                .trackUrl(trackUrl)
                .requestedAt(Instant.now())
                .build();

        return songRequestCommandService.saveOrVote(entity, guestsOwnIds);
    }
}

