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
        return engine.process("fragments/player-controls", Set.of("next"), new Context(locale));
    }

    @Test
    @DisplayName("the button has the id and the label element the script looks for, and its label and \"sent\" text in English")
    void shouldRenderTheNextButton() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("id=\"playerNextBtn\"", "data-role=\"label\"");
        assertThat(html).contains("data-text-label=\"⏭ Next\"", "data-text-sent=\"Sent…\"");
        assertThat(html).contains(">⏭ Next</span>");
        assertThat(html).contains("title=\"Skip to the next track.");
        assertThat(html).doesNotContain("??", "<html", "<body");
    }

    @Test
    @DisplayName("the button in Polish, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(PL);

        assertThat(html).contains("data-text-label=\"⏭ Dalej\"", "data-text-sent=\"Wysłano…\"");
        assertThat(html).contains(">⏭ Dalej</span>");
        assertThat(html).contains("title=\"Przejdź do następnego utworu.");
        assertThat(html).doesNotContain("??");
    }
}
