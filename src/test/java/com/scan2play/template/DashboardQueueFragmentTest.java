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
    @DisplayName("each row has its votes, sortable as a number; a song of more than one guest stands out")
    void shouldShowTheVotes() {
        SongRequestEntity wanted = accepted(1, "Wanted");
        wanted.setVotes(12);
        String html = render(List.of(wanted, accepted(2, "Alone")), PL);

        assertThat(html).doesNotContain("??");
        String first = html.substring(html.indexOf("data-song-id=\"1\""), html.indexOf("data-song-id=\"2\""));
        assertThat(first).contains("data-sort-value=\"votes\" data-val=\"12\"").containsPattern("text-bg-warning\">12<");
        assertThat(html.substring(html.indexOf("data-song-id=\"2\""))).contains("data-val=\"1\"").containsPattern("text-bg-secondary\">1<");
    }

    @Test
    @DisplayName("the guest's own words under the song when they say something else — escaped; none when they are the song's name or missing")
    void shouldShowTheGuestsWords_whenTheySaySomethingElse() {
        SongRequestEntity shrek = accepted(1, "Smash Mouth - All Star");
        shrek.setGuestText("ta z Shreka <b>na wesele</b>");
        SongRequestEntity same = accepted(2, "Wilki - Baśka");
        same.setGuestText("wilki baśka");
        String html = render(List.of(shrek, same, accepted(3, "DJ pick")), PL);

        assertThat(html).contains("gość napisał: „ta z Shreka &lt;b&gt;na wesele&lt;/b&gt;”");
        assertThat(html.split("guest-text", -1)).as("only the first row has the line").hasSize(2);
    }

    @Test
    @DisplayName("Polish text of the \"nothing matches\" row, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(List.of(accepted(1, "Alpha")), PL);

        assertThat(html).contains("Nic nie pasuje.");
        assertThat(html).doesNotContain("??");
    }
}
