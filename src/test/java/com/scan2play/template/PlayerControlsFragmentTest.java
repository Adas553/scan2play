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
    @DisplayName("the buttons have the id and the label element the script looks for, and their labels and the \"sent\" text in English")
    void shouldRenderTheButtons() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("id=\"playerNextBtn\"", "id=\"playerPreviousBtn\"", "id=\"playerPauseBtn\"",
                "id=\"playerBackBtn\"", "id=\"playerRestartBtn\"");
        assertThat(html.split("data-role=\"label\"", -1)).hasSize(6);       // one label element per button: five buttons
        assertThat(html).contains("data-text-label=\"⏭ Next\"", "data-text-label=\"⏮ Previous\"", "data-text-sent=\"Sent…\"");
        assertThat(html).contains(">⏭ Next</span>", ">⏮ Previous</span>");
        assertThat(html).contains("title=\"Skip to the next track.", "title=\"Back. A track that has played for more than 3 seconds");
        assertThat(html).doesNotContain("??", "<html", "<body");
    }

    @Test
    @DisplayName("the tooltip of Back says that a second press within 20 seconds goes to the previous track (RESTART_AFTER_SECONDS and DOUBLE_PRESS_MS in youtube-autopilot.js)")
    void shouldExplainTheDoublePressInTheTooltipOfBack() {
        assertThat(render(Locale.ENGLISH)).contains("a second press within 20 seconds goes to the track that played before it");
        assertThat(render(PL)).contains("drugie naciśnięcie w ciągu 20 sekund przechodzi do utworu, który leciał przed nim");
    }

    @Test
    @DisplayName("the single Back no longer mentions a remote: a window that does not play has two buttons of its own (renderBackButtons in youtube-autopilot.js)")
    void shouldNotPromiseARemoteBackOnTheSingleButton() {
        assertThat(render(Locale.ENGLISH)).contains("Within the first 3 seconds of a track one press is enough.\"")
                .doesNotContain("Within the first 3 seconds of a track one press is enough. In a window");
        assertThat(render(PL)).contains("W pierwszych 3 sekundach utworu wystarcza jedno naciśnięcie.\"")
                .doesNotContain("wystarcza jedno naciśnięcie. W oknie");
    }

    @Test
    @DisplayName("a window that does not play has two buttons for back — the previous track, and the track from the start — hidden until the script knows it is such a window")
    void shouldHaveTwoBackButtonsForAWindowThatDoesNotPlay() {
        String english = render(Locale.ENGLISH);

        assertThat(buttonTag(english, "playerBackBtn")).contains("d-none").contains("data-text-label=\"⏮ Previous\"")
                .contains("title=\"Previous track: goes back to the track that played before this one");
        assertThat(buttonTag(english, "playerRestartBtn")).contains("d-none").contains("data-text-label=\"↺ From start\"")
                .contains("title=\"Plays the current track again from the start.");
        assertThat(english).contains(">↺ From start</span>");
        // the single ⏮ is the one that shows at first (the window that plays, or one that does not know yet)
        assertThat(buttonTag(english, "playerPreviousBtn")).doesNotContain("d-none");
        assertThat(buttonTag(english, "playerPauseBtn")).doesNotContain("d-none");
        assertThat(buttonTag(english, "playerNextBtn")).doesNotContain("d-none");
        // both carry the "sent" text of a remote press
        assertThat(buttonTag(english, "playerBackBtn")).contains("data-text-sent=\"Sent…\"");
        assertThat(buttonTag(english, "playerRestartBtn")).contains("data-text-sent=\"Sent…\"");
    }

    @Test
    @DisplayName("the two back buttons in Polish: Wstecz and Od początku, every message key resolved")
    void shouldRenderTheTwoBackButtonsInPolish() {
        String html = render(PL);

        assertThat(buttonTag(html, "playerBackBtn")).contains("data-text-label=\"⏮ Wstecz\"")
                .contains("title=\"Poprzedni utwór: wraca do utworu, który leciał przed tym");
        assertThat(buttonTag(html, "playerRestartBtn")).contains("data-text-label=\"↺ Od początku\"")
                .contains("title=\"Odtwarza bieżący utwór jeszcze raz od początku.");
        assertThat(html).contains(">↺ Od początku</span>").doesNotContain("??");
    }

    @Test
    @DisplayName("the buttons of a window that does not play sit where the single back does: before pause and next, back first")
    void shouldPlaceTheTwoBackButtonsBeforePauseAndNext() {
        String html = render(Locale.ENGLISH);

        assertThat(html.indexOf("playerPreviousBtn")).isLessThan(html.indexOf("playerBackBtn"));
        assertThat(html.indexOf("playerBackBtn")).isLessThan(html.indexOf("playerRestartBtn"));
        assertThat(html.indexOf("playerRestartBtn")).isLessThan(html.indexOf("playerPauseBtn"));
    }

    /** The opening tag of the button with this id (attributes included), to look at one button at a time. */
    private static String buttonTag(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        int start = html.lastIndexOf("<button", at);
        return html.substring(start, html.indexOf(">", at) + 1);
    }

    @Test
    @DisplayName("the tooltip of Next says that after Back it goes forward again through the tracks that played (skipToNext in youtube-autopilot.js)")
    void shouldExplainInTheTooltipOfNextThatItRetracesTheStepsAfterBack() {
        assertThat(render(Locale.ENGLISH)).contains("After going back with ⏮ it goes forward again through the tracks that played");
        assertThat(render(PL)).contains("Po cofnięciu przyciskiem ⏮ przechodzi z powrotem do przodu przez utwory, które grały");
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
