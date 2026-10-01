package com.scan2play.template;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService.GuestQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real guest page ({@code index.html}) with the real message bundles and checks the two request modes: a specific
 * song (the default) or a mood, and the way back to the form after a mood was sent as a song — the text kept, the mood chosen.
 */
class GuestPageRenderTest {

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

    private static String render(Locale locale, Map<String, Object> flash) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        Map<String, Object> model = new HashMap<>(Map.of(
                "globalVibe", VibeType.ANY, "activeProvider", MusicProviderType.YOUTUBE,
                "guestQueue", new GuestQueue(null, List.of(), Set.of(), null, null), "partyCode", "ABC12"));
        model.putAll(flash);
        context.setVariables(model);
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        return engine.process("index", context);
    }

    /** The opening tag of the element with this id. */
    private static String tag(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as(id).isPositive();
        return html.substring(html.lastIndexOf('<', at), html.indexOf('>', at) + 1);
    }

    @Test
    void theFormOffersBothModes_theSongModeChosen_inBothLanguages() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            String html = render(locale, Map.of());

            assertThat(html).as(locale.toString()).doesNotContain("??");
            assertThat(tag(html, "modeSong")).contains("name=\"requestMode\"", "value=\"SONG\"", "checked");
            assertThat(tag(html, "modeMood")).contains("value=\"MOOD\"").doesNotContain("checked");
            assertThat(tag(html, "songInputHelp")).contains("data-text-song=\"", "data-text-mood=\"");
            // no inline script (CSP, review 5.1): the page's script is a file, its text a data attribute
            DashboardPageRenderTest.assertNothingInline(html);
            assertThat(html).contains("<script src=\"/js/guest-party.js\">");
            assertThat(tag(html, "submitBtn")).contains("data-text-submitting=\"");
        }
        String pl = render(PL, Map.of());
        assertThat(pl).contains("Konkretna piosenka", "Nastrój", "Czego chcesz?");
        writePreview("index.html", pl);
    }

    /** For a look in a browser (the page links /css/app.css): target/guest-page/NAME. */
    private static void writePreview(String name, String html) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "guest-page", name);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, html, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static SongRequestEntity song(long id, String name) {
        return SongRequestEntity.builder().id(id).songName(name).build();
    }

    @Test
    void theQueue_saysWhatPlays_whatComesNextInOrder_andWhereTheGuestsSongWaits() {
        GuestQueue queue = new GuestQueue("Wilki - Baśka", List.of(song(1, "First"), song(2, "Mine"), song(3, "Third")),
                Set.of(2L), 2, "Mine");
        String html = render(PL, Map.of("guestQueue", queue));
        writePreview("index-with-queue.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("id=\"guestQueueBox\"", "data-url=\"/p/ABC12/queue\"");
        assertThat(html).contains("Twoja piosenka „Mine” — 2. w kolejce", "Teraz gra", "Wilki - Baśka", "Następne w kolejce");
        assertThat(html.indexOf("First")).as("in the order they play").isLessThan(html.indexOf(">Mine<"));
        assertThat(html.indexOf(">Mine<")).isLessThan(html.indexOf("Third"));
        String mine = html.substring(html.indexOf(">Mine<"), html.indexOf("Third"));
        assertThat(mine).as("the guest's own song is marked").contains("Twoja");
        assertThat(html.substring(html.indexOf("First"), html.indexOf(">Mine<"))).doesNotContain("Twoja");
    }

    /** GET /p/{code}/queue renders the fragment alone: the same list, with its refresh button, and nothing when it is empty. */
    @Test
    void theFragmentAlone_isTheList_withItsRefreshButton() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariable("guestQueue", new GuestQueue("Wilki - Baśka", List.of(song(1, "First")), Set.of(), null, null));
        String fragment = engine.process("fragments/guest-queue", Set.of("guestQueue"), context);

        assertThat(fragment).contains("data-guest-queue-refresh", "Odśwież", "Teraz gra", "Wilki - Baśka", "First")
                .doesNotContain("??", "<html");

        context.setVariable("guestQueue", null);
        assertThat(engine.process("fragments/guest-queue", Set.of("guestQueue"), context).strip()).isEmpty();
    }

    @Test
    void anEmptyQueue_showsNoList() {
        String html = render(PL, Map.of());

        assertThat(html).doesNotContain("id=\"upNext\"", "id=\"nowPlaying\"", "id=\"myPosition\"");
    }

    @Test
    void aMoodSentAsASong_comesBackWithItsText_andTheMoodModeChosen() {
        String html = render(PL, Map.of("lastRequest", "coś do tańca", "suggestedMode", "MOOD",
                "errorMessage", "To wygląda na opis nastroju"));

        assertThat(tag(html, "modeMood")).contains("checked");
        assertThat(tag(html, "modeSong")).doesNotContain("checked");
        assertThat(tag(html, "songInput")).contains("value=\"coś do tańca\"");
        assertThat(html).contains("To wygląda na opis nastroju");
    }
}
