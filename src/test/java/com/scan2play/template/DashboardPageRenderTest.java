package com.scan2play.template;

import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.model.HistoryFilter;
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
        String view = controller.dashboard(model, ownerToken(), new MockHttpSession());
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

    private static OAuth2AuthenticationToken ownerToken() {
        return new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
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
        // the lists and the tabs (dashboard.js): the queue's search box, count and "nothing matches" row, the tab bar, the two panels
        assertThat(html).contains("id=\"queueList\"", "data-list-search", "data-list-count", "data-nomatch", "id=\"queue-content\"",
                "id=\"history-content\"", "id=\"djTabBar\"", "data-dj-tab=\"panel\"", "data-dj-tab=\"queue\"", "data-dj-tab=\"history\"");
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
    @DisplayName("the same party in English (written to target/browser-harness/dashboard-en.html): the page says which language it is")
    void shouldRenderTheDashboardInEnglish() throws IOException {
        String html = renderDashboard(youTubeParty(PlaybackMode.AUTO, "https://www.youtube.com/playlist?list=" + PLAYLIST),
                List.of(song(1, "Song One", "aaaaaaaaaaA")), Locale.ENGLISH);

        assertWhatTheScriptsNeed(html, "AUTO");
        assertThat(html).contains("data-text-ok=\"Playlist saved. Tracks in the queue: {0}.\"");
        write("dashboard-en.html", html);
    }

    @Test
    @DisplayName("<html lang> is the language of the texts: the bundle that wrote them says it, so they cannot disagree (a locale without a bundle gets the English one)")
    void shouldDeclareTheLanguageOfTheTexts() {
        PartySettingsEntity party = youTubeParty(PlaybackMode.AUTO, null);

        assertThat(renderDashboard(party, List.of(), PL)).contains("<html lang=\"pl\">");
        assertThat(renderDashboard(party, List.of(), Locale.ENGLISH)).contains("<html lang=\"en\">");
        // A German browser: there is no German bundle, the texts are the English ones — and so is the declared language
        // (a `${#locale.language}` would have said "de" over English texts)
        String german = renderDashboard(party, List.of(), Locale.GERMAN);
        assertThat(german).contains("data-text-ok=\"Playlist saved. Tracks in the queue: {0}.\"", "<html lang=\"en\">");
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

    /** A row of the sample timeline: {@code i} counts back from the newest (1) — every entry is a minute older than the one before. */
    private static HistoryEntry historyGuest(int i, String title, String decision) {
        return new HistoryEntry(Source.GUEST, (long) i, LocalDateTime.of(2026, 9, 29, 22, 0).minusMinutes(i), title,
                "https://www.youtube.com/watch?v=g" + String.format("%010d", i), "g" + String.format("%010d", i), "Pop", decision,
                "ok", 5 + i % 5);
    }

    private static HistoryEntry historyBackground(int i, String title) {
        return new HistoryEntry(Source.BACKGROUND, (long) i, LocalDateTime.of(2026, 9, 29, 22, 0).minusMinutes(i), title,
                "https://www.youtube.com/watch?v=b" + String.format("%010d", i), "b" + String.format("%010d", i), null, "played", null, null);
    }

    /** What the real server does with a filter: the entries whose kind the filter includes (the flags are the real enum's). */
    private static boolean belongsTo(HistoryEntry entry, HistoryFilter filter) {
        if (entry.source() == Source.BACKGROUND) {
            return filter.includesBackgroundTracks();
        }
        return "rejected".equals(entry.decision()) ? filter.includesGuestsRejected() : filter.includesGuestsPlayed();
    }

    /** The History tab's fragment as {@code DjDashboardController.historyFragment} builds it for what the service answers, rendered in Polish. */
    private static String renderHistory(HistoryFilter filter, int limit, List<HistoryEntry> entries, boolean hasMore) {
        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(PARTY, limit, filter)).thenReturn(new PlayHistoryService.Page(entries, hasMore));
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsQueryService.class),
                mock(QrCodeService.class), mock(DjSessionHelper.class), mock(NextTrackService.class), mock(PlayerLeaseService.class), history);

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.historyFragment(PARTY, limit, filter.param(), model, ownerToken(), new MockHttpSession());
        assertThat(view).isEqualTo("history :: historyTableContent");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());
        return engine.process("history", Set.of("historyTableContent"), context);
    }

    @Test
    @DisplayName("the History tab as the dashboard fetches it: a first page and a longer one (\"Show more\") for each filter, through the real controller (written to target/browser-harness/history-<filter>[-more].html)")
    void shouldRenderTheHistoryForTheBrowserTests() throws IOException {
        // ten entries on one timeline, newest first: guests' songs that played or were rejected, and tracks of the playlist
        List<HistoryEntry> timeline = List.of(
                historyGuest(1, "Żółć — piosenka", "played"), historyBackground(2, "Playlist Alpha"), historyGuest(3, "Rejected Beat", "rejected"),
                historyBackground(4, "Playlist Bravo"), historyGuest(5, "Guest Charlie", "played"), historyGuest(6, "Rejected Delta", "rejected"),
                historyBackground(7, "Playlist Echo"), historyGuest(8, "Guest Foxtrot", "played"), historyGuest(9, "Rejected Golf", "rejected"),
                historyBackground(10, "Playlist Hotel"));
        int firstPage = 4;   // a "page" of the sample is four entries, so that a short list has something to show more of

        for (HistoryFilter filter : HistoryFilter.values()) {
            List<HistoryEntry> ofKind = timeline.stream().filter(entry -> belongsTo(entry, filter)).toList();
            boolean older = ofKind.size() > firstPage;
            // the controller asks for a page of 50, and "Show more" for the 100 that its button carries (data-limit)
            String first = renderHistory(filter, 50, ofKind.subList(0, Math.min(firstPage, ofKind.size())), older);
            String more = renderHistory(filter, 100, ofKind, false);

            assertThat(first).as(filter.param()).contains("data-list-filter=\"" + filter.param() + "\"").doesNotContain("??");
            assertThat(first.contains("data-history-more")).as("%s: a button to show more only where older entries exist", filter.param()).isEqualTo(older);
            if (older) {
                assertThat(first).contains("data-limit=\"100\"");
            }
            assertThat(more).as(filter.param() + " (more)").doesNotContain("data-history-more").doesNotContain("??");
            write("history-" + filter.param() + ".html", first);
            write("history-" + filter.param() + "-more.html", more);
        }
    }

    @Test
    @DisplayName("the \"up next\" list as the dashboard fetches it: four tracks, one skipped in this round (written to target/browser-harness/fallback-queue.html)")
    void shouldRenderTheUpNextListForTheBrowserTests() throws IOException {
        ConcurrentModel model = new ConcurrentModel();
        model.addAttribute("queue", new FallbackQueueView(true, false, false, 4, 1, List.of(
                // titles as a real playlist has them — long ones, with an ampersand, Polish letters — so that a look at the page shows how a row copes
                new FallbackQueueView.Track(11L, "aaaaaaaaaaA", "Warren - Ordinary (Official Video)"),
                new FallbackQueueView.Track(12L, "bbbbbbbbbbB", "Justin Bieber - DAISIES (Audio)"),
                new FallbackQueueView.Track(13L, "cccccccccCc", "MAZUREK & STANOWSKI #117: WAŁĘSA ANALFABETĄ, CZARZASTY W POLU, POKAZ MODY W MINISTERSTWIE"),
                new FallbackQueueView.Track(14L, "ddddddddddD", "Short one"))));
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
