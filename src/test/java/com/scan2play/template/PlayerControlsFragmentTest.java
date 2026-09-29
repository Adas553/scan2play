package com.scan2play.template;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real {@code fragments/player-controls.html} (the ⏭ button under the video) with the real message bundles.
 */
class PlayerControlsFragmentTest {

    private static final Locale PL = Locale.forLanguageTag("pl");

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    private static String render(Locale locale) {
        return engine.process("fragments/player-controls", Set.of("controls"), new Context(locale));
    }

    @Test
    @DisplayName("the three buttons have the id and the label element the script looks for, and their labels and the \"sent\" text in English")
    void shouldRenderTheButtons() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("id=\"playerNextBtn\"", "id=\"playerPreviousBtn\"", "id=\"playerPauseBtn\"");
        assertThat(html.split("data-role=\"label\"", -1)).hasSize(4);       // one label element per button
        assertThat(html).contains("data-text-label=\"⏭ Next\"", "data-text-label=\"⏮ Previous\"", "data-text-sent=\"Sent…\"");
        assertThat(html).contains(">⏭ Next</span>", ">⏮ Previous</span>");
        assertThat(html).contains("title=\"Skip to the next track.", "title=\"Back. A track that has played for more than 3 seconds");
        assertThat(html).doesNotContain("??", "<html", "<body");
    }

    @Test
    @DisplayName("the tooltip of Back says that a second press within 10 seconds goes to the previous track (RESTART_AFTER_SECONDS and DOUBLE_PRESS_MS in youtube-autopilot.js)")
    void shouldExplainTheDoublePressInTheTooltipOfBack() {
        assertThat(render(Locale.ENGLISH)).contains("a second press within 10 seconds goes to the track that played before it");
        assertThat(render(PL)).contains("drugie naciśnięcie w ciągu 10 sekund przechodzi do utworu, który leciał przed nim");
    }

    @Test
    @DisplayName("the pause button carries both of its labels (pause while it plays, resume while it is paused), and starts as \"Pause\"")
    void shouldCarryBothLabelsOfThePauseButton() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("data-text-pause=\"⏸ Pause\"", "data-text-resume=\"▶ Resume\"", "data-text-label=\"⏸ Pause\"");
        assertThat(html).contains(">⏸ Pause</span>");
        assertThat(html).contains("title=\"Pause the music, or carry on after a pause.");
    }

    @Test
    @DisplayName("back, pause, next — the order of a normal player")
    void shouldKeepTheOrderOfANormalPlayer() {
        String html = render(Locale.ENGLISH);

        assertThat(html.indexOf("playerPreviousBtn")).isLessThan(html.indexOf("playerPauseBtn"));
        assertThat(html.indexOf("playerPauseBtn")).isLessThan(html.indexOf("playerNextBtn"));
    }

    @Test
    @DisplayName("the buttons in Polish, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(PL);

        assertThat(html).contains("data-text-label=\"⏭ Dalej\"", "data-text-label=\"⏮ Wstecz\"", "data-text-sent=\"Wysłano…\"");
        assertThat(html).contains(">⏭ Dalej</span>", ">⏮ Wstecz</span>", ">⏸ Pauza</span>");
        assertThat(html).contains("data-text-pause=\"⏸ Pauza\"", "data-text-resume=\"▶ Wznów\"");
        assertThat(html).contains("title=\"Przejdź do następnego utworu.", "title=\"Wstecz. Utwór, który gra dłużej niż 3 sekundy");
        assertThat(html).doesNotContain("??");
    }
}
