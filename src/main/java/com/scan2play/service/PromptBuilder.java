package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestDedicationEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.VibeType;
import com.scan2play.util.Texts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The prompt a guest's request is judged by (the review of 2026-10-21, R1: out of {@code SongEvaluationService}, which asks Gemini
 * and saves): the templates of {@code src/main/resources/prompts} in the guest's language, filled with the request, the party's
 * genre, the DJ's vibe note, the songs played lately, the comment style and the dedication. Pure text — no call, no database — so
 * a change of the prompt is tested here, and {@code GeminiComparison} builds the same prompts (or a proposed rewrite of them).
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private static final String DEFAULT_LANG = "en";
    private static final List<String> SUPPORTED_LANGS = List.of("en", "pl");

    /** How the prompt names "no genre": words, not the code "ANY" — the AI once quoted "'ANY'" to a guest (2026-10-07). */
    private static final Map<String, String> ANY_GENRE = Map.of(
            "pl", "dowolny (DJ nie wybrał gatunku)",
            "en", "any (the DJ picked no genre)");

    private final ResourceLoader resourceLoader;

    /** Prompt template per language code (e.g. "en" → english prompt, "pl" → polish prompt). */
    private Map<String, String> promptTemplates;
    /** Duplicate rule template per language code. */
    private Map<String, String> duplicateRuleTemplates;
    /** The DJ's vibe note in the prompt, per language code. */
    private Map<String, String> vibeNoteTemplates;
    /** The block of each comment style but {@link CommentStyle#CLASSIC}, per language code. */
    private Map<String, Map<CommentStyle, String>> commentStyleRules;
    /** The guest's dedication in the prompt (V38), per language code. */
    private Map<String, String> dedicationTemplates;

    /** A builder of the templates under {@code classpath:prompts/} as this loader finds them (the tests, {@code GeminiComparison}). */
    static PromptBuilder loaded(ResourceLoader resourceLoader) {
        PromptBuilder prompts = new PromptBuilder(resourceLoader);
        prompts.init();
        return prompts;
    }

    /** Loads the templates of every language: one missing stops the start. */
    @PostConstruct
    void init() {
        var prompts = new HashMap<String, String>();
        var duplicates = new HashMap<String, String>();
        var vibeNotes = new HashMap<String, String>();
        var styles = new HashMap<String, Map<CommentStyle, String>>();
        var dedications = new HashMap<String, String>();
        for (String lang : SUPPORTED_LANGS) {
            vibeNotes.put(lang, load("prompt-vibe-note_" + lang + ".txt"));
            prompts.put(lang, load("prompt-template_" + lang + ".txt"));
            duplicates.put(lang, load("prompt-duplicate-rule_" + lang + ".txt"));
            styles.put(lang, commentStyleRules(load("prompt-comment-style_" + lang + ".txt"), lang));
            dedications.put(lang, load("prompt-dedication_" + lang + ".txt"));
        }
        this.promptTemplates = Map.copyOf(prompts);
        this.duplicateRuleTemplates = Map.copyOf(duplicates);
        this.vibeNoteTemplates = Map.copyOf(vibeNotes);
        this.commentStyleRules = Map.copyOf(styles);
        this.dedicationTemplates = Map.copyOf(dedications);
    }

    private String load(String file) {
        try {
            return resourceLoader.getResource("classpath:prompts/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("System configuration error: prompt template " + file + " missing", e);
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

    /** The prompt in the guest's language, with the request, the style and the duplicate rule filled in. */
    String build(String songName, String style, String recentSongs, Locale locale) {
        return build(songName, style, recentSongs, null, locale);
    }

    /**
     * The same, with the DJ's vibe note (V16) before the duplicate rule: one line of at most
     * {@value PartySettingsEntity#VIBE_NOTE_MAX} characters, without double quotes (the prompt puts it in quotes); nothing when
     * the DJ wrote none.
     */
    String build(String songName, String style, String recentSongs, String vibeNote, Locale locale) {
        return build(songName, style, recentSongs, vibeNote, CommentStyle.CLASSIC, locale);
    }

    /**
     * The same, with the DJ's comment style (V22) after the other rules, where it overrides the comments the prompt gives as
     * examples; {@link CommentStyle#CLASSIC} (or none) adds nothing — the prompt as it was.
     */
    String build(String songName, String style, String recentSongs, String vibeNote, CommentStyle commentStyle, Locale locale) {
        return build(songName, style, recentSongs, vibeNote, commentStyle, null, locale);
    }

    /**
     * The same, with the guest's dedication (V38) after the other rules: its text in quotes (without double quotes of its own) and
     * the step that asks the AI for {@code dedicationOk}; none, the prompt as it was before V38.
     */
    String build(String songName, String style, String recentSongs, String vibeNote, CommentStyle commentStyle, String dedication,
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
        String dedicationRule = dedication == null || dedication.isBlank()
                ? ""
                : String.format(dedicationTemplates.get(lang), Texts.oneLine(dedication, SongRequestDedicationEntity.TEXT_MAX)
                        .replace('"', '\'')) + System.lineSeparator();
        return String.format(promptTemplates.get(lang), songName, genreForPrompt(style, lang),
                vibeRule + duplicateRule + styleRule + dedicationRule);
    }

    /** The party's genre as the prompt gives it: a picked one in quotes (the guest-facing name), none in plain words. */
    static String genreForPrompt(String style, String lang) {
        return style == null || style.isBlank() || VibeType.ANY.name().equals(style)
                ? ANY_GENRE.getOrDefault(lang, ANY_GENRE.get(DEFAULT_LANG))
                : "\"" + style.replace('"', '\'') + "\"";
    }

    /** Maps a Locale to a supported prompt language. Falls back to English for unsupported locales. */
    private String resolvePromptLanguage(Locale locale) {
        String lang = locale.getLanguage();
        return promptTemplates.containsKey(lang) ? lang : DEFAULT_LANG;
    }
}
