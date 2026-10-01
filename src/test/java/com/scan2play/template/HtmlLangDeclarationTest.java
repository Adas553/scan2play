package com.scan2play.template;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code <html lang>} says the language a page is written in (a screen reader pronounces by it, the browser's own translation
 * decides by it). A page whose texts come from the message bundles declares the language of the bundle that wrote them
 * ({@code th:lang="#{html.lang}"}), so the two cannot disagree — a {@code ${#locale.language}} would say "de" over English texts
 * for a browser whose language has no bundle. A page whose text is written into the template (the legal pages: one file per
 * language, {@code LegalController} picks it) declares the language of its file.
 * <p>
 * The whole dashboard is rendered in {@code DashboardPageRenderTest}; here: every page of {@code templates/} is checked for the
 * declaration (so a new page cannot forget it), and the two pages that need no model are rendered in three locales.
 */
class HtmlLangDeclarationTest {

    private static final Locale PL = Locale.forLanguageTag("pl");
    private static final Pattern HTML_TAG = Pattern.compile("<html[^>]*>");

    private static ResourceBundleMessageSource messages;
    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    /** The pages of templates/ (not the fragments in its subdirectories), by file name without the extension. */
    private static List<String> pages() throws IOException {
        List<String> names = new ArrayList<>();
        for (Resource page : new PathMatchingResourcePatternResolver().getResources("classpath:templates/*.html")) {
            names.add(page.getFilename().replaceAll("\\.html$", ""));
        }
        return names;
    }

    private static String source(String page) throws IOException {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:templates/" + page + ".html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String htmlTag(String page) throws IOException {
        Matcher tag = HTML_TAG.matcher(source(page));
        assertThat(tag.find()).as("%s has an <html> tag", page).isTrue();
        return tag.group();
    }

    @Test
    @DisplayName("every page whose texts come from the message bundles declares the language of the bundle (a new page cannot forget it)")
    void shouldDeclareTheLanguageOfTheBundle_onEveryPageThatUsesIt() throws IOException {
        List<String> checked = new ArrayList<>();
        for (String page : pages()) {
            if (source(page).contains("#{")) {
                assertThat(htmlTag(page)).as("<html> of %s", page).contains("th:lang=\"#{html.lang}\"");
                checked.add(page);
            }
        }
        // the seven pages of the DJ, the guests and the errors — a scan that found none would pass without saying anything
        assertThat(checked).contains("dashboard", "landing", "index", "history", "result", "error", "party_ended");
    }

    @Test
    @DisplayName("the legal pages have their text written in, one file per language: no message keys, and the language of the file")
    void shouldDeclareTheLanguageOfTheFile_onTheLegalPages() throws IOException {
        for (String page : List.of("terms", "privacy")) {
            assertThat(source(page)).as(page).doesNotContain("#{");
            assertThat(htmlTag(page)).as(page).contains("lang=\"en\"").doesNotContain("th:lang");
            assertThat(source(page + "_pl")).as(page + "_pl").doesNotContain("#{");
            assertThat(htmlTag(page + "_pl")).as(page + "_pl").contains("lang=\"pl\"").doesNotContain("th:lang");
        }
    }

    @Test
    @DisplayName("each bundle names its own language, and a locale without a bundle gets the default one")
    void shouldNameTheLanguageInEachBundle() {
        assertThat(messages.getMessage("html.lang", null, Locale.ENGLISH)).isEqualTo("en");
        assertThat(messages.getMessage("html.lang", null, PL)).isEqualTo("pl");
        assertThat(messages.getMessage("html.lang", null, Locale.GERMAN)).isEqualTo("en");
    }

    @Test
    @DisplayName("the pages that need no model say the language of their texts: Polish, English, and English for a locale without a bundle")
    void shouldRenderTheDeclaredLanguage() {
        for (String page : List.of("party_ended", "error")) {
            assertThat(render(page, PL)).as("%s in Polish", page).contains("<html lang=\"pl\">");
            assertThat(render(page, Locale.ENGLISH)).as("%s in English", page).contains("<html lang=\"en\">");
            assertThat(render(page, Locale.GERMAN)).as("%s for a German browser", page).contains("<html lang=\"en\">");
        }
    }

    @Test
    @DisplayName("a guest's dead end leads back to the party's link, not to the DJs' login page (the owner, 2026-10-01)")
    void shouldLeadTheGuestBackToTheParty() {
        Context ended = new Context(PL);
        ended.setVariable("partyCode", "ABC12");
        assertThat(engine.process("party_ended", ended)).contains("href=\"/p/ABC12\" id=\"checkAgainBtn\"", "Sprawdź ponownie")
                .doesNotContain("??");

        Context error = new Context(PL);
        error.setVariable("status", 500);
        error.setVariable("path", "/p/ABC12/request");
        assertThat(engine.process("error", error)).contains("href=\"/p/ABC12\"", "id=\"retryBtn\"", "Spróbuj ponownie")
                .doesNotContain("??");
        error.setVariable("path", "/dj/dashboard");
        assertThat(engine.process("error", error)).contains("href=\"/dj/dashboard\"");
    }

    private static String render(String page, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("status", 404);   // what error.html reads; party_ended needs nothing
        return engine.process(page, context);
    }
}
