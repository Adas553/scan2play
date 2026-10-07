package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.DjResponse;
import com.scan2play.model.VibeType;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.SongNames;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

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

    /**
     * The shape of the AI's answer ({@link DjResponse}), given to Gemini with the request: JSON mode alone promises valid JSON, not
     * its fields — Gemini could add one, leave the decision out or write "Accepted". The schema makes every field required and
     * names the only words a decision and a kind of request may be. The reading of the answer stays defensive anyway
     * ({@link #withKnownDecision}, unknown fields ignored by {@code DjResponse}).
     */
    static final Schema ANSWER_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "decision", Schema.builder().type(Type.Known.STRING).enum_(DECISION_ACCEPTED, DECISION_REJECTED).build(),
                    "comment", Schema.builder().type(Type.Known.STRING).build(),
                    "songName", Schema.builder().type(Type.Known.STRING).build(),
                    "energyLevel", Schema.builder().type(Type.Known.INTEGER).build(),
                    "requestKind", Schema.builder().type(Type.Known.STRING)
                            .enum_(DjResponse.KIND_TITLE, DjResponse.KIND_ARTIST, DjResponse.KIND_LYRICS, DjResponse.KIND_MOOD)
                            .build()))
            .required("decision", "comment", "songName", "energyLevel", "requestKind")
            // the song first, the verdict and the comment last: the model works out which song it is before it judges it (the
            // prompt's steps, 2026-10-07 — the verdict first let it judge a song it had not named yet)
            .propertyOrdering("songName", "requestKind", "decision", "energyLevel", "comment")
            .build();

    @Value("${google.ai.model-name}")
    private String modelName;

    /**
     * How many tokens the model may think before it answers a guest's request ({@code google.ai.thinking-budget}): working out
     * which song a line of lyrics comes from needs a little, and every thinking token is paid as output and adds to the guest's
     * wait (the call times out after 15 s, {@code GeminiConfig}). 0 = no thinking, -1 = the model decides.
     */
    @Value("${google.ai.thinking-budget:1024}")
    private int thinkingBudget;

    /**
     * How much a Gemini 3 model thinks ({@code google.ai.thinking-level}: minimal / low / medium / high): those models take a level
     * instead of a budget of tokens ({@link #thinkingConfig}).
     */
    @Value("${google.ai.thinking-level:low}")
    private String thinkingLevel;

    /** Config for AI evaluation requests — default temperature allows creative DJ comments; built in {@link #init()}. */
    private GenerateContentConfig aiJsonConfig;

    private final Client client;
    private final ObjectMapper objectMapper;
    private final SongRequestRepository songRequestRepository;
    private final PartySettingsQueryService partySettingsQueryService;
    private final MessageSource messageSource;
    private final ResourceLoader resourceLoader;
    private final SongRequestCommandService songRequestCommandService;
    private final AiHealthMonitor aiHealthMonitor;
    private final PushNotificationService pushNotificationService;

    /** Prompt template per language code (e.g. "en" → english prompt, "pl" → polish prompt). */
    private Map<String, String> promptTemplates;
    /** Duplicate rule template per language code. */
    private Map<String, String> duplicateRuleTemplates;
    /** The DJ's vibe note in the prompt, per language code. */
    private Map<String, String> vibeNoteTemplates;
    /** The block of each comment style but {@link CommentStyle#CLASSIC}, per language code. */
    private Map<String, Map<CommentStyle, String>> commentStyleRules;

    /**
     * Loads AI prompt templates for all supported languages.
     */
    @PostConstruct
    public void init() {
        this.aiJsonConfig = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(ANSWER_SCHEMA)
                .thinkingConfig(thinkingConfig(modelName, thinkingBudget, thinkingLevel))
                .build();
        try {
            var prompts = new HashMap<String, String>();
            var duplicates = new HashMap<String, String>();
            var vibeNotes = new HashMap<String, String>();
            var styles = new HashMap<String, Map<CommentStyle, String>>();
            for (String lang : SUPPORTED_LANGS) {
                vibeNotes.put(lang, loadResource("classpath:prompts/prompt-vibe-note_" + lang + ".txt"));
                prompts.put(lang, loadResource("classpath:prompts/prompt-template_" + lang + ".txt"));
                duplicates.put(lang, loadResource("classpath:prompts/prompt-duplicate-rule_" + lang + ".txt"));
                styles.put(lang, commentStyleRules(loadResource("classpath:prompts/prompt-comment-style_" + lang + ".txt"), lang));
            }
            this.promptTemplates = Map.copyOf(prompts);
            this.duplicateRuleTemplates = Map.copyOf(duplicates);
            this.vibeNoteTemplates = Map.copyOf(vibeNotes);
            this.commentStyleRules = Map.copyOf(styles);
        } catch (IOException e) {
            log.error("Failed to load prompt templates", e);
            throw new RuntimeException("System configuration error: prompt templates missing", e);
        }
    }

    /**
     * The comment styles' blocks of one language: a line {@code STYLE=text} for each style but {@link CommentStyle#CLASSIC} (blank
     * lines and {@code #} comments ignored). A style without its line stops the start: the DJ would pick it and get the classic one.
     */
    static Map<CommentStyle, String> commentStyleRules(String file, String lang) {
        Map<CommentStyle, String> rules = new EnumMap<>(CommentStyle.class);
        file.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(line -> {
            int eq = line.indexOf('=');
            rules.put(CommentStyle.valueOf(line.substring(0, eq)), line.substring(eq + 1).strip());
        });
        for (CommentStyle style : CommentStyle.values()) {
            if (style != CommentStyle.CLASSIC && !rules.containsKey(style)) {
                throw new IllegalStateException("prompt-comment-style_" + lang + ".txt has no line for " + style);
            }
        }
        return Collections.unmodifiableMap(rules);
    }

    /**
     * How much the model thinks: a Gemini 2.x model by a budget of tokens, a later one (Gemini 3, 3.5…) by a level — those take
     * a level, and Google's documentation no longer gives them a budget. An unknown level is a start-up error, not a silent
     * default.
     */
    static ThinkingConfig thinkingConfig(String model, int budget, String level) {
        if (model == null || model.startsWith("gemini-2")) {
            return ThinkingConfig.builder().thinkingBudget(budget).build();
        }
        return ThinkingConfig.builder()
                .thinkingLevel(new ThinkingLevel(ThinkingLevel.Known.valueOf(level.strip().toUpperCase(Locale.ROOT))))
                .build();
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
        DjResponse aiResponse = evaluateWithAi(songName, style, recentSongs, settings.getVibeNote(), settings.getCommentStyle(), locale);
        if (aiResponse == null) {
            aiHealthMonitor.recordUnchecked();
            aiResponse = withoutTheAi(partyCode, songName, locale);
        } else {
            aiHealthMonitor.recordAnswered();
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
        if (saved.outcome() == SongRequestCommandService.Outcome.SKIPPED_BY_DJ) {
            // The DJ skipped this song lately (usually: they do not have it): nothing saved, the guest is told to pick another one
            return new DjResponse(DECISION_REJECTED, messageSource.getMessage("guest.skipped_by_dj", null, locale),
                    saved.request().getSongName(), 0, aiResponse.requestKind());
        }
        SongRequestEntity savedRequest = saved.request();
        if (saved.outcome() == SongRequestCommandService.Outcome.NEW && DECISION_ACCEPTED.equals(savedRequest.getDecision())) {
            // A new song on the DJ's list (a vote on a waiting one is not news): the DJ's devices that asked for it get a notification
            pushNotificationService.notifyNewRequest(settings.getOwnerId(), partyCode, savedRequest.getSongName());
        }
        if (saved.outcome() != SongRequestCommandService.Outcome.NEW && !DECISION_ACCEPTED.equalsIgnoreCase(aiResponse.decision())) {
            // The AI rejected this time a song the party took already and that still waits: the guest hears the verdict it was taken with
            log.info("Party [{}]: '{}' rejected this time, but it waits in the queue — counted on it", partyCode, savedRequest.getSongName());
            aiResponse = aiResponse.withVerdict(savedRequest.getDecision(), savedRequest.getDjComment(), savedRequest.getEnergyLevel());
        }

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

        List<SongRequestEntity> recentRequests = songRequestRepository.findRecentlyPlayed(partyCode, PageRequest.of(0, duplicateCheckWindow));

        String recentSongs = recentRequests.stream()
                .map(SongRequestEntity::getSongName)
                .collect(Collectors.joining(", "));

        return recentSongs.isEmpty() ? "None" : recentSongs;
    }

    /** The AI's answer, or null when the AI could not be asked (an error, a timeout, an answer that is not JSON). */
    private DjResponse evaluateWithAi(String songName, String style, String recentSongs, String vibeNote, CommentStyle commentStyle,
                                      Locale locale) {
        try {
            String prompt = buildPrompt(songName, style, recentSongs, vibeNote, commentStyle, locale);
            long started = System.nanoTime();
            String text = askAi(prompt, aiJsonConfig);
            // how long the guest waited for the AI: search the log for "AI answered" (2026-10-07: 3.5 Flash timed out a while)
            log.info("AI answered in {} ms ({})", (System.nanoTime() - started) / 1_000_000, modelName);
            DjResponse answer = objectMapper.readValue(text, DjResponse.class);
            // The AI may leave the name of a rejected song empty (the Polish prompt once allowed it): the history would show a
            // row without a song, so it keeps what the guest asked for.
            if (answer.songName() == null || answer.songName().isBlank()) {
                answer = answer.withSongName(songName);
            } else if (!SongNames.tidy(answer.songName()).equals(answer.songName())) {
                // a control character in place of a dash (the page shows "□"), a line break in the middle of the name
                answer = answer.withSongName(SongNames.tidy(answer.songName()));
            }
            return withKnownDecision(answer);
        } catch (Exception e) {
            log.error("AI evaluation failed for song: '{}'", songName, e);
            return null;
        }
    }

    /**
     * The AI's decision as the app keeps it: {@code accepted}, whatever its case, or else {@code rejected} (another word, or none).
     * The queue, the votes and the history match the decision exactly — an "Accepted" row would be shown nowhere.
     */
    static DjResponse withKnownDecision(DjResponse answer) {
        String decision = answer.decision() != null && DECISION_ACCEPTED.equalsIgnoreCase(answer.decision().strip())
                ? DECISION_ACCEPTED
                : DECISION_REJECTED;
        return decision.equals(answer.decision()) ? answer : answer.withVerdict(decision, answer.comment(), answer.energyLevel());
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
     * on each try), while YouTube's search matches lyrics well. The guest's words too when the AI's song has none of them
     * ({@link SongNames#sharesNoWord}, marked "⚠ Sprawdź"): the DJ checks it against what the guest asked for.
     *
     * @param guestText what the guest typed
     */
    static String searchQueryFor(DjResponse aiResponse, String guestText) {
        if (guestText != null && !guestText.isBlank()
                && (aiResponse.isLyrics() || SongNames.sharesNoWord(guestText, aiResponse.songName()))) {
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
     * the AI (or to pay for its tokens). The form allows up to 10 KB. Every kind of dash is "-": the AI copied a "–" back as a
     * control character ({@link SongNames#tidy}).
     */
    static String forPrompt(String guestText) {
        return asTyped(guestText).replace('"', '\'').replaceAll("[\\u2010-\\u2015\\u2212]", "-");
    }

    /**
     * What the guest typed as the DJ is shown it beside the AI's song: the line the AI is given ({@link #forPrompt}), with the
     * guest's double quotes.
     */
    public static String asTyped(String guestText) {
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
        return buildPrompt(songName, style, recentSongs, vibeNote, CommentStyle.CLASSIC, locale);
    }

    /**
     * The same, with the DJ's comment style (V22) after the other rules, where it overrides the comments the prompt gives as
     * examples; {@link CommentStyle#CLASSIC} (or none) adds nothing — the prompt as it was.
     */
    String buildPrompt(String songName, String style, String recentSongs, String vibeNote, CommentStyle commentStyle,
                       Locale locale) {
        String lang = resolvePromptLanguage(locale);
        String note = Texts.oneLine(vibeNote, PartySettingsEntity.VIBE_NOTE_MAX).replace('"', '\'');
        String vibeRule = note.isEmpty() ? "" : String.format(vibeNoteTemplates.get(lang), note);
        String duplicateRule = (recentSongs != null)
                ? String.format(duplicateRuleTemplates.get(lang), recentSongs)
                : "";
        String styleRule = commentStyle == null || commentStyle == CommentStyle.CLASSIC
                ? ""
                : System.lineSeparator() + commentStyleRules.get(lang).get(commentStyle) + System.lineSeparator();
        return String.format(promptTemplates.get(lang), songName, genreForPrompt(style, lang), vibeRule + duplicateRule + styleRule);
    }

    /** How the prompt names "no genre": words, not the code "ANY" — the AI once quoted "'ANY'" to a guest (2026-10-07). */
    private static final Map<String, String> ANY_GENRE = Map.of(
            "pl", "dowolny (DJ nie wybrał gatunku)",
            "en", "any (the DJ picked no genre)");

    /** The party's genre as the prompt gives it: a picked one in quotes (the guest-facing name), none in plain words. */
    static String genreForPrompt(String style, String lang) {
        return style == null || style.isBlank() || VibeType.ANY.name().equals(style)
                ? ANY_GENRE.getOrDefault(lang, ANY_GENRE.get(DEFAULT_LANG))
                : "\"" + style.replace('"', '\'') + "\"";
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

