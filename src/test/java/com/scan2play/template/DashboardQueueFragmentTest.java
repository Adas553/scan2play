package com.scan2play.template;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real polled {@code <tbody>} of the active queue ({@code dashboard.html :: songTableBody}) with the real
 * message bundles. The dashboard is behind a login, so this is what catches a broken expression, a missing message key
 * or an unescaped song name; the links ({@code @{...}}) need a web context, hence the mock servlet objects.
 */
class DashboardQueueFragmentTest {

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

    private static SongRequestEntity accepted(int i, String songName) {
        return SongRequestEntity.builder().id((long) i).partyCode("ABC12").songName(songName).style("Pop")
                .decision("accepted").djComment("ok").energyLevel(7).requestedAt(java.time.LocalDateTime.of(2026, 9, 29, 20, i % 60).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant())
                .trackUrl("https://www.youtube.com/watch?v=hTWKbfoikeg").build();
    }

    private static String render(List<SongRequestEntity> queue, Locale locale) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        context.setVariable("history", queue);
        context.setVariable("playbackMode", PlaybackMode.AUTO);
        context.setVariable("activeProvider", MusicProviderType.YOUTUBE);
        context.setVariable("isSpotifyConnected", false);
        return engine.process("dashboard", Set.of("songTableBody"), context);
    }

    @Test
    @DisplayName("the polled tbody keeps its id and the Auto-Pilot mode the player script reads")
    void shouldKeepTheHooksOfTheScripts() {
        String html = render(List.of(accepted(1, "Alpha")), Locale.ENGLISH);

        assertThat(html).contains("id=\"song-list\"", "data-playback-mode=\"AUTO\"", "data-provider=\"YOUTUBE\"");
        assertThat(html).containsPattern("<tr[^>]*data-song-id=\"1\"[^>]*data-song-name=\"Alpha\"");
    }

    @Test
    @DisplayName("every row carries its song name for the search box; a \"nothing matches\" row is there, hidden")
    void shouldMarkRowsForTheSearch() {
        String html = render(List.of(accepted(1, "Alpha"), accepted(2, "Żółć")), Locale.ENGLISH);

        assertThat(html).contains("data-song-name=\"Alpha\"", "data-song-name=\"Żółć\"");
        assertThat(html).containsPattern("<tr data-nomatch[^>]*class=\"d-none\"");
        assertThat(html).contains("Nothing matches.");
    }

    @Test
    @DisplayName("a song name is never rendered as HTML, not even inside an attribute (it comes from guests)")
    void shouldEscapeTheSongName() {
        String html = render(List.of(accepted(1, "\"><script>alert(1)</script>")), Locale.ENGLISH);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("an empty queue says so, and has no song rows for the search to work on")
    void shouldRenderAnEmptyQueue() {
        String html = render(List.of(), Locale.ENGLISH);

        assertThat(html).contains("Queue is empty!");
        assertThat(html).doesNotContain("data-song-id");
    }

    /**
     * One source of a video's id (review 3.5): the server reads it from the track's URL (YouTubeUrls.extractVideoId) and the row
     * and its ▶ link carry it — the scripts no longer parse URLs. A search link has no video, so no attribute.
     */
    @Test
    @DisplayName("a row and its ▶ link carry the video's id from the server; a search link carries none")
    void shouldCarryTheVideoIdOfTheServer() {
        SongRequestEntity searchLink = accepted(2, "Beta");
        searchLink.setTrackUrl("https://www.youtube.com/results?search_query=Beta");

        String html = render(List.of(accepted(1, "Alpha"), searchLink), Locale.ENGLISH);

        String alpha = html.substring(html.indexOf("data-song-id=\"1\""), html.indexOf("data-song-id=\"2\""));
        String beta = html.substring(html.indexOf("data-song-id=\"2\""));
        assertThat(alpha.split("data-video-id=\"hTWKbfoikeg\"", -1)).as("the row and its link").hasSize(3);
        assertThat(beta.substring(0, beta.indexOf("</tr>"))).doesNotContain("data-video-id");
        // ↗ beside ▶: only a look at the video on YouTube, in a new tab — for a video, not for a search link
        assertThat(alpha).contains("youtube-preview", "Preview on YouTube", "the song leaves the queue");
        assertThat(beta.substring(0, beta.indexOf("</tr>"))).doesNotContain("youtube-preview");
    }

    @Test
    @DisplayName("Polish text of the \"nothing matches\" row, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(List.of(accepted(1, "Alpha")), PL);

        assertThat(html).contains("Nic nie pasuje.");
        assertThat(html).doesNotContain("??");
    }
}
