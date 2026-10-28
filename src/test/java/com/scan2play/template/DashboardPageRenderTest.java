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
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
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
        com.scan2play.service.PartyStaffService staff = mock(com.scan2play.service.PartyStaffService.class);
        when(staff.staffOf(PARTY)).thenReturn(List.of(com.scan2play.entity.PartyStaffEntity.builder().id(7L).partyCode(PARTY)
                .memberId("kasia").memberName("Kasia").joinedAt(java.time.Instant.now()).build()));
        return renderDashboard(settings, queue, locale, limiter, ownerToken(), staff, new MockHttpSession(),
                new PartyStaffService.Access(settings, true, StaffPermission.all()), null);
    }

    /**
     * The same for {@code user} — the owner, or someone on the party's staff (V30) with what they may do there (V32) — with the staff
     * service given and the note the panel shows ({@code DjSessionHelper.Note}, or null).
     */
    private static String renderDashboard(PartySettingsEntity settings, List<SongRequestEntity> queue, Locale locale,
                                          GuestRequestLimiter limiter, OAuth2AuthenticationToken user,
                                          PartyStaffService staff, MockHttpSession session, PartyStaffService.Access access,
                                          DjSessionHelper.Note note) {
        return renderDashboard(settings, queue, locale, limiter, user, staff, session, access, note, com.scan2play.service.Plans.off());
    }

    /** The same with the plans as {@code plans} decides them (V35). */
    private static String renderDashboard(PartySettingsEntity settings, List<SongRequestEntity> queue, Locale locale,
                                          GuestRequestLimiter limiter, OAuth2AuthenticationToken user,
                                          PartyStaffService staff, MockHttpSession session, PartyStaffService.Access access,
                                          DjSessionHelper.Note note, com.scan2play.service.PlanService plans) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        DjService djService = mock(DjService.class);
        QrCodeService qrCodeService = mock(QrCodeService.class);
        when(sessionHelper.panel(any(), any(), any())).thenReturn(java.util.Optional.of(access));
        when(sessionHelper.takeNote(any())).thenReturn(java.util.Optional.ofNullable(note));
        when(djService.getDashboardQueue(PARTY)).thenReturn(queue);
        when(djService.dedicationsOf(queue)).thenReturn(dedications);
        when(djService.libraryMarksOf(PARTY, queue)).thenReturn(libraryMarks);
        when(djService.libraryOf(PARTY)).thenReturn(library);
        when(qrCodeService.generateQrCodeBase64(anyString(), anyInt(), anyInt())).thenReturn(null);

        DjDashboardController controller = new DjDashboardController(djService, mock(PartySettingsCommandService.class),
                qrCodeService, sessionHelper, mock(PlayHistoryService.class), limiter, pushWithKey(), staff, mock(com.scan2play.service.StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor());
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "http://localhost:8080/");
        controller.init();

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.dashboard(null, model, user, session);
        assertThat(view).isEqualTo("dashboard");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        context.setVariables(model.asMap());
        // what LanguageSwitchAdvice adds to every page's model (the footer's and the menu's "Polski · English")
        context.setVariable("langSwitch", new com.scan2play.controller.LanguageSwitchAdvice.LanguageSwitch(
                "/dj/dashboard?party=" + PARTY + "&lang=pl", "/dj/dashboard?party=" + PARTY + "&lang=en"));
        // what Spring Security's CsrfRequestDataValueProcessor / the `_csrf` request attribute give the real page
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        return engine.process(view, context);
    }

    /**
     * The row "Limity gości" says the guests' own limit — and, from 80 % of a server limit, its use in yellow, the party's before the
     * network's (the design review, 2026-10-10: the use was three lines in a card of its own); above the queue a warning from 80 % of
     * the party's limit, under it ("wyczerpany" takes over at the limit).
     */
    @Test
    void nearAServerLimit_theRowOfTheLimitsAndTheQueueSayIt() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(30, 10, 300, "");
        String calm = renderDashboard(party(), List.of(), PL, limiter);
        assertThat(element(calm, "data-limit-calm")).doesNotContain("hidden").endsWith(">2 na gościa · 3 min");
        assertThat(element(calm, "data-limit-near=\"network\"")).contains("hidden");
        assertThat(element(calm, "data-limit-near=\"party\" data-limit")).contains("hidden=\"hidden\"");

        for (int i = 0; i < 24; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        String network = renderDashboard(party(), List.of(), PL, limiter);
        assertThat(element(network, "data-limit-calm")).contains("hidden");
        assertThat(element(network, "data-limit-near=\"network\"")).doesNotContain("hidden").endsWith(">Sieć 24/30")
                .contains("data-template=\"Sieć {used}/{limit}\"");

        GuestRequestLimiter busy = new GuestRequestLimiter(0, 10, 300, "");
        for (int i = 0; i < 250; i++) {
            busy.tryAcquire("203.0.113." + (i % 200), PARTY);
        }
        String party = renderDashboard(party(), List.of(), PL, busy);
        String warning = party.substring(party.indexOf("id=\"guestLimitWarnings\""));
        assertThat(element(warning, "data-limit-near=\"party\"")).doesNotContain("hidden").endsWith(">Impreza: 250 z 300 próśb na dobę")
                .contains("data-below=\"true\"", "data-template=\"Impreza: {used} z {limit} próśb na dobę\"");
        String row = party.substring(party.indexOf("data-settings-row=\"limits\""));
        assertThat(element(row, "data-limit-near=\"party\"")).doesNotContain("hidden").endsWith(">Impreza 250/300");
    }

    /** One element, from its opening tag to the end of its text, found by a piece of its opening tag. */
    private static String element(String html, String piece) {
        int at = html.indexOf(piece);
        assertThat(at).as(piece).isPositive();
        int start = html.lastIndexOf("<", at);
        return html.substring(start, html.indexOf("</", at));
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
                .instagramUrl("https://www.instagram.com/dj.koko/").tipUrl("https://buycoffee.to/djkoko")
                .hostWanted("Hej sokoły").hostToken("AbC_123-xyzAbC_123-xyz").hostTokenMadeAt(java.time.Instant.now())
                .staffToken("Inv_123-xyzInv_123-xyz").staffTokenMadeAt(java.time.Instant.now()).build();
    }

    /** A waiting request with its "🔍 Podejrzyj" link: YouTube's search results for the song's name. */
    private static SongRequestEntity song(long id, String name) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).style("Pop").decision("accepted")
                .djComment("ok").requestedAt(java.time.LocalDateTime.of(2026, 9, 29, 20, 0, (int) id).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant())
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
                "id=\"history-content\"", "data-poll-offline", "data-form-refused", "data-form-refused-reload", "id=\"djTabBar\"", "data-dj-tab=\"panel\"", "data-dj-tab=\"history\"", "data-dj-tab=\"settings\"");
        // the warnings of the server's guest limits (dashboard.js, applyGuestLimits) and the line with the limits
        assertThat(html).contains("id=\"guestLimitWarnings\"", "data-guest-limit=\"party-full\"", "data-limit-near=\"party\"");
        // the AI not judging (review 2026-10-21, O2): the bar is there, hidden while the AI answers (polling.js shows it by the flag)
        assertThat(html).contains("data-guest-limit=\"ai-down\"");
        assertThat(html.substring(html.indexOf("data-guest-limit=\"ai-down\"") - 120, html.indexOf("data-guest-limit=\"ai-down\"")))
                .contains("d-none");
        // the organiser's way out when the party's day is spent (review 2026-10-14 W4): in the warning, for whom may set the limits
        assertThat(html).contains("action=\"/dj/dashboard/guest-limit-reset\"");
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
        // the settings (the design review, 2026-10-10): the card "Impreza" and the QR code, then a row with its state for each rare
        // setting — the cards themselves are on the page "Ustawienia imprezy" (SettingsPageRenderTest)
        assertThat(html).contains("id=\"partyCard\"", ">Impreza<", "id=\"qrCard\"", "id=\"settingsMore\"", ">Więcej<",
                        "data-settings-row=\"hosts\"", "data-settings-row=\"links\"", "data-settings-row=\"limits\"",
                        "data-settings-row=\"staff\"", "data-settings-row=\"device\"",
                        "href=\"/dj/settings?party=HARN1#hostListsCard\"", "href=\"/dj/settings?party=HARN1#deviceCard\"")
                .doesNotContain("id=\"limitsCard\"", "id=\"deviceCard\"", "id=\"linksCard\"", "id=\"hostListsCard\"", "id=\"staffCard\"",
                        "action=\"/dj/dashboard/limits\"", "action=\"/dj/dashboard/tip-link\"", "action=\"/dj/dashboard/dj-links\"",
                        "action=\"/dj/dashboard/host-lists\"", "id=\"pushToggle\"", "id=\"installApp\"", "Źródło odtwarzania");
        assertThat(html).as("each row's state")
                .contains(">🚫 0 · ⭐ 1 · link<", ">Instagram · napiwki<", ">2 na gościa · 3 min<", ">Osób: 1<",
                        "data-on=\"Powiadomienia wł.\"", "data-off=\"Powiadomienia wył.\"");
        assertThat(html).as("the AI's comment a line under the song, no vibe / verdict / energy columns in the waiting queue")
                .contains("class=\"small text-secondary s2p-queue-comment\"", ">AI: ok<")
                .doesNotContain("ZAAKCEPTOWANE", "badge-energy", "data-sort=\"energy\"");
        assertThat(html).as("the account's buttons under one menu, the party's end in words")
                .contains("id=\"accountMenuBtn\"", ">Konto<", ">Wyloguj<", "id=\"deleteAccountBtn\"", ">Zakończ imprezę<");
        // the owner, 2026-10-12: the guide and one window of help — the address, the guide, the old "Zgłoś uwagę" form
        assertThat(html).as("\"Instrukcja\" and \"Pomoc i kontakt\" in the menu, the help window with the address and the form")
                .contains(">Instrukcja<", "id=\"helpMenuBtn\"", ">Pomoc i kontakt<",
                        ">scan2playcontact@gmail.com<", "data-copy-text=\"scan2playcontact@gmail.com\"", ">Kopiuj<",
                        ">Zgłoś problem albo pomysł<", "id=\"feedbackMessage\"")
                .doesNotContain(">Zgłoś uwagę<");
        for (String link : List.of("guideMenuLink", "helpGuideLink", "queueEmptyGuide")) {
            assertThat(html).as(link + " leads to the guide in Polish")
                    .containsPattern("<a [^>]*(id=\"" + link + "\"[^>]*href=\"/jak-to-dziala\"|href=\"/jak-to-dziala\"[^>]*id=\"" + link + "\")");
        }
        assertThat(html).as("the time of a request: the clock, the day under it, the full moment in the title")
                .contains("title=\"29.09.2026 20:00:01\"", ">20:00</span>", ">29.09</span>").doesNotContain(">29.09.2026 20:00:01<");
        assertThat(html).as("our logo above the page's heading").contains(
                "<span class=\"s2p-logo\"><img src=\"/images/logo-small.svg\" alt=\"\" class=\"s2p-logo-mark\"><span>Scan<span class=\"s2p-logo-two\">2</span>Play</span></span>",
                "<h1 class=\"visually-hidden\">Panel DJ-a</h1>");
        assertThat(html).as("the queue sorts by votes, the most wanted first").contains("<th data-sort=\"votes\" data-sort-first=\"desc\"", ">Głosy<");
        // the DJ's vibe note form, and "any" means "the AI judges" here: the guests pick no vibe
        assertThat(html).as("the AI's comment style (V22): the party's own picked, saved as soon as picked")
                .contains("action=\"/dj/dashboard/comment-style\"", "id=\"commentStyleSelect\"", ">Komentarze AI<",
                        "selected=\"selected\">Klasyczne<", ">Sarkastyczne<")
                .doesNotContain("data-example", "commentStyleExample", "łagodne", "SARCASTIC_LIGHT");
        assertThat(html).as("the vibe note (V16) and who plays (V17): one form, one \"Zapisz\"")
                .contains("action=\"/dj/dashboard/party-words\"", "id=\"vibeNoteInput\"", "id=\"djNameInput\"", ">Opis klimatu<", ">Kto gra<")
                .doesNotContain("action=\"/dj/dashboard/dj-name\"", "action=\"/dj/dashboard/vibe-note\"", "(widzą goście)");
        assertThat(html).contains(">Dowolny<").doesNotContain("Goście wybierają", "ocenia AI");
        assertThat(html).contains("Gość: „ta o Baśce, co ją Wilki grają”");
        assertThat(html.split(">⚠ Sprawdź<", -1)).as("only the song with none of the guest's words").hasSize(2);
        // the vibe and the QR code fold under one button, so the queue comes first (app.css); it names what it holds —
        // "Ustawienia" is the tab of the page "Ustawienia imprezy"
        assertThat(html).contains("id=\"settingsToggle\"", ">Impreza i kod QR<", "data-dj-tab=\"settings\"", ">Ustawienia</a>");
        // the class alone, not the button's s2p-settings-toggle
        assertThat(html.split("s2p-settings(?![-\\w])", -1).length - 1).as("the folded parts: the row of the settings' cards and its two columns").isEqualTo(3);
        // "Wyczyść kolejkę": the DJ's own queue (no party code in the form), asks first
        assertThat(html).contains("action=\"/dj/dashboard/clear-queue\"", "id=\"clearQueueBtn\"", ">Wyczyść kolejkę<",
                "data-confirm=\"Usunąć wszystkie czekające prośby z kolejki?");
        // "Dodaj piosenkę" (V44): a button over the queue, its form folded (add-song.js) with the guests' suggestions
        assertThat(html).contains("id=\"addSongToggle\"", "aria-controls=\"addSongForm\"", ">+ Dodaj piosenkę<",
                        "action=\"/dj/dashboard/add-song\"", "name=\"song\" required maxlength=\"150\"", "data-autocomplete=\"songs\"",
                        "src=\"/js/song-autocomplete.js\"")
                .containsPattern("<form[^>]*id=\"addSongForm\"[^>]*hidden");
        assertThat(html).contains("action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/dismiss\"", ">⏭ Pomiń<", ">Podejrzyj<",
                "href=\"https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka\"");
        // the request's buttons: "▶ Zagrane" filled and short (one line), "⏭ Pomiń" outlined but readable (the owner, 2026-10-07)
        assertThat(html).containsPattern("class=\"btn btn-sm btn-action text-nowrap s2p-btn-played\"[^>]*>▶ Zagrane<")
                .containsPattern("class=\"btn btn-sm btn-outline-light text-nowrap s2p-btn-skip\"[^>]*>⏭ Pomiń<")
                .containsPattern("class=\"btn btn-sm btn-outline-secondary [^\"]*\"[^>]*>Podejrzyj<")   // a button as in the history (the owner, 2026-10-10)
                .doesNotContain("btn-outline-danger text-danger", "Oznacz jako zagrane", "btn-outline-secondary\" title=\"Nie tę");
        assertThat(html).doesNotContain("Powered by YouTube", "🔍 YOUTUBE", "▶ YOUTUBE", "YouTube API Services");
        // "Cofnij" after "Pomiń": a bar forms.js shows for a few seconds after a skip; hidden until then
        assertThat(html).contains("id=\"undoSkip\"", "Pominięto:", "data-undo-button", ">Cofnij<");
        assertThat(html.substring(html.indexOf("id=\"undoSkip\""), html.indexOf("data-undo-song"))).contains("hidden");
        assertThat(html).as("the staff (V30, V32): how many, and the way to the page \"Obsługa\" — the list and the link are there")
                .contains("data-settings-row=\"staff\"", ">Obsługa<", ">Osób: 1<", "id=\"staffManageLink\"", "href=\"/dj/staff?party=HARN1\"")
                .doesNotContain("id=\"staffMenuLink\"", "id=\"staffLinkInput\"", "Inv_123-xyz", "id=\"panelBar\"", "id=\"panelNote\"", "id=\"makeOwnPartyBtn\"");
        assertThat(html).as("every form of the queue names the party it shows (V32: two tabs, two parties)")
                .contains("id=\"panelAccess\" value=\"owner\"");
        String playForm = html.substring(html.indexOf("action=\"/dj/dashboard/play\""));
        assertThat(playForm.substring(0, playForm.indexOf("</form>"))).contains("name=\"partyCode\" value=\"HARN1\"");
        assertThat(html).as("the owner deletes their account; leaving a staff is not theirs")
                .contains("action=\"/dj/delete-account\"").doesNotContain("id=\"leaveStaffBtn\"", "action=\"/dj/staff/leave\"");
        assertThat(html).as("the heading in one row: our logo (the way home, 2026-10-24) beside \"Zakończ\" (a word on a phone) and \"Konto\"")
                .containsPattern("s2p-account-bar\">\\s*<div class=\"fs-4 fw-bold text-white me-auto s2p-min-w-0\">"
                        + "<a href=\"/\" class=\"s2p-home-logo\"[^>]*><span class=\"s2p-logo\">")
                .contains(">Zakończ<", "aria-label=\"Zakończ imprezę\"");
        write("dashboard.html", html);
    }

    @Test
    @DisplayName("the Content-Security-Policy of the real server (written to target/browser-harness/csp.txt): the stand-in sends it, enforced")
    void shouldWriteThePolicyForTheBrowserTests() throws IOException {
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY).contains("script-src 'self'").doesNotContain("'unsafe-eval'", "youtube", "ytimg");
        write("csp.txt", SecurityConfig.CONTENT_SECURITY_POLICY);
    }

    /** Kasia, on the staff of {@code party}: her Google subject and name. */
    private static OAuth2AuthenticationToken kasia() {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "kasia", "name", "Kasia"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    /** The panel of Kasia on the staff of Klub Ola with {@code role}'s permissions, right after she joined. */
    private static String renderStaffPanel(StaffRole role, List<PartyStaffService.Panel> panels) {
        PartySettingsEntity pub = party();
        pub.setDjName("Klub Ola");
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.panelsOf("kasia")).thenReturn(panels);
        return renderDashboard(pub, List.of(song(1, "Wilki - Baśka")), PL, new GuestRequestLimiter(30, 10, 300, ""), kasia(), staff,
                new MockHttpSession(), new PartyStaffService.Access(pub, false, role.permissions()),
                new DjSessionHelper.Note("dashboard.staff.joined", "Klub Ola", false));
    }

    /** The guests' dedications the dashboard's queue shows (V38), by the song's id: none, unless a test gives them. */
    private static Map<Long, List<com.scan2play.entity.SongRequestDedicationEntity>> dedications = Map.of();
    /** The queue's marks of the DJ's library (V41), by the song's id: none, unless a test gives them. */
    private static Map<Long, com.scan2play.model.LibraryMark> libraryMarks = Map.of();
    /** The party's library (V41): none, unless a test gives one. */
    private static java.util.Optional<com.scan2play.service.DjLibraryService.Info> library = java.util.Optional.empty();

    private static com.scan2play.entity.SongRequestDedicationEntity dedication(long songId, String text) {
        return com.scan2play.entity.SongRequestDedicationEntity.builder().songRequestId(songId).text(text).checked(true)
                .createdAt(java.time.Instant.now()).build();
    }

    /** A waiting request with its number (V28), the guest's words, the AI's comment, the votes and the DJ's tips. */
    private static SongRequestEntity numbered(long id, int number, String name, String guestText, int votes, int tips) {
        SongRequestEntity request = song(id, name);
        request.setRequestNumber(number);
        request.setGuestText(guestText);
        request.setDjComment("Klasyk parkietu — idealny, żeby rozkręcić salę!");
        request.setVotes(votes);
        request.setTips(tips);
        return request;
    }

    /**
     * The queue a phone folds (the review of 2026-10-10, cards.js): a request with tips counted ("💸 2" stays folded), one with none
     * of the guest's words ("⚠ Sprawdź", the guest's words stay), one on the hosts' list ("⭐", party(): "Hej sokoły") — for the owner
     * ({@code dashboard-cards.html}) and for the staff's "Podgląd", who has no buttons ({@code dashboard-cards-viewer.html}).
     */
    @Test
    void shouldRenderTheFoldedCardsForTheBrowserTests() throws IOException {
        List<SongRequestEntity> queue = List.of(
                numbered(11, 12, "Daft Punk - One More Time", "daft punk one more time", 4, 2),
                numbered(12, 13, "sanah - Szampan", "orła cień", 1, 0),
                numbered(13, 14, "Golec uOrkiestra - Hej sokoły", "hej sokoły dla babci", 2, 0));
        // the first one with four guests' dedications (V38): the newest three shown, "+1 więcej"; a phone folds them to "💌 4"
        queue.getFirst().setDedications(4);
        dedications = Map.of(11L, List.of(dedication(11, "Dla Zosi"), dedication(11, "Dla Ani"),
                dedication(11, "Dla Damiana od najlepszego wujka")));
        String owner;
        try {
            owner = renderDashboard(party(), queue, PL);
        } finally {
            dedications = Map.of();
        }
        assertThat(owner).contains("💌 „Dla Damiana od najlepszego wujka”", ">+1 więcej<", "s2p-dedication-count", ">💌 4<",
                "title=\"Dedykacje gości — dotknij karty, żeby je przeczytać\"");
        assertThat(owner).contains("class=\"btn btn-sm d-md-none s2p-card-toggle\" aria-expanded=\"false\"",
                "aria-label=\"Szczegóły\"", "s2p-check-song", "s2p-host-wanted", "💸 2", ">Podejrzyj<");
        // "Kopiuj" and the queue's keys (queue-keys.js): "Zagrane" / "Pomiń" by the keyboard only for whom may press them
        assertThat(owner).contains("s2p-btn-copy", ">Kopiuj<", "data-copied=\"Skopiowano\"",
                "id=\"queueKeys\"", ">Skróty klawiszowe<", "<kbd>Enter</kbd> <span class=\"s2p-key-what\">zagrane</span>",
                "<kbd>S</kbd> <span class=\"s2p-key-what\">pomiń</span>", "<kbd>Backspace</kbd> <span class=\"s2p-key-what\">cofnij</span>");
        assertThat(owner).as("the way out of a spent day (W4)").contains(">Odblokuj prośby gości<");
        write("dashboard-cards.html", owner);

        // the staff's "Podgąd": the plain cards, one line each (queue-cards-viewer) — the dedications are the owner's page's
        queue.getFirst().setDedications(0);
        PartyStaffService staff = mock(PartyStaffService.class);
        String viewer = renderDashboard(party(), queue, PL, new GuestRequestLimiter(30, 10, 300, ""), kasia(), staff,
                new MockHttpSession(), new PartyStaffService.Access(party(), false, StaffRole.VIEWER.permissions()), null);
        assertThat(viewer).contains("s2p-card-toggle", ">Kopiuj<", "<kbd>C</kbd> <span class=\"s2p-key-what\">kopiuj</span>",
                        "<kbd>Esc</kbd> <span class=\"s2p-key-what\">odznacz</span>")
                .doesNotContain("action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/tip-count\"", "<kbd>Enter</kbd>",
                        "action=\"/dj/dashboard/guest-limit-reset\"");
        write("dashboard-cards-viewer.html", viewer);
    }

    /**
     * The panel of someone on the party's staff with the role "Obsługa kolejki" (V30, V32): the queue, the history, the notifications,
     * closing the party, the QR code — no settings, no lists, no staff. One line says whose panel it is and the role; unfolded, what
     * she may do. A bartender without a party of her own: no switcher, but "Załóż własną imprezę" in the menu.
     * {@code dashboard-staff.html} for the browser tests.
     */
    @Test
    void theStaffsPanel_queueRole_isTheQueueAndTheHistory() throws IOException {
        String html = renderStaffPanel(StaffRole.QUEUE, List.of(new PartyStaffService.Panel(PARTY, "Klub Ola", false)));
        write("dashboard-staff.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("id=\"panelNote\"", "Dołączono do obsługi: Klub Ola.",
                "id=\"panelBar\"", ">Klub Ola<", "id=\"panelRole\"", ">Obsługa kolejki<", "id=\"panelCan\"",
                "data-permission=\"QUEUE\"", "Zagrane, Pomiń, Cofnij, Przywróć", "data-permission=\"CLEAR_QUEUE\"",
                "action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/dismiss\"", "id=\"clearQueueBtn\"", "data-settings-row=\"device\"",
                "action=\"/dj/dashboard/add-song\"",
                "action=\"/dj/end-party\"", "Zakończyć imprezę: Klub Ola?",
                "id=\"leaveStaffBtn\"", "action=\"/dj/staff/leave\"", "Opuścić obsługę tej imprezy?",
                // no party of her own: made on purpose from the menu, never by a click on "Mój panel"
                "id=\"makeOwnPartyBtn\"", ">Załóż własną imprezę<",
                "id=\"panelAccess\" value=\"QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY\"");
        assertThat(html).as("one panel only: nothing to switch to").doesNotContain("id=\"panelSwitcher\"", "Mój panel");
        assertThat(html).as("the staff's button opens the QR code only").contains(">Kod QR<").doesNotContain(">Impreza i kod QR<");
        assertThat(html).doesNotContain("action=\"/dj/delete-account\"", "id=\"staffMenuLink\"");
        assertThat(html).as("no \"Wklej link\" on another's party: she has joined already (the owner, 2026-10-10)")
                .doesNotContain("id=\"joinStaffForm\"", "action=\"/dj/join\"");
        assertThat(html).as("the QR code, its link and its print: the staff's too — it is on the tables anyway (the owner, 2026-10-09)")
                .contains("id=\"partyLinkInput\"", "value=\"http://localhost:8080/p/HARN1\"", "id=\"qrPrintLink\"", "aria-label=\"Link dla gości\"",
                        "id=\"tvScreenLink\"");
        assertThat(html).as("the owner's: the settings, the limits, the lists, the profiles, the tip link, the staff")
                .doesNotContain("action=\"/dj/dashboard/vibe\"", "action=\"/dj/dashboard/limits\"", "id=\"hostListsCard\"",
                        "data-settings-row=\"hosts\"", "data-settings-row=\"limits\"", "data-settings-row=\"links\"",
                        "data-settings-row=\"staff\"", "action=\"/dj/dashboard/dj-links\"", "action=\"/dj/dashboard/tip-link\"",
                        "Inv_123-xyz", "id=\"hostLinkInput\"", "/h/", "data-open-settings");
    }

    /** "Podgląd" (V32): the queue and the history to look at — no button that changes anything. */
    @Test
    void theStaffsPanel_viewer_changesNothing() {
        String html = renderStaffPanel(StaffRole.VIEWER, List.of(new PartyStaffService.Panel(PARTY, "Klub Ola", false)));

        assertThat(html).contains(">Podgląd<", "id=\"song-list\"", ">Wilki - Baśka<", "data-dj-tab=\"history\"")
                .doesNotContain("action=\"/dj/dashboard/play\"", "action=\"/dj/dashboard/dismiss\"", "id=\"clearQueueBtn\"",
                        "action=\"/dj/end-party\"", "action=\"/dj/start-party\"", "s2p-tip-count", "data-permission=\"QUEUE\"",
                        "id=\"addSongToggle\"", "action=\"/dj/dashboard/add-song\"");
    }

    /** "Współorganizator" (V32): the party's settings cards too — still not the staff, the profiles or the tip link. */
    @Test
    void theStaffsPanel_coOrganiser_hasTheSettingsButNotTheStaffOrTheMoney() {
        String html = renderStaffPanel(StaffRole.CO_ORGANISER, List.of(new PartyStaffService.Panel(PARTY, "Klub Ola", false)));

        assertThat(html).contains(">Współorganizator<", ">Ustawienia<", "id=\"partyCard\"", "data-settings-row=\"limits\"",
                        "data-settings-row=\"hosts\"", "data-settings-row=\"device\"", "data-open-settings")
                .doesNotContain("data-settings-row=\"staff\"", "data-settings-row=\"links\"", "data-settings-row=\"library\"",
                        "action=\"/dj/dashboard/tip-link\"", "id=\"staffMenuLink\"",
                        "action=\"/dj/delete-account\"");
    }

    /** A DJ with a party of her own on another's staff: the other panel to open, her own named as the guests see it. */
    @Test
    void theStaffsPanel_withAPartyOfTheirOwn_offersTheirPanel() {
        String html = renderStaffPanel(StaffRole.QUEUE, List.of(new PartyStaffService.Panel("KASIA", "DJ Kasia", true),
                new PartyStaffService.Panel(PARTY, "Klub Ola", false)));

        assertThat(html).contains("id=\"panelSwitcher\"", "action=\"/dj/panel\"", ">DJ Kasia (Twoja impreza)<",
                "name=\"party\" value=\"KASIA\"", "name=\"party\" value=\"HARN1\"");
        String current = html.substring(html.indexOf("name=\"party\" value=\"HARN1\""));
        assertThat(current.substring(0, current.indexOf("</button>"))).as("the panel open now is lit").contains("aria-current=\"page\"");
    }

    @Test
    @DisplayName("the same party in English (written to target/browser-harness/dashboard-en.html): the page says which language it is")
    void shouldRenderTheDashboardInEnglish() throws IOException {
        String html = renderDashboard(party(), List.of(song(1, "Song One")), Locale.ENGLISH);

        assertWhatTheScriptsNeed(html);
        assertThat(html).contains(">⏭ Skip<", ">▶ Played<", ">Preview<", ">Settings<", ">QR code<");
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
        assertThat(german).contains(">Preview<", "<html lang=\"en\">");
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
                i == 3 ? "Klasyk wesel, ale parkiet chce dziś czegoś szybszego — może później?" : "ok",
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
        return renderHistory(filter, limit, entries, hasMore, true, com.scan2play.service.Plans.off());
    }

    /** The History tab for the owner or ({@code owner} false) a person of the staff with every permission, under {@code plans}. */
    private static String renderHistory(HistoryFilter filter, int limit, List<HistoryEntry> entries, boolean hasMore, boolean isOwner,
                                        com.scan2play.service.PlanService plans) {
        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(PARTY, limit, filter)).thenReturn(new PlayHistoryService.Page(entries, hasMore));
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        PartyStaffService.Access owner = new PartyStaffService.Access(party(), isOwner, StaffPermission.all());
        when(sessionHelper.access(any(), any(), any())).thenReturn(owner);
        when(sessionHelper.require(any(), any(), any(), any())).thenReturn(owner.party());
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
                mock(QrCodeService.class), sessionHelper, history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class), mock(PartyStaffService.class), mock(com.scan2play.service.StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor());

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

    /**
     * The standalone history page ({@code /dj/history-view?party=}) as {@code DjDashboardController.historyView} builds it: a first
     * page with older entries to show — written to {@code history-page.html} for the browser scenario that checks its filter and
     * "Pokaż więcej" ask for the same party (review 2026-10-14 W7). The page names its party for the scripts.
     */
    @Test
    void shouldRenderTheStandaloneHistoryPageForTheBrowserTests() throws IOException {
        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(PARTY, 50, HistoryFilter.ALL)).thenReturn(new PlayHistoryService.Page(List.of(
                historyEntry(1, "Played Alpha", "played"), historyEntry(2, "Rejected Beat", "rejected")), true));
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        PartyStaffService.Access owner = new PartyStaffService.Access(party(), true, StaffPermission.all());
        when(sessionHelper.access(any(), any(), any())).thenReturn(owner);
        when(sessionHelper.require(any(), any(), any(), any())).thenReturn(owner.party());
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
                mock(QrCodeService.class), sessionHelper, history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class),
                mock(PartyStaffService.class), mock(com.scan2play.service.StaffInvitationService.class), com.scan2play.service.Plans.off(), new com.scan2play.service.AiHealthMonitor());

        ConcurrentModel model = new ConcurrentModel();
        assertThat(controller.historyView(50, null, PARTY, model, ownerToken(), new MockHttpSession())).isEqualTo("history");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        String html = engine.process("history", context);

        assertThat(html).contains("id=\"partyCode\"", "value=\"" + PARTY + "\"", "data-history-more", "data-list-filter=\"played\"")
                .doesNotContain("??");
        assertNothingInline(html);
        write("history-page.html", html);
    }

    // ---------------------------------------------------------------- the plans (V35)

    /** The plans not enforced (production today): not one mark of Pro anywhere — nothing changes for the DJs. */
    @Test
    void withThePlansNotEnforced_nothingSaysPro() {
        String html = renderDashboard(party(), List.of(numbered(11, 12, "Golec uOrkiestra - Hej sokoły", "hej sokoły", 1, 2)), PL);
        assertThat(html).contains("data-settings-row=\"hosts\"", ">🚫 0 · ⭐ 1 · link<", ">Instagram · napiwki<", ">Zabawne<",
                        "s2p-btn-tip", "s2p-host-wanted")
                .doesNotContain("s2p-pro", "data-pro-offer", "· Pro", "W planie Pro", "<optgroup", "disabled=\"disabled\" value=\"FUNNY\"");
    }

    /**
     * An organiser without Pro, the plans enforced (variant B of the review 2026-10-14): the rows of the Pro features stay with a
     * "PRO" by the name, said once — no "W planie Pro" beside it (the limits' row keeps the limits that hold: 2 / 3, short enough for
     * one line on a phone; the review of the look, 2026-10-14) —, the AI's other comment styles are there but
     * not to pick, and the queue changes nothing but what the plan has not: no 💸, no ⭐ of the hosts' list (kept, not applied).
     * {@code dashboard-free.html} for the browser tests.
     */
    @Test
    void aFreeOrganiser_seesProByTheRows_neverInTheQueue() throws IOException {
        PartySettingsEntity party = party();
        party.setRequestLimit(5);   // saved while on Pro: kept, but the party works with the defaults
        PartyStaffService staff = mock(PartyStaffService.class);
        String html = renderDashboard(party, List.of(numbered(11, 12, "Golec uOrkiestra - Hej sokoły", "hej sokoły", 1, 2)), PL,
                new GuestRequestLimiter(30, 10, 300, ""), ownerToken(), staff, new MockHttpSession(),
                new PartyStaffService.Access(party, true, StaffPermission.all()), null, com.scan2play.service.Plans.enforced());

        assertWhatTheScriptsNeed(html);
        assertThat(rowOf(html, "hosts")).contains("class=\"s2p-pro\">PRO<", "class=\"s2p-row-state\"></span>")
                .doesNotContain("🚫", "W planie Pro");
        assertThat(rowOf(html, "links")).contains("class=\"s2p-pro\">PRO<", "class=\"s2p-row-state\"></span>")
                .doesNotContain("Instagram", "W planie Pro");
        assertThat(rowOf(html, "limits")).contains("class=\"s2p-pro\">PRO<", ">2 na gościa · 3 min<");
        assertThat(rowOf(html, "staff")).doesNotContain("s2p-pro");
        assertThat(html).as("the comment styles: \"Klasyczne\" to pick, the others under \"W planie Pro\" and not")
                .contains(">Klasyczne<", "<optgroup label=\"W planie Pro\">", ">Zabawne<", ">Sarkastyczne<", ">Krótkie<")
                .doesNotContain("· Pro<")
                .containsPattern("disabled=\"disabled\"[^>]*value=\"FUNNY\"|value=\"FUNNY\"[^>]*disabled=\"disabled\"");
        assertThat(html).as("the queue: no 💸 and no ⭐ without Pro — the song's own number and the buttons stay")
                .contains("Golec uOrkiestra - Hej sokoły", "s2p-btn-played", "s2p-btn-skip")
                .doesNotContain("s2p-btn-tip", "action=\"/dj/dashboard/tip-count\"", "s2p-host-wanted");
        write("dashboard-free.html", html);
    }

    /** The staff of a party without Pro (V35): nothing of Pro, no row of a Pro feature — the staff cannot buy it (6.7: hidden). */
    @Test
    void theStaffOfAFreeParty_seeNoPro_andNoRowOfAProFeature() {
        String html = renderDashboard(party(), List.of(), PL, new GuestRequestLimiter(30, 10, 300, ""), kasia(),
                mock(PartyStaffService.class), new MockHttpSession(),
                new PartyStaffService.Access(party(), false, StaffRole.CO_ORGANISER.permissions()), null,
                com.scan2play.service.Plans.enforced());
        assertThat(html).contains("id=\"partyCard\"", ">Klasyczne<")
                .doesNotContain("s2p-pro", "· Pro", "<optgroup", "data-settings-row=\"hosts\"", "data-settings-row=\"limits\"", ">Zabawne<");
    }

    /** An organiser with Pro, the plans enforced: the panel as today. */
    @Test
    void aProOrganiser_hasThePanelAsToday() {
        PartySettingsEntity party = party();
        String html = renderDashboard(party, List.of(numbered(11, 12, "Golec uOrkiestra - Hej sokoły", "hej sokoły", 1, 2)), PL,
                new GuestRequestLimiter(30, 10, 300, ""), ownerToken(), mock(PartyStaffService.class), new MockHttpSession(),
                new PartyStaffService.Access(party, true, StaffPermission.all()), null, com.scan2play.service.Plans.enforced("owner"));
        assertThat(html).contains(">🚫 0 · ⭐ 1 · link<", ">Instagram · napiwki<", ">Zabawne<", "s2p-btn-tip", "s2p-host-wanted")
                .doesNotContain("s2p-pro", "· Pro", "<optgroup");
    }

    /** "Podsumowanie wieczoru" (V35): to an organiser without Pro, the way to /pro with "PRO"; to the staff of such a party, nothing. */
    @Test
    void theEveningSummary_withoutPro_leadsToWhatProAdds() {
        List<HistoryEntry> one = List.of(historyEntry(1, "Played Alpha", "played"));
        assertThat(renderHistory(HistoryFilter.ALL, 50, one, false, true, com.scan2play.service.Plans.off()))
                .contains("href=\"/dj/summary?party=HARN1\"").doesNotContain("s2p-pro");
        assertThat(renderHistory(HistoryFilter.ALL, 50, one, false, true, com.scan2play.service.Plans.enforced()))
                .contains("id=\"summaryLink\"", "href=\"/pro\"", ">Podsumowanie wieczoru<", "class=\"s2p-pro\">PRO<")
                .doesNotContain("/dj/summary");
        assertThat(renderHistory(HistoryFilter.ALL, 50, one, false, false, com.scan2play.service.Plans.enforced()))
                .doesNotContain("id=\"summaryLink\"", "s2p-pro");
    }

    /** One row of "Więcej", from its tag to its end. */
    private static String rowOf(String html, String row) {
        int at = html.indexOf("data-settings-row=\"" + row + "\"");
        assertThat(at).as("the row " + row).isPositive();
        return html.substring(at, html.indexOf("</a>", at));
    }

    /**
     * The DJ's panel of a made-up party, for the pictures of the landing page (src/test/browser/screenshots.py: a laptop's screen,
     * {@code static/images/landing/dashboard-<lang>.webp}): the queue with the guests' words, the AI's comments, the votes, a song on the
     * hosts' list (⭐), one to check (⚠) and tips. No real person in it. The English picture has songs known everywhere and the guests'
     * words in English (the owner, 2026-10-18: Zenek, sanah and Podsiadło are known in Poland only).
     */
    @Test
    void theLandingPagesPicture_ofTheDashboard() throws IOException {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            boolean pl = locale == PL;
            PartySettingsEntity party = party();
            party.setDjName(pl ? "DJ Kuba" : "DJ Alex");
            party.setGlobalVibe(VibeType.WEDDING_CLASSICS);
            party.setHostWanted(pl ? "Przez twe oczy zielone" : "I Wanna Dance with Somebody");
            List<SongRequestEntity> queue = List.of(
                    pl ? demo(21, 12, "Zenek Martyniuk - Przez twe oczy zielone", "zenek oczy zielone", 9, 0, 1,
                            "Sala już śpiewa — to musi polecieć!")
                       : demo(21, 12, "Whitney Houston - I Wanna Dance with Somebody", "whitney dance with somebody", 9, 0, 1,
                            "The whole room sings this one!"),
                    demo(22, 15, "ABBA - Dancing Queen", "abba dancing queen", 6, 2, 4,
                            pl ? "Klasyk parkietu, idealny na rozkręcenie." : "A dance-floor classic, perfect to warm up."),
                    pl ? demo(23, 18, "Dawid Podsiadło - Małomiasteczkowy", "podsiadło małomiasteczkowy", 4, 0, 7,
                            "Świetny wybór na drugą część wieczoru.")
                       : demo(23, 18, "Ed Sheeran - Perfect", "ed sheeran perfect", 4, 0, 7,
                            "Great pick for the slow dance."),
                    demo(24, 21, "Smash Mouth - All Star", pl ? "ta piosenka ze shreka" : "the shrek song", 2, 0, 11,
                            pl ? "Dobra energia, wszyscy znają refren." : "Good energy, everyone knows the chorus."),
                    demo(25, 23, "Queen - Don't Stop Me Now", "queen dont stop me now", 3, 1, 14,
                            pl ? "Petarda na koniec seta!" : "A banger to end the set!"));
            // the guests' dedications (V38) under two songs
            queue.get(0).setDedications(2);
            queue.get(2).setDedications(1);
            dedications = Map.of(
                    21L, List.of(dedication(21, pl ? "Dla Młodej Pary — wszystkiego najlepszego!" : "For the newlyweds — all the best!"),
                            dedication(21, pl ? "Dla babci Heli" : "For Grandma Helen")),
                    23L, List.of(dedication(23, pl ? "Dla Ani na 18-stkę" : "For Mum and Dad — 30 years together")));
            String html;
            try {
                html = renderDashboard(party, queue, locale);
            } finally {
                dedications = Map.of();
            }
            write("landing-demo-dashboard-" + locale.getLanguage() + ".html", html);
            assertThat(html).contains(pl ? "DJ Kuba" : "DJ Alex", "s2p-host-wanted", "s2p-check-song",
                    pl ? "Dla babci Heli" : "For Grandma Helen", pl ? "Dla Ani na 18-stkę" : "For Mum and Dad — 30 years together")
                    .doesNotContain("??", pl ? ">wczoraj<" : ">yesterday<");
            if (!pl) {
                assertThat(html).as("songs known everywhere, the guests' words in English")
                        .doesNotContain("Zenek", "Podsiadło", "sanah", "zielone", "shreka");
            }
            write("landing-demo-dashboard-" + locale.getLanguage() + ".html", html);
        }
    }

    private static SongRequestEntity demo(long id, int number, String name, String guestText, int votes, int tips, int minute,
                                          String comment) {
        SongRequestEntity request = song(id, name);
        request.setRequestNumber(number);
        request.setGuestText(guestText);
        request.setVotes(votes);
        request.setTips(tips);
        request.setDjComment(comment);
        // today, whenever the pictures are made: the day under the time says "dziś" ("wczoraj" with a fixed date a day later)
        request.setRequestedAt(java.time.LocalDate.now(com.scan2play.util.Times.DISPLAY_ZONE).atTime(21, minute)
                .atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant());
        return request;
    }

    /**
     * The screen for a TV (Next 7 c, an idea from Rekwest): under the QR code's print, the party's public screen in a new tab — the
     * most wanted songs and the code, to open on a TV or a laptop by a projector.
     */
    @Test
    void theQrCard_leadsToTheTvScreen() {
        String html = renderDashboard(party(), List.of(), PL);
        // the screen speaks the panel's language (the owner, 2026-10-23): the link carries it to the TV
        assertThat(DashboardPageRenderTest.tagOf(html, "tvScreenLink")).contains("href=\"/p/HARN1/tv?lang=pl\"", "target=\"_blank\"",
                "rel=\"noopener\"");
        assertThat(html).contains(">📺 Ekran na TV<");
        assertThat(html.indexOf("id=\"tvScreenLink\"")).as("beside the print").isGreaterThan(html.indexOf("id=\"qrPrintLink\""));
        String english = renderDashboard(party(), List.of(), Locale.ENGLISH);
        assertThat(english).contains(">📺 Screen for a TV<");
        assertThat(DashboardPageRenderTest.tagOf(english, "tvScreenLink")).contains("href=\"/p/HARN1/tv?lang=en\"");
        assertThat(DashboardPageRenderTest.tagOf(renderDashboard(party(), List.of(), Locale.GERMAN), "tvScreenLink"))
                .as("any other language: English").contains("href=\"/p/HARN1/tv?lang=en\"");
    }

    /**
     * The language in the menu "Konto" (the owner, 2026-10-18): the panel's footer is far under a long queue, so the menu has
     * "Polski" / "English" too — this panel ({@code ?party=}) in that language, the one shown marked.
     */
    @Test
    void theAccountMenu_switchesTheLanguage() {
        String html = renderDashboard(party(), List.of(), PL);
        String menu = html.substring(html.indexOf("aria-labelledby=\"accountMenuBtn\""), html.indexOf("</ul>", html.indexOf("aria-labelledby=\"accountMenuBtn\"")));
        assertThat(menu).contains("Język / Language", "href=\"/dj/dashboard?party=HARN1&amp;lang=en\"", ">English<", ">Polski<")
                .containsPattern("<a [^>]*href=\"/dj/dashboard\\?party=HARN1&amp;lang=pl\"[^>]*aria-current=\"true\"");
        assertThat(html).as("and the footer").contains("id=\"langSwitch\"");
    }

    /** The opening tag of the element with this id. */
    static String tagOf(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as(id).isPositive();
        return html.substring(html.lastIndexOf('<', at), html.indexOf('>', at) + 1);
    }

    /** The guests' dedications (V38) on or off in the card "Impreza", saved as soon as picked (data-auto-submit), as the vibe. */
    @Test
    void theDedications_areSwitchedInThePartyCard() {
        String on = renderDashboard(party(), List.of(), PL);
        assertThat(on).contains("action=\"/dj/dashboard/dedications\"", "id=\"dedicationsSelect\"", ">Dedykacje gości<",
                "value=\"true\" selected=\"selected\">Włączone<", "value=\"false\">Wyłączone<").doesNotContain("??");
        assertThat(on.indexOf("id=\"dedicationsSelect\"")).as("in the card \"Impreza\"").isGreaterThan(on.indexOf("id=\"partyCard\""))
                .isLessThan(on.indexOf("action=\"/dj/dashboard/party-words\""));
        assertThat(on.substring(on.indexOf("id=\"dedicationsSelect\""))).startsWith("id=\"dedicationsSelect\" name=\"on\" data-auto-submit");

        PartySettingsEntity offParty = party();
        offParty.setDedicationsOn(false);
        assertThat(renderDashboard(offParty, List.of(), PL)).contains("value=\"false\" selected=\"selected\">Wyłączone<");
        assertThat(renderDashboard(party(), List.of(), Locale.ENGLISH)).contains(">Guests’ dedications<", ">On<", ">Off<");
    }

    /**
     * The DJ's library (V41, V42): each waiting song "✓ Masz", "? Podobny" or "✗ Nie masz" under its name, with what it means on
     * hover; a song the service has no mark for (no library) none. Written for the browser tests as {@code dashboard-library.html}.
     */
    @Test
    void theLibrary_marksEachSong() throws IOException {
        libraryMarks = Map.of(1L, com.scan2play.model.LibraryMark.HAVE, 2L, com.scan2play.model.LibraryMark.MISSING,
                4L, com.scan2play.model.LibraryMark.SIMILAR);
        library = java.util.Optional.of(new com.scan2play.service.DjLibraryService.Info(3,
                com.scan2play.service.DjLibraryParser.Format.M3U, java.time.Instant.now(), null));
        try {
            String html = renderDashboard(party(), List.of(song(1, "Wilki - Baśka"), song(2, "sanah - Szampan"), song(3, "ABBA - SOS"),
                    song(4, "Dżem - Baśka")), PL);

            assertWhatTheScriptsNeed(html);
            assertThat(rowFor(html, 1)).contains("data-library=\"have\"", "text-bg-success", ">✓ Masz<", "title=\"Ten utwór jest w Twojej bibliotece\"");
            assertThat(rowFor(html, 2)).contains("data-library=\"missing\"", ">✗ Nie masz<", "title=\"Tego utworu nie ma w Twojej bibliotece");
            assertThat(rowFor(html, 3)).doesNotContain("data-library", "s2p-library");
            assertThat(rowFor(html, 4)).contains("data-library=\"similar\"", ">? Podobny<",
                    "title=\"Masz utwór o tym tytule, ale innego wykonawcy albo zapisany inaczej — sprawdź\"");
            assertThat(html).as("the switch \"Ukryj „Nie masz”\" beside the search, for a party with marks: it says the mark it hides")
                    .contains("id=\"libraryOnly\"", ">Ukryj „Nie masz”<", "aria-pressed=\"false\"");
            write("dashboard-library.html", html);
        } finally {
            libraryMarks = Map.of();
            library = java.util.Optional.empty();
        }
    }

    @Test
    void withoutALibrary_noSongIsMarked() {
        assertThat(renderDashboard(party(), List.of(song(1, "Wilki - Baśka")), PL)).doesNotContain("data-library", "s2p-library",
                "id=\"libraryOnly\"");
    }

    /** The row of the song {@code id} in the queue, up to the next row. */
    private static String rowFor(String html, long id) {
        int start = html.indexOf("data-song-id=\"" + id + "\"");
        int end = html.indexOf("</tr>", start);
        return html.substring(start, end);
    }

    /** "Więcej" has the DJ's library (V41) too: how many songs, or that none was uploaded; the way to its card. */
    @Test
    void theRowOfTheLibrary_saysItsSongs() {
        assertThat(rowOf(renderDashboard(party(), List.of(), PL), "library"))
                .contains("href=\"/dj/settings?party=" + PARTY + "#libraryCard\"", ">Twoja biblioteka<", ">Nie wgrano<");

        library = java.util.Optional.of(new com.scan2play.service.DjLibraryService.Info(1234,
                com.scan2play.service.DjLibraryParser.Format.REKORDBOX, java.time.Instant.now(), null));
        try {
            assertThat(rowOf(renderDashboard(party(), List.of(), PL), "library")).contains(">Utworów: 1\u00A0234<");
            assertThat(rowOf(renderDashboard(party(), List.of(), Locale.ENGLISH), "library")).contains(">Songs: 1,234<");
        } finally {
            library = java.util.Optional.empty();
        }
    }

    /** Without Pro (V35): the row stays for the organiser with "PRO" by its name and no state, as the other Pro rows. */
    @Test
    void theRowOfTheLibrary_withoutPro_saysPro() {
        PartySettingsEntity party = party();
        String html = renderDashboard(party, List.of(), PL, new GuestRequestLimiter(30, 10, 300, ""), ownerToken(),
                mock(PartyStaffService.class), new MockHttpSession(), new PartyStaffService.Access(party, true, StaffPermission.all()),
                null, com.scan2play.service.Plans.enforced());
        assertThat(rowOf(html, "library")).contains("class=\"s2p-pro\">PRO<", "class=\"s2p-row-state\"></span>")
                .doesNotContain("Nie wgrano");
    }
}
