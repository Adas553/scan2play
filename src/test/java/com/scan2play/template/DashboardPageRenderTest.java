package com.scan2play.template;

import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.service.NextTrackService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PlayerLeaseService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renders the real {@code dashboard.html} — the whole page, with the real message bundles and the model that the real
 * {@link DjDashboardController#dashboard} puts together (its services are mocks) — and checks what the page's scripts depend
 * on: the ids of the buttons, the party code, the CSRF meta tags, the Auto-Pilot flag, the order of the scripts.
 * <p>
 * It is an ordinary unit test that also leaves its result in {@code target/browser-harness/}: the browser tests
 * ({@code src/test/browser}, see its README) serve those files and run the real scripts on them. Nothing here needs a browser.
 * <ul>
 *   <li>{@code dashboard.html} — a YouTube party in Polish, Auto-Pilot on, a playlist saved, two songs in the queue;</li>
 *   <li>{@code dashboard-manual.html} — a brand-new YouTube party: Auto-Pilot off (what a new party starts with), no playlist.</li>
 * </ul>
 */
class DashboardPageRenderTest {

    private static final String PARTY = "HARN1";
    private static final String PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";
    private static final Path OUT = Path.of("target", "browser-harness");
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

    /** What the controller does for a DJ with these settings, rendered like the view resolver would. */
    private static String renderDashboard(PartySettingsEntity settings, List<SongRequestEntity> queue, Locale locale) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        DjService djService = mock(DjService.class);
        QrCodeService qrCodeService = mock(QrCodeService.class);
        when(sessionHelper.getPartySettings(any(), any())).thenReturn(settings);
        when(djService.getDashboardQueue(PARTY)).thenReturn(queue);
        when(qrCodeService.generateQrCodeBase64(anyString(), anyInt(), anyInt())).thenReturn(null);

        DjDashboardController controller = new DjDashboardController(djService, mock(PartySettingsQueryService.class),
                qrCodeService, sessionHelper, mock(NextTrackService.class), mock(PlayerLeaseService.class),
                mock(PlayHistoryService.class));
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "http://localhost:8080/");
        controller.init();

        ConcurrentModel model = new ConcurrentModel();
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        String view = controller.dashboard(model, token, new MockHttpSession());
        assertThat(view).isEqualTo("dashboard");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        context.setVariables(model.asMap());
        // what Spring Security's CsrfRequestDataValueProcessor / the `_csrf` request attribute give the real page
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        return engine.process(view, context);
    }

    private static PartySettingsEntity youTubeParty(PlaybackMode mode, String playlistUrl) {
        return PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).globalVibe(VibeType.ANY)
                .activeProvider(MusicProviderType.YOUTUBE).playbackMode(mode).fallbackPlaylistUrl(playlistUrl)
                .fallbackShuffle(false).build();
    }

    private static SongRequestEntity song(long id, String name, String videoId) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).style("Pop").decision("accepted")
                .djComment("ok").energyLevel(7).requestedAt(LocalDateTime.of(2026, 9, 29, 20, 0, (int) id))
                .trackUrl("https://www.youtube.com/watch?v=" + videoId).build();
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }

    /** The things every page the browser tests use must have: without them the scripts do nothing at all. */
    private static void assertWhatTheScriptsNeed(String html, String autoPilot) {
        assertThat(html).contains("id=\"playerPreviousBtn\"", "id=\"playerPauseBtn\"", "id=\"playerNextBtn\"",
                "id=\"playerBackBtn\"", "id=\"playerRestartBtn\"", "id=\"yt-player\"", "id=\"playerLeaseBanner\"", "id=\"fallbackQueue\"", "id=\"fallbackInput\"");
        assertThat(html).contains("id=\"partyCode\"", "value=\"" + PARTY + "\"");
        assertThat(html).contains("name=\"_csrf\" content=\"harness-csrf-token\"", "name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"");
        assertThat(html).contains("id=\"song-list\"", "data-playback-mode=\"" + autoPilot + "\"");
        assertThat(html).contains("/js/dashboard.js", "/js/youtube-autopilot.js");
        assertThat(html.indexOf("/js/dashboard.js")).as("dashboard.js goes before youtube-autopilot.js")
                .isLessThan(html.indexOf("/js/youtube-autopilot.js"));
        assertThat(html).doesNotContain("??");   // a message key that no bundle has renders as ??key_pl??
    }

    @Test
    @DisplayName("a YouTube party with Auto-Pilot on and a playlist: the page the scripts need (written to target/browser-harness/dashboard.html)")
    void shouldRenderTheDashboardOfAPartyWithAutoPilotOn() throws IOException {
        String html = renderDashboard(youTubeParty(PlaybackMode.AUTO, "https://www.youtube.com/playlist?list=" + PLAYLIST),
                List.of(song(1, "Song One", "aaaaaaaaaaA"), song(2, "Song Two", "bbbbbbbbbbB")), PL);

        assertWhatTheScriptsNeed(html, "AUTO");
        assertThat(html).contains("Song One", "Song Two");
        assertThat(html).contains(PLAYLIST);
        write("dashboard.html", html);
    }

    @Test
    @DisplayName("a new YouTube party (Auto-Pilot off, no playlist): the page the scripts need (written to target/browser-harness/dashboard-manual.html)")
    void shouldRenderTheDashboardOfANewParty() throws IOException {
        String html = renderDashboard(youTubeParty(PlaybackMode.MANUAL, null), List.of(), PL);

        assertWhatTheScriptsNeed(html, "MANUAL");
        assertThat(html).doesNotContain(PLAYLIST);
        write("dashboard-manual.html", html);
    }

    @Test
    @DisplayName("the box that says how the playlist import went carries all its texts, in both languages (dashboard.js showFallbackImportResult)")
    void shouldCarryTheTextsOfTheImportResult() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            String html = renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), locale);

            assertThat(html).as(locale.toString()).contains("id=\"fallbackImportStatus\"", "data-text-ok=\"", "data-text-noapikey=\"",
                    "data-text-invalidplaylist=\"", "data-text-apierror=\"", "data-text-noplayabletracks=\"", "data-text-unknown=\"");
            assertThat(html).as(locale.toString()).doesNotContain("??");
        }
        assertThat(renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), Locale.ENGLISH))
                .contains("data-text-ok=\"Playlist saved. Tracks in the queue: {0}.\"");
        assertThat(renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), PL))
                .contains("data-text-ok=\"Playlista zapisana. Utworów w kolejce: {0}.\"");
    }

    @Test
    @DisplayName("the \"up next\" list as the dashboard fetches it: four tracks, one skipped in this round (written to target/browser-harness/fallback-queue.html)")
    void shouldRenderTheUpNextListForTheBrowserTests() throws IOException {
        ConcurrentModel model = new ConcurrentModel();
        model.addAttribute("queue", new FallbackQueueView(true, false, false, 4, 1, List.of(
                new FallbackQueueView.Track(11L, "aaaaaaaaaaA", "Old A"), new FallbackQueueView.Track(12L, "bbbbbbbbbbB", "Old B"),
                new FallbackQueueView.Track(13L, "cccccccccCc", "Old C"), new FallbackQueueView.Track(14L, "ddddddddddD", "Old D"))));
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());

        String html = engine.process("fragments/fallback-queue", Set.of("queue"), context);

        assertThat(html).contains("data-track-id=\"11\"", "data-track-id=\"14\"", "data-skip", "data-move=\"TOP\"");
        assertThat(html).doesNotContain("??");
        write("fallback-queue.html", html);
    }
}
