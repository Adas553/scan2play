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
 * Renders the real {@code fragments/player-lease-banner.html} with the real message bundles — the DJ dashboard is
 * behind a login, so this is what catches a missing message key or a banner that is not hidden at first.
 */
class PlayerLeaseBannerFragmentTest {

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
        return engine.process("fragments/player-lease-banner", Set.of("banner"), new Context(locale));
    }

    @Test
    @DisplayName("the banner is hidden until the script shows it, and has the hooks the script looks for")
    void shouldBeHiddenAndHaveTheScriptHooks() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("id=\"playerLeaseBanner\"", "id=\"playerLeaseTakeover\"", "data-role=\"text\"");
        assertThat(html).containsPattern("id=\"playerLeaseBanner\"[^>]*class=\"d-none ");
        assertThat(html).doesNotContain("<html", "<body");
    }

    @Test
    @DisplayName("the three texts and the button label are in the banner in English, with every key resolved")
    void shouldCarryTheEnglishTexts() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("data-text-other=\"Playback is running on another device. This window only shows the queue.\"");
        assertThat(html).contains("data-text-free=\"No device is playing right now.\"");
        assertThat(html).contains("data-confirm=\"Playback will move to this device and stop on the other one. Continue?\"");
        assertThat(html).contains(">Play on this device</button>");
        assertThat(html).doesNotContain("??");
    }

    @Test
    @DisplayName("the three texts and the button label are in the banner in Polish, with every key resolved")
    void shouldCarryThePolishTexts() {
        String html = render(PL);

        assertThat(html).contains("data-text-other=\"Odtwarzanie działa na innym urządzeniu. To okno tylko pokazuje kolejkę.\"");
        assertThat(html).contains("data-text-free=\"Żadne urządzenie teraz nie odtwarza.\"");
        assertThat(html).contains("data-confirm=\"Odtwarzanie przejdzie na to urządzenie i zatrzyma się na drugim. Kontynuować?\"");
        assertThat(html).contains(">Odtwarzaj na tym urządzeniu</button>");
        assertThat(html).doesNotContain("??");
    }
}
