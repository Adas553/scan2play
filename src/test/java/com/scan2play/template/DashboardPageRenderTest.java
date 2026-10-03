package com.scan2play.template;

import com.scan2play.config.SecurityConfig;
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
import com.scan2play.service.YouTubeSearchBudget;
import com.scan2play.service.GuestRequestLimiter;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        return renderDashboard(settings, queue, locale, new GuestRequestLimiter(30, 10, 300, ""));
    }

    private static String renderDashboard(PartySettingsEntity settings, List<SongRequestEntity> queue, Locale locale,
                                          GuestRequestLimiter limiter) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        DjService djService = mock(DjService.class);
        QrCodeService qrCodeService = mock(QrCodeService.class);
        when(sessionHelper.getPartySettings(any(), any())).thenReturn(settings);
        when(djService.getDashboardQueue(PARTY)).thenReturn(queue);
        when(qrCodeService.generateQrCodeBase64(anyString(), anyInt(), anyInt())).thenReturn(null);

        DjDashboardController controller = new DjDashboardController(djService, mock(PartySettingsQueryService.class),
                qrCodeService, sessionHelper, mock(NextTrackService.class), mock(PlayerLeaseService.class),
                mock(PlayHistoryService.class), limiter, mock(YouTubeSearchBudget.class));   // not spent
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

    /** The use of each server limit is a badge the DJ notices: grey, yellow from 80 %, red at the limit. */
    @Test
    void theUseOfTheServerLimits_standsOut_andTurnsYellowThenRed() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(30, 10, 300, "");
        for (int i = 0; i < 24; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        String html = renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), PL, limiter);
        assertThat(badge(html, "network")).contains("text-bg-warning").endsWith(">24/30");
        assertThat(badge(html, "party")).contains("text-bg-secondary").endsWith(">24/300");

        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        html = renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), PL, limiter);
        assertThat(badge(html, "network")).contains("text-bg-danger").endsWith(">30/30");
    }

    /** The badge of one limit's use, from its opening tag to its text. */
    private static String badge(String html, String limit) {
        int at = html.indexOf("data-limit-use=\"" + limit + "\"");
        assertThat(at).as("the badge of " + limit).isPositive();
        int start = html.lastIndexOf("<span", at);
        return html.substring(start, html.indexOf("</span>", at));
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
                .djComment("ok").energyLevel(7).requestedAt(java.time.LocalDateTime.of(2026, 9, 29, 20, 0, (int) id).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant())
                .trackUrl("https://www.youtube.com/watch?v=" + videoId).build();
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }

    /** The things every page the browser tests use must have: without them the scripts do nothing at all. */
    /**
     * The page runs under a Content-Security-Policy without {@code 'unsafe-inline'} for scripts (review 5.1): no {@code <script>}
     * without {@code src}, no {@code on…=} attribute.
     */
    static void assertNothingInline(String html) {
        assertThat(java.util.regex.Pattern.compile("<script(?![^>]*\\ssrc=)[^>]*>").matcher(html).find())
                .as("an inline <script>").isFalse();
        assertThat(java.util.regex.Pattern.compile("\\son[a-z]+\\s*=", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("an inline on…= handler").isFalse();
    }

    private static void assertWhatTheScriptsNeed(String html, String autoPilot) {
        assertThat(html).contains("id=\"playerPreviousBtn\"", "id=\"playerPauseBtn\"", "id=\"playerNextBtn\"",
                "id=\"playerBackBtn\"", "id=\"playerRestartBtn\"", "id=\"yt-player\"", "id=\"playerLeaseBanner\"", "id=\"fallbackQueue\"", "id=\"fallbackInput\"");
        assertThat(html).contains("id=\"partyCode\"", "value=\"" + PARTY + "\"");
        assertThat(html).contains("name=\"_csrf\" content=\"harness-csrf-token\"", "name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"");
        assertThat(html).contains("id=\"song-list\"", "data-playback-mode=\"" + autoPilot + "\"");
        // the lists and the tabs (dashboard.js): the queue's search box, count and "nothing matches" row, the tab bar, the two panels
        assertThat(html).contains("id=\"queueList\"", "data-list-search", "data-list-count", "data-nomatch", "id=\"queue-content\"",
                "id=\"history-content\"", "id=\"djTabBar\"", "data-dj-tab=\"panel\"", "data-dj-tab=\"queue\"", "data-dj-tab=\"history\"");
        // the warnings of the server's guest limits (dashboard.js, applyGuestLimits) and the line with the limits
        assertThat(html).contains("id=\"guestLimitWarnings\"", "data-guest-limit=\"search-spent\"", "data-guest-limit=\"party-full\"",
                "id=\"serverLimitsInfo\"");
        // the scripts are ES modules (REVIEW.md 3.3): main.js imports the dashboard's parts, the player comes after it
        assertThat(html).contains("<script type=\"module\" src=\"/js/dashboard/main.js\">",
                "<script type=\"module\" src=\"/js/youtube-autopilot.js\">");
        assertThat(html.indexOf("/js/dashboard/main.js")).as("the dashboard's modules go before the player")
                .isLessThan(html.indexOf("/js/youtube-autopilot.js"));
        // no inline handler calls a global function any more: the modules attach their listeners (a step towards a CSP, 5.1)
        assertThat(html).doesNotContain("submitAutoPilotToggle", "stopFallbackPlaylist", "toggleFallbackShuffle", "copyPartyLink(");
        assertNothingInline(html);
        // what came out of the inline scripts: the scroll memory, the DJ navigation (feedback, confirm), the vibe select
        assertThat(html).contains("<script src=\"/js/scroll-restore.js\">", "<script src=\"/js/dj-nav.js\">", "data-auto-submit",
                "data-confirm=\"");
        assertThat(html).doesNotContain("??");   // a message key that no bundle has renders as ??key_pl??
    }

    @Test
    @DisplayName("a YouTube party with Auto-Pilot on and a playlist: the page the scripts need (written to target/browser-harness/dashboard.html)")
    void shouldRenderTheDashboardOfAPartyWithAutoPilotOn() throws IOException {
        String html = renderDashboard(youTubeParty(PlaybackMode.AUTO, "https://www.youtube.com/playlist?list=" + PLAYLIST),
                List.of(song(1, "Song One", "aaaaaaaaaaA"), song(2, "Song Two", "bbbbbbbbbbB")), PL);

        assertWhatTheScriptsNeed(html, "AUTO");
        assertThat(html).contains("Song One", "Song Two");
        assertThat(html).as("the time of a request: the clock, the day under it, the full moment in the title")
                .contains("title=\"29.09.2026 20:00:01\"", ">20:00</span>", ">29.09</span>").doesNotContain(">29.09.2026 20:00:01<");
        assertThat(html).as("the app's name above the page's heading").contains("🎵 Scan2Play", "<h1 class=\"h4 mb-0 text-secondary\">Panel DJ-a</h1>");
        assertThat(html).as("the queue sorts by votes, the most wanted first").contains("<th data-sort=\"votes\" data-sort-first=\"desc\"", ">Głosy<");
        assertThat(html).as("the footer's YouTube API attribution").contains("YouTube API Services");
        assertThat(html).contains(PLAYLIST);
        // on a phone the settings, the vibe, the background playlist and the QR code fold under one button; the Auto-Pilot switch
        // stays (app.css)
        assertThat(html).contains("id=\"settingsToggle\"");
        assertThat(html.split("s2p-phone-settings", -1).length - 1).as("the folded parts: vibe, the kind of party, limits, playlist, QR code").isEqualTo(5);
        write("dashboard.html", html);
    }

    @Test
    @DisplayName("a Spotify party, connected: the queue with Played / push to Spotify, no YouTube player (written to target/browser-harness/dashboard-spotify.html)")
    void shouldRenderTheDashboardOfASpotifyParty() throws IOException {
        PartySettingsEntity party = youTubeParty(PlaybackMode.MANUAL, null);
        party.setActiveProvider(MusicProviderType.SPOTIFY);
        party.setSpotifyAccessToken("token");
        SongRequestEntity waiting = song(1, "Daft Punk - One More Time", "unused");
        waiting.setTrackUrl("spotify:track:0DiWol3AO6WpXZgp0goxAV");
        String html = renderDashboard(party, List.of(waiting), PL);

        // the Spotify icon beside what comes from Spotify (its Developer Policy): on the track's link and on the push button
        assertThat(html.split("class=\"s2p-spotify-icon\"", -1).length - 1).as("the Spotify icons").isEqualTo(2);
        assertThat(html).contains("aria-label=\"Spotify\"", "</svg> SPOTIFY").doesNotContain("▶ SPOTIFY", "🎵 <span");
        assertThat(html).contains("action=\"/dj/dashboard/play\"", "action=\"/dj/requests/1/push-to-spotify\"",
                "id=\"autoToggle\"", "id=\"settingsToggle\"");
        assertThat(html).doesNotContain("id=\"yt-player\"", "/js/youtube-autopilot.js", "id=\"fallbackQueue\"");
        assertThat(html).contains("id=\"partyCode\"", "id=\"song-list\"", "data-provider=\"SPOTIFY\"", "id=\"queueList\"",
                "<script type=\"module\" src=\"/js/dashboard/main.js\">");
        assertNothingInline(html);
        assertThat(html).doesNotContain("??");
        write("dashboard-spotify.html", html);
    }

    @Test
    @DisplayName("a requests-only party (the DJ plays from their own software): the queue with Played / Skip / Preview, no player (written to target/browser-harness/dashboard-requests.html)")
    void shouldRenderTheDashboardOfARequestsOnlyParty() throws IOException {
        PartySettingsEntity party = youTubeParty(PlaybackMode.MANUAL, null);
        party.setActiveProvider(MusicProviderType.REQUESTS_ONLY);
        SongRequestEntity waiting = song(1, "Wilki - Baśka", "unused");
        waiting.setTrackUrl("https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka");
        waiting.setGuestText("ta o Baśce, co ją Wilki grają");
        String html = renderDashboard(party, List.of(waiting), PL);

        assertThat(html).contains("Twój program DJ-a", "Grasz ze swojego programu");
        // the DJ's vibe note form, and "any" means "the AI judges" here: the guests pick no vibe
        assertThat(html).contains("action=\"/dj/dashboard/vibe-note\"", "id=\"vibeNoteInput\"", "Dowolny (ocenia AI)").doesNotContain("Goście wybierają");
        assertThat(html).contains("gość napisał: „ta o Baśce, co ją Wilki grają”");
        // on a phone the settings, the vibe and the QR code fold under one button, so the queue comes first (app.css)
        assertThat(html).contains("id=\"settingsToggle\"", "⚙️ Ustawienia, klimat i kod QR", "s2p-phone-settings");
        // "Wyczyść kolejkę": the DJ's own queue (no party code in the form), asks first
        assertThat(html).contains("action=\"/dj/dashboard/clear-queue\"", "id=\"clearQueueBtn\"", "🧹 Wyczyść kolejkę",
                "data-confirm=\"Usunąć wszystkie czekające prośby z kolejki?");
        assertThat(html).contains("action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/dismiss\"", ">Pomiń<", "🔍 Podejrzyj",
                "href=\"https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka\"");
        // no player, no Auto-Pilot, no background playlist, no DJ pick: the DJ's own software plays
        assertThat(html).doesNotContain("id=\"yt-player\"", "/js/youtube-autopilot.js", "id=\"autoToggle\"", "id=\"fallbackQueue\"",
                "id=\"dj-pick-form\"", "Powered by YouTube", "🔍 YOUTUBE", "YouTube API Services");
        // what the dashboard's own scripts need: the party, the CSRF token, the polled queue, the lists and the tabs
        assertThat(html).contains("id=\"partyCode\"", "name=\"_csrf\" content=\"harness-csrf-token\"", "id=\"song-list\"",
                "data-provider=\"REQUESTS_ONLY\"", "id=\"queueList\"", "id=\"djTabBar\"",
                "<script type=\"module\" src=\"/js/dashboard/main.js\">");
        assertNothingInline(html);
        assertThat(html).doesNotContain("??");
        write("dashboard-requests.html", html);
    }

    @Test
    @DisplayName("the Content-Security-Policy of the real server (written to target/browser-harness/csp.txt): the stand-in sends it, enforced")
    void shouldWriteThePolicyForTheBrowserTests() throws IOException {
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY).contains("script-src 'self'").doesNotContain("'unsafe-eval'");
        write("csp.txt", SecurityConfig.CONTENT_SECURITY_POLICY);
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
                    "data-text-invalidplaylist=\"", "data-text-notalink=\"", "data-text-apierror=\"", "data-text-noplayabletracks=\"", "data-text-unknown=\"");
            assertThat(html).as(locale.toString()).doesNotContain("??");
        }
        assertThat(renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), Locale.ENGLISH))
                .contains("data-text-ok=\"Playlist saved. Tracks in the queue: {0}.\"");
        assertThat(renderDashboard(youTubeParty(PlaybackMode.AUTO, null), List.of(), PL))
                .contains("data-text-ok=\"Playlista zapisana. Utworów w kolejce: {0}.\"");
    }

    /**
     * When the entry {@code i} of the sample timeline happened, counting back from the newest (1): every entry a minute older than
     * the one before, the first five on Tuesday 29.09, the rest on Monday 28.09 — two days, so the history has two day headings.
     */
    private static Instant historyAt(int i) {
        java.time.LocalDateTime start = i <= 5 ? java.time.LocalDateTime.of(2026, 9, 29, 22, 0) : java.time.LocalDateTime.of(2026, 9, 28, 23, 30);
        return start.atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant().minus(i, ChronoUnit.MINUTES);
    }

    /** A row of the sample timeline: {@code i} counts back from the newest (1), see {@link #historyAt}. */
    private static HistoryEntry historyGuest(int i, String title, String decision) {
        return new HistoryEntry(Source.GUEST, (long) i, historyAt(i), title,
                "https://www.youtube.com/watch?v=g" + String.format("%010d", i), "g" + String.format("%010d", i), "Pop", decision,
                "ok", 5 + i % 5, null, i == 5 ? 12 : i == 8 ? 3 : 1);   // the votes: a ranking to sort (12 before 3 — as numbers)
    }

    private static HistoryEntry historyBackground(int i, String title) {
        return new HistoryEntry(Source.BACKGROUND, (long) i, historyAt(i), title,
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
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);   // a YouTube party: the sample has background tracks
        when(sessionHelper.getPartySettings(any(), any())).thenReturn(youTubeParty(PlaybackMode.AUTO, null));
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsQueryService.class),
                mock(QrCodeService.class), sessionHelper, mock(NextTrackService.class), mock(PlayerLeaseService.class), history,
                mock(GuestRequestLimiter.class), mock(YouTubeSearchBudget.class));

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
            if (filter == HistoryFilter.ALL) {
                // a heading where a new day starts (Polish weekday names: the fragment is rendered in Polish)
                assertThat(first.split("data-day-heading", -1).length - 1).as("the first page: one day").isEqualTo(1);
                assertThat(more.split("data-day-heading", -1).length - 1).as("the whole timeline: two days").isEqualTo(2);
                assertThat(more).contains("wtorek, 29.09", "poniedziałek, 28.09");
                assertThat(more.indexOf("wtorek, 29.09")).isLessThan(more.indexOf("poniedziałek, 28.09"));
            }
            write("history-" + filter.param() + ".html", first);
            write("history-" + filter.param() + "-more.html", more);
        }
    }

    @Test
    @DisplayName("the \"up next\" list as the dashboard fetches it: four tracks, one skipped in this round (written to target/browser-harness/fallback-queue.html)")
    void shouldRenderTheUpNextListForTheBrowserTests() throws IOException {
        ConcurrentModel model = new ConcurrentModel();
        model.addAttribute("queue", FallbackQueueView.builder().hasPlaylist(true).remaining(4).skipped(1).tracks(List.of(
                // titles as a real playlist has them — long ones, with an ampersand, Polish letters — so that a look at the page shows how a row copes
                new FallbackQueueView.Track(11L, "aaaaaaaaaaA", "Warren - Ordinary (Official Video)"),
                new FallbackQueueView.Track(12L, "bbbbbbbbbbB", "Justin Bieber - DAISIES (Audio)"),
                new FallbackQueueView.Track(13L, "cccccccccCc", "MAZUREK & STANOWSKI #117: WAŁĘSA ANALFABETĄ, CZARZASTY W POLU, POKAZ MODY W MINISTERSTWIE"),
                new FallbackQueueView.Track(14L, "ddddddddddD", "Short one"))).build());
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
