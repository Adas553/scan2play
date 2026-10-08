package com.scan2play.template;

import com.scan2play.config.SecurityConfig;
import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryFilter;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.GuestRequestLimiter;
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
 * on: the ids of the buttons, the party code, the CSRF meta tags, the scripts.
 * <p>
 * It is an ordinary unit test that also leaves its result in {@code target/browser-harness/}: the browser tests
 * ({@code src/test/browser}, see its README) serve those files and run the real scripts on them. Nothing here needs a browser.
 * <ul>
 *   <li>{@code dashboard.html} — the party in Polish, two requests in the queue;</li>
 *   <li>{@code dashboard-en.html} — the same party in English;</li>
 *   <li>{@code history-<filter>[-more].html} — the History tab's fragment;</li>
 *   <li>{@code csp.txt} — the real Content-Security-Policy.</li>
 * </ul>
 */
class DashboardPageRenderTest {

    private static final String PARTY = "HARN1";
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
                qrCodeService, sessionHelper, mock(PlayHistoryService.class), limiter, pushWithKey());
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
        String html = renderDashboard(party(), List.of(), PL, limiter);
        assertThat(badge(html, "network")).contains("text-bg-warning").endsWith(">24/30");
        assertThat(badge(html, "party")).contains("text-bg-secondary").endsWith(">24/300");

        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        html = renderDashboard(party(), List.of(), PL, limiter);
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

    /**
     * Notifications on the server, with a public key of the right shape (made here): the page offers the switch "🔔 Powiadomienia
     * na tym urządzeniu" (the browser scenario {@code push}).
     */
    private static PushNotificationService pushWithKey() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            PushNotificationService push = mock(PushNotificationService.class);
            when(push.publicKey()).thenReturn(com.scan2play.VapidKeyGenerator.publicKeyBase64Url(
                    (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic()));
            return push;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A party of the product: the DJ plays from their own software, the guests' requests wait on the dashboard. */
    private static PartySettingsEntity party() {
        return PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).globalVibe(VibeType.ANY)
                .instagramUrl("https://www.instagram.com/dj.koko/").build();
    }

    /** A waiting request with its "🔍 Podejrzyj" link: YouTube's search results for the song's name. */
    private static SongRequestEntity song(long id, String name) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).style("Pop").decision("accepted")
                .djComment("ok").energyLevel(7).requestedAt(java.time.LocalDateTime.of(2026, 9, 29, 20, 0, (int) id).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant())
                .trackUrl("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)).build();
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }

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

    /** The things every page the browser tests use must have: without them the scripts do nothing at all. */
    private static void assertWhatTheScriptsNeed(String html) {
        assertThat(html).contains("id=\"partyCode\"", "value=\"" + PARTY + "\"");
        assertThat(html).contains("name=\"_csrf\" content=\"harness-csrf-token\"", "name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"");
        assertThat(html).contains("id=\"song-list\"");
        // the lists and the tabs (dashboard.js): the queue's search box, count and "nothing matches" row, the tab bar, the two panels
        assertThat(html).contains("id=\"queueList\"", "data-list-search", "data-list-count", "data-nomatch", "id=\"queue-content\"",
                "id=\"history-content\"", "id=\"djTabBar\"", "data-dj-tab=\"panel\"", "data-dj-tab=\"queue\"", "data-dj-tab=\"history\"");
        // the warnings of the server's guest limits (dashboard.js, applyGuestLimits) and the line with the limits
        assertThat(html).contains("id=\"guestLimitWarnings\"", "data-guest-limit=\"party-full\"", "id=\"serverLimitsInfo\"");
        // the scripts are ES modules (review 3.3): main.js imports the dashboard's parts
        assertThat(html).contains("<script type=\"module\" src=\"/js/dashboard/main.js\">");
        // no player: the DJ's own software plays (the YouTube player, Auto-Pilot, the background playlist and the DJ pick are gone)
        assertThat(html).doesNotContain("id=\"yt-player\"", "/js/youtube-autopilot.js", "/js/wake-lock.js", "id=\"autoToggle\"",
                "id=\"fallbackQueue\"", "id=\"fallbackInput\"", "id=\"dj-pick-form\"", "data-playback-mode", "data-video-id");
        // no inline handler calls a global function any more: the modules attach their listeners (a step towards a CSP, 5.1)
        assertThat(html).doesNotContain("copyPartyLink(");
        assertNothingInline(html);
        // what came out of the inline scripts: the scroll memory, the DJ navigation (feedback, confirm), the vibe select
        assertThat(html).contains("<script src=\"/js/scroll-restore.js\">", "<script src=\"/js/dj-nav.js\">", "data-auto-submit",
                "data-confirm=\"");
        assertThat(html).doesNotContain("??");   // a message key that no bundle has renders as ??key_pl??
    }

    @Test
    @DisplayName("the dashboard: the queue with Played / Skip / Preview, the settings, no player (written to target/browser-harness/dashboard.html)")
    void shouldRenderTheDashboard() throws IOException {
        SongRequestEntity waiting = song(1, "Wilki - Baśka");
        waiting.setGuestText("ta o Baśce, co ją Wilki grają");
        // none of the guest's words in the AI's song: "⚠ Sprawdź" (the browser scenario check-song-phone)
        SongRequestEntity other = song(2, "sanah - Szampan");
        other.setGuestText("orła cień");
        String html = renderDashboard(party(), List.of(waiting, other), PL);

        assertWhatTheScriptsNeed(html);
        assertThat(html).contains("Wilki - Baśka", "sanah - Szampan");
        assertThat(html).contains("Twój program DJ-a", "Grasz ze swojego programu");
        assertThat(html).as("the time of a request: the clock, the day under it, the full moment in the title")
                .contains("title=\"29.09.2026 20:00:01\"", ">20:00</span>", ">29.09</span>").doesNotContain(">29.09.2026 20:00:01<");
        assertThat(html).as("our logo above the page's heading").contains(
                "<span class=\"s2p-logo\"><img src=\"/images/logo.svg\" alt=\"\" class=\"s2p-logo-mark\"><span>Scan<span class=\"s2p-logo-two\">2</span>Play</span></span>",
                "<h1 class=\"h4 mb-0 text-secondary\">Panel DJ-a</h1>");
        assertThat(html).as("the queue sorts by votes, the most wanted first").contains("<th data-sort=\"votes\" data-sort-first=\"desc\"", ">Głosy<");
        // the DJ's vibe note form, and "any" means "the AI judges" here: the guests pick no vibe
        assertThat(html).as("the AI's comment style (V22): the party's own picked, saved as soon as picked")
                .contains("action=\"/dj/dashboard/comment-style\"", "id=\"commentStyleSelect\"", "💬 Komentarze AI:",
                        "selected=\"selected\">Klasyczne<", ">Sarkastyczne (łagodne)<")
                .doesNotContain("data-example", "commentStyleExample");
        assertThat(html).as("the DJ's profiles (V24), a note for a refused one hidden until then")
                .contains("action=\"/dj/dashboard/dj-links\"", "id=\"instagramInput\"", "id=\"facebookInput\"", "id=\"tiktokInput\"",
                        "value=\"https://www.instagram.com/dj.koko/\"", "Twoje profile (goście widzą je", "data-form-error hidden");
        assertThat(html).as("who plays (V17)").contains("action=\"/dj/dashboard/dj-name\"", "id=\"djNameInput\"", "Kto gra (widzą goście)");
        assertThat(html).contains("action=\"/dj/dashboard/vibe-note\"", "id=\"vibeNoteInput\"", "Dowolny (ocenia AI)").doesNotContain("Goście wybierają");
        assertThat(html).contains("gość napisał: „ta o Baśce, co ją Wilki grają”");
        assertThat(html.split(">⚠ Sprawdź<", -1)).as("only the song with none of the guest's words").hasSize(2);
        // on a phone the settings, the vibe and the QR code fold under one button, so the queue comes first (app.css)
        assertThat(html).contains("id=\"settingsToggle\"", "⚙️ Ustawienia, klimat i kod QR");
        assertThat(html.split("s2p-phone-settings", -1).length - 1).as("the folded parts: vibe, the kind of party, limits, QR code").isEqualTo(4);
        // "Wyczyść kolejkę": the DJ's own queue (no party code in the form), asks first
        assertThat(html).contains("action=\"/dj/dashboard/clear-queue\"", "id=\"clearQueueBtn\"", "🧹 Wyczyść kolejkę",
                "data-confirm=\"Usunąć wszystkie czekające prośby z kolejki?");
        assertThat(html).contains("action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/dismiss\"", ">⏭ Pomiń<", "🔍 Podejrzyj",
                "href=\"https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka\"");
        // the request's buttons: "▶ Zagrane" filled and short (one line), "⏭ Pomiń" outlined but readable (the owner, 2026-10-07)
        assertThat(html).containsPattern("class=\"btn btn-sm btn-info text-nowrap s2p-btn-played\"[^>]*>▶ Zagrane<")
                .containsPattern("class=\"btn btn-sm btn-outline-light text-nowrap s2p-btn-skip\"[^>]*>⏭ Pomiń<")
                .containsPattern("class=\"btn btn-sm btn-secondary [^\"]*\"[^>]*>🔍 Podejrzyj<")   // grey, as in the history: red deletes
                .doesNotContain("btn-outline-danger text-danger", "Oznacz jako zagrane", "btn-outline-secondary\" title=\"Nie tę");
        assertThat(html).doesNotContain("Powered by YouTube", "🔍 YOUTUBE", "▶ YOUTUBE", "YouTube API Services");
        // "Cofnij" after "Pomiń": a bar forms.js shows for a few seconds after a skip; hidden until then
        assertThat(html).contains("id=\"undoSkip\"", "Pominięto:", "data-undo-button", ">Cofnij<");
        assertThat(html.substring(html.indexOf("id=\"undoSkip\""), html.indexOf("data-undo-song"))).contains("hidden");
        write("dashboard.html", html);
    }

    @Test
    @DisplayName("the Content-Security-Policy of the real server (written to target/browser-harness/csp.txt): the stand-in sends it, enforced")
    void shouldWriteThePolicyForTheBrowserTests() throws IOException {
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY).contains("script-src 'self'").doesNotContain("'unsafe-eval'", "youtube", "ytimg");
        write("csp.txt", SecurityConfig.CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("the same party in English (written to target/browser-harness/dashboard-en.html): the page says which language it is")
    void shouldRenderTheDashboardInEnglish() throws IOException {
        String html = renderDashboard(party(), List.of(song(1, "Song One")), Locale.ENGLISH);

        assertWhatTheScriptsNeed(html);
        assertThat(html).contains(">⏭ Skip<", ">▶ Played<", "🔍 Preview");
        write("dashboard-en.html", html);
    }

    @Test
    @DisplayName("<html lang> is the language of the texts: the bundle that wrote them says it, so they cannot disagree (a locale without a bundle gets the English one)")
    void shouldDeclareTheLanguageOfTheTexts() {
        assertThat(renderDashboard(party(), List.of(), PL)).contains("<html lang=\"pl\">");
        assertThat(renderDashboard(party(), List.of(), Locale.ENGLISH)).contains("<html lang=\"en\">");
        // A German browser: there is no German bundle, the texts are the English ones — and so is the declared language
        // (a `${#locale.language}` would have said "de" over English texts)
        String german = renderDashboard(party(), List.of(song(1, "Song One")), Locale.GERMAN);
        assertThat(german).contains("🔍 Preview", "<html lang=\"en\">");
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
    private static HistoryEntry historyEntry(int i, String title, String decision) {
        // the 3rd the DJ skipped ("⏭ Pominięta przez DJ-a", "↩ Przywróć"), with the AI's longer comment kept (a phone's card shows it);
        // the 6th the DJ cleared with the queue
        return new HistoryEntry((long) i, historyAt(i), title, "https://www.youtube.com/results?search_query=song" + i, "Pop", decision,
                i == 3 ? "Klasyk wesel, ale parkiet chce dziś czegoś szybszego — może później?" : "ok", 5 + i % 5,
                // the guest's words: the 2nd's are not in its song ("⚠ Sprawdź"), the 4th's are
                i == 2 ? "orła cień" : i == 4 ? "bravo" : null,
                i == 5 ? 12 : i == 8 ? 3 : 1,   // the votes: a ranking to sort (12 before 3 — as numbers)
                i == 3 ? historyAt(i) : null,
                i == 6 ? historyAt(i) : null);   // the 6th cleared with the whole queue ("🧹 Wyczyszczona przez DJ-a", V25)
    }

    /** What the real server does with a filter: the entries whose decision the filter includes (the flags are the real enum's). */
    private static boolean belongsTo(HistoryEntry entry, HistoryFilter filter) {
        return "rejected".equals(entry.decision()) ? filter.includesRejected() : filter.includesPlayed();
    }

    /** The History tab's fragment as {@code DjDashboardController.historyFragment} builds it for what the service answers, rendered in Polish. */
    private static String renderHistory(HistoryFilter filter, int limit, List<HistoryEntry> entries, boolean hasMore) {
        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(PARTY, limit, filter)).thenReturn(new PlayHistoryService.Page(entries, hasMore));
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.getPartySettings(any(), any())).thenReturn(party());
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsQueryService.class),
                mock(QrCodeService.class), sessionHelper, history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class));

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
        // ten entries on one timeline, newest first: songs that played or were rejected
        List<HistoryEntry> timeline = List.of(
                historyEntry(1, "Żółć — piosenka", "played"), historyEntry(2, "Played Alpha", "played"), historyEntry(3, "Rejected Beat", "rejected"),
                historyEntry(4, "Played Bravo", "played"), historyEntry(5, "Guest Charlie", "played"), historyEntry(6, "Rejected Delta", "rejected"),
                historyEntry(7, "Played Echo", "played"), historyEntry(8, "Guest Foxtrot", "played"), historyEntry(9, "Rejected Golf", "rejected"),
                historyEntry(10, "Played Hotel", "played"));
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
}
