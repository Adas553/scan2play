package com.scan2play.template;

import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.StaffInvitationService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renders the real page "Ustawienia imprezy" ({@code settings.html}, {@code GET /dj/settings}; the design review, 2026-10-10) — the
 * real controller with mocked services, the real message bundles — for the owner, a co-organiser and a person of the staff with no
 * settings, and leaves {@code settings.html} / {@code settings-staff.html} in {@code target/browser-harness/} for the browser tests.
 * What moved here from the panel keeps the ids its scripts and scenarios use.
 */
class SettingsPageRenderTest {

    private static final String PARTY = "HARN1";
    private static final Path OUT = Path.of("target", "browser-harness");
    private static final Locale PL = Locale.forLanguageTag("pl");

    private static SpringTemplateEngine engine;

    /**
     * A VAPID public key of the right shape (made here): the page offers the switch of the notifications, and the browser scenarios
     * {@code push-*} subscribe with it (65 bytes, an uncompressed point).
     */
    private static final String PUBLIC_KEY = publicKey();

    private static String publicKey() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return com.scan2play.VapidKeyGenerator.publicKeyBase64Url(
                    (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private static PartySettingsEntity party() {
        return PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).globalVibe(VibeType.ANY)
                .instagramUrl("https://www.instagram.com/dj.koko/").tipUrl("https://buycoffee.to/djkoko")
                .hostWanted("Hej sokoły").hostToken("AbC_123-xyzAbC_123-xyz")
                .hostTokenMadeAt(java.time.Instant.now()).build();
    }

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    /** What the controller does for {@code access} with {@code limiter}, rendered like the view resolver would. */
    private static String render(PartyStaffService.Access access, GuestRequestLimiter limiter, Locale locale) {
        return render(access, limiter, locale, com.scan2play.service.Plans.off());
    }

    /** The same with the plans as {@code plans} decides them (V35). */
    private static String render(PartyStaffService.Access access, GuestRequestLimiter limiter, Locale locale,
                                 com.scan2play.service.PlanService plans) {
        return render(access, limiter, locale, plans, mock(DjService.class));
    }

    /** The same with the party's queue and library (V41) as {@code djService} has them. */
    private static String render(PartyStaffService.Access access, GuestRequestLimiter limiter, Locale locale,
                                 com.scan2play.service.PlanService plans, DjService djService) {
        return render(access, limiter, locale, plans, djService, Map.of());
    }

    /** The same after a redirect that left {@code flash} for the page (as Spring puts the flash attributes into its model). */
    private static String render(PartyStaffService.Access access, GuestRequestLimiter limiter, Locale locale,
                                 com.scan2play.service.PlanService plans, DjService djService, Map<String, Object> flash) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.access(any(), any(), any())).thenReturn(access);
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.staffOf(PARTY)).thenReturn(List.of(PartyStaffEntity.builder().id(7L).partyCode(PARTY).memberId("kasia")
                .memberName("Kasia").joinedAt(Instant.now()).build()));
        PushNotificationService push = mock(PushNotificationService.class);
        when(push.publicKey()).thenReturn(PUBLIC_KEY);
        DjDashboardController controller = new DjDashboardController(djService, mock(PartySettingsCommandService.class),
                mock(QrCodeService.class), sessionHelper, mock(PlayHistoryService.class), limiter, push, staff,
                mock(StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor(), com.scan2play.service.PartySchedules.of(mock(PartySettingsCommandService.class), com.scan2play.service.PartySchedules.FRIDAY_NOON));
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "http://localhost:8080/");
        controller.init();

        ConcurrentModel model = new ConcurrentModel();
        model.addAllAttributes(flash);
        // the request's language, as the app's LocaleResolver sets it (the plan's words, V55, are made in the controller)
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
        String view;
        try {
            view = controller.settingsPage(PARTY, model, user(access.owner() ? "owner" : "kasia"), new MockHttpSession());
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        assertThat(view).isEqualTo("settings");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        String html = engine.process(view, context);
        assertThat(html).doesNotContain("??");
        DashboardPageRenderTest.assertNothingInline(html);
        return html;
    }

    /**
     * The answer to "Godziny imprezy" sent in the background (V55): the real controller, on Friday at noon, with the form's fields, and
     * the card it answers with rendered as the view resolver would — the fragment {@code settings :: partyHoursCard} alone.
     */
    @SuppressWarnings("unchecked")
    private static String renderHoursAnswer(PartySettingsEntity party, Map<String, String> fields) {
        PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
        when(commands.updateSettings(any(), any())).thenAnswer(call -> {
            ((java.util.function.Consumer<PartySettingsEntity>) call.getArgument(1)).accept(party);
            return party;
        });
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(any(), any(), any(), any())).thenReturn(party);
        when(sessionHelper.access(any(), any(), any())).thenReturn(new PartyStaffService.Access(party, true, StaffPermission.all()));
        com.scan2play.controller.DjPartySettingsController controller = new com.scan2play.controller.DjPartySettingsController(commands,
                mock(com.scan2play.service.AccountDeletionService.class), mock(com.scan2play.service.PartyCodeService.class), sessionHelper,
                com.scan2play.service.Plans.off(), com.scan2play.service.PartySchedules.of(commands, com.scan2play.service.PartySchedules.FRIDAY_NOON));
        ConcurrentModel model = new ConcurrentModel();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(PL);
        String view;
        try {
            view = controller.updateSchedule(PARTY, fields.get("openDate"), fields.get("openTime"), fields.get("closeDate"),
                    fields.get("closeTime"), fields.get("cancel"), "fetch", user("owner"), new MockHttpSession(), model,
                    new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        assertThat(view).isEqualTo("settings :: partyHoursCard");
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        String html = engine.process("settings", Set.of("partyHoursCard"), context);
        assertThat(html).doesNotContain("??");
        DashboardPageRenderTest.assertNothingInline(html);
        return html;
    }

    /**
     * "Zapisz" sent in the background: the answer is the card alone — no page, no heading —, unfolded, the plan as
     * saved; or why not with the fields as typed. {@code settings-hours-answer-saved.html} / {@code -refused.html} for the stand-in.
     */
    @Test
    void theHoursSentInTheBackground_areAnsweredWithTheCardAlone() throws IOException {
        PartySettingsEntity party = party();
        party.setActive(false);
        String saved = renderHoursAnswer(party, Map.of("openDate", "2026-10-30", "openTime", "21:00", "closeDate", "2026-10-31",
                "closeTime", "04:00"));
        assertThat(saved.trim()).startsWith("<details").endsWith("</details>").doesNotContain("<html", "djTabBar", "hostListsCard");
        assertThat(saved).doesNotContain("partyHoursError", "Zapisano");
        assertThat(saved).contains("id=\"partyHoursCard\" open=\"open\"", "id=\"partyHoursSave\"",
                ">⏰ Otworzy się dziś o 21:00<", ">⏰ Zamknie się jutro o 4:00<", "name=\"openTime\" value=\"21:00\"",
                "action=\"/dj/dashboard/schedule\"", "id=\"openHoursCancel\"");
        write("settings-hours-answer-saved.html", saved);

        String refused = renderHoursAnswer(party(), Map.of("openDate", "2026-10-30", "openTime", "11:30"));
        assertThat(refused).contains("id=\"partyHoursCard\" open=\"open\"", "id=\"partyHoursError\"",
                ">Ta pora już minęła: wybierz późniejszą.<", "name=\"openTime\" value=\"11:30\"");
        write("settings-hours-answer-refused.html", refused);
    }

    private static PartyStaffService.Access owner() {
        return new PartyStaffService.Access(party(), true, StaffPermission.all());
    }

    private static PartyStaffService.Access staff(Set<StaffPermission> permissions) {
        return new PartyStaffService.Access(party(), false, permissions);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the owner: the hosts' lists, the profiles and the tip link, the limits, this device — what the scripts need (written to target/browser-harness/settings.html)")
    void theOwner_hasEveryCard() throws IOException {
        String html = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL);

        assertThat(html).as("what the scripts need: the CSRF token, the party, the page's module, the manifest (the app's install offer)")
                .contains("name=\"_csrf\" content=\"harness-csrf-token\"", "name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"",
                        "id=\"partyCode\" value=\"HARN1\"", "<script type=\"module\" src=\"/js/dashboard/settings-page.js\">",
                        "<script src=\"/js/dj-nav.js\">", "rel=\"manifest\"");
        assertThat(html).contains("<title>Ustawienia imprezy - Scan2Play</title>", ">Ustawienia imprezy<");
        assertThat(html).as("the heading and the tabs of every DJ's page, the tab of the settings lit, the others of the same party")
                .contains("id=\"djTabBar\"", "id=\"end-party-form\"", "id=\"accountMenuBtn\"",
                        "href=\"/dj/dashboard?party=HARN1#top\">Panel DJ-a</a>", "href=\"/dj/settings?party=HARN1\">Ustawienia</a>",
                        "<script src=\"/webjars/bootstrap/js/bootstrap.bundle.min.js\" defer>")
                .doesNotContain("id=\"backToPanel\"");
        assertThat(html.split("<script src=\"/js/dj-nav.js\">", -1)).as("dj-nav.js once: a second copy would ask every question twice").hasSize(2);
        assertThat(html).as("every card folded to its heading and its state (the panel's rows'), the card the address names unfolded by folds.js")
                .contains("<details class=\"card bg-dark border-secondary s2p-fold\" id=\"hostListsCard\">",
                        "<details class=\"card bg-dark border-secondary s2p-fold\" id=\"libraryCard\">",
                        "<details class=\"card bg-dark border-secondary s2p-fold\" id=\"deviceCard\">",
                        "class=\"s2p-fold-state\">🚫 0 · ⭐ 1 · link<", "class=\"s2p-fold-state\">Instagram · napiwki<",
                        "class=\"s2p-fold-state\">Nie wgrano<", "<script src=\"/js/folds.js\">")
                .doesNotContain("id=\"settingsJump\"", "s2p-fold\" id=\"hostListsCard\" open");
        assertThat(html).as("\"Obsługa\" a row like a folded card, with its state, leading to its page")
                .contains("id=\"staffRow\"", "href=\"/dj/staff?party=HARN1\"", ">Obsługa<");
        assertThat(html).as("the hosts' lists (V29): both in one form, the hosts' link to copy, a new one asks first")
                .contains("id=\"hostListsCard\"", "action=\"/dj/dashboard/host-lists\"", "id=\"hostBlockedInput\"", "name=\"blocked\"",
                        ">Hej sokoły</textarea>", ">Lista gospodarzy<", "Jeden utwór lub wykonawca w linii.",
                        "value=\"http://localhost:8080/h/AbC_123-xyzAbC_123-xyz\" id=\"hostLinkInput\"", "data-copy-target=\"hostLinkInput\"",
                        "action=\"/dj/dashboard/host-link\"", ">Nowy link<", "data-confirm=\"Utworzyć nowy link? Stary przestanie działać.\"",
                        "id=\"hostLinkOffBtn\"", ">Wyłącz<", "Link dla gospodarzy — wypełnią listy bez konta",
                        // it works 30 days (V37)
                        "id=\"hostLinkUntil\"", ">Działa do " + com.scan2play.util.Times.day(java.time.Instant.now().plus(java.time.Duration.ofDays(30)))
                                + ".<")
                .doesNotContain("para młoda, solenizant", "Na następną imprezę");
        assertThat(html).as("the lines each hosts' list holds, counted as typed (js/song-list-count.js), and the limit")
                .contains("<script src=\"/js/song-list-count.js\">", "data-list-for=\"hostBlockedInput\"",
                        "data-list-for=\"hostWantedInput\"", "data-list-max=\"100\"");
        assertThat(html).as("the DJ's profiles (V24) and tip link (V27), each form with its note for a refused one hidden until then")
                .contains("id=\"linksCard\"", "id=\"djLinksForm\"", "action=\"/dj/dashboard/dj-links\"", "id=\"instagramInput\"",
                        "id=\"facebookInput\"", "id=\"tiktokInput\"", "value=\"https://www.instagram.com/dj.koko/\"",
                        "Goście widzą je na swojej stronie i na wydruku.", "id=\"tipLinkForm\"", "action=\"/dj/dashboard/tip-link\"",
                        "id=\"tipInput\" name=\"tip\"", "value=\"https://buycoffee.to/djkoko\"", ">Link do napiwków<",
                        "pieniądze idą w 100% prosto do Ciebie, Scan2Play nie bierze prowizji", "Nie zapisano: to nie jest link do Twojej strony", "data-form-error hidden")
                .doesNotContain("Scan2Play ich nie dotyka", "(goście widzą");
        assertThat(html).as("a form refused by the server (the session ran out) says so, hidden until then (review 2026-10-14 W1)")
                .contains("data-form-refused hidden", "Nie zapisano. Sesja mogła wygasnąć albo zerwało się połączenie",
                        "data-form-refused-reload", ">Odśwież stronę<", "aria-label=\"Zamknij\"");
        assertThat(html).as("the guests' limits and the server's in one line, what they are unfolded")
                .contains("id=\"limitsCard\"", "action=\"/dj/dashboard/limits\"", "id=\"requestLimit\"", ">Prośby na gościa<",
                        ">Potem przerwa (min)<", ">Bez powtórek z ostatnich<", "id=\"serverLimitsInfo\"", ">Limity serwera:<",
                        ">na 10 min<", ">na 24 h<", "Jedna sieć to np. Wi-Fi lokalu")
                .doesNotContain("chronią przed nadużyciami", "najbardziej aktywna sieć");
        assertThat(html).as("this device: the notifications, the app, an invitation pasted (on the person's own party)")
                .contains("id=\"deviceCard\"", "id=\"pushSettings\"", "data-public-key=\"" + PUBLIC_KEY + "\"", "id=\"pushToggle\"",
                        ">Powiadomienia o prośbach<", "id=\"installApp\"", "id=\"installAppBtn\"", "id=\"joinStaffForm\"", "action=\"/dj/join\"",
                        ">Zaproszenie do obsługi? Wklej link<");
        assertThat(html).as("the DJ's library (V41): a file sent by library.js, what it does, none uploaded yet")
                .contains("id=\"libraryCard\"", ">Twoja biblioteka<", "id=\"libraryForm\"",
                        "action=\"/dj/library\"", "id=\"libraryFile\"", "accept=\".xml,.m3u,.m3u8\"", "data-sending=\"Wgrywanie…\"",
                        "data-refused=\"Nie udało się wgrać pliku.", "id=\"libraryAnswer\" role=\"status\" hidden",
                        "Plik → Eksportuj kolekcję w formacie XML", ">Nie masz jeszcze wgranej biblioteki.<")
                .doesNotContain("action=\"/dj/library/delete\"");
        write("settings.html", html);
    }

    /** A library uploaded: how many tracks and when, and the way to delete it (written as {@code settings-library.html}). */
    @Test
    void theOwnersLibrary_saysItsTracksAndDay_andCanBeDeleted() throws IOException {
        DjService djService = mock(DjService.class);
        Instant uploaded = Instant.now();
        when(djService.libraryOf(PARTY)).thenReturn(java.util.Optional.of(
                new com.scan2play.service.DjLibraryService.Info(1234, com.scan2play.service.DjLibraryParser.Format.REKORDBOX, uploaded, "Kluby / Piątek")));

        String html = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL, com.scan2play.service.Plans.off(), djService);

        assertThat(html).contains("id=\"libraryState\"", ">Utwory w bibliotece: 1\u00A0234 · playlista „Kluby / Piątek” · wgrano " + com.scan2play.util.Times.day(uploaded) + "<",
                "id=\"libraryForm\"", "action=\"/dj/library/delete\"", "id=\"libraryDeleteForm\"",
                // "Usuń" small in the state's box (it asks first), our own file field, "Wgraj" disabled until a file is chosen
                "class=\"btn btn-sm btn-link text-danger s2p-library-delete\">Usuń<",
                "data-confirm=\"Usunąć bibliotekę? Znaczniki znikną z kolejki",
                "<label for=\"libraryFile\" class=\"s2p-file-drop\" id=\"libraryDrop\">", ">Wybierz plik<", ">XML, M3U lub M3U8 · do 64 MB<",
                "id=\"libraryUpload\" disabled", ">Jak przygotować plik?<");
        write("settings-library.html", html);
    }

    /**
     * "Twoje logo" (V45): without one the field to choose an image (any the browser reads, at most 2 MB), the preview hidden, "Wgraj"
     * disabled; with one the logo from our address and a small "Usuń" that asks first (written as {@code settings-logo.html}).
     */
    @Test
    void theOwnersLogo_canBeUploaded_shown_andDeleted() throws IOException {
        String none = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL);
        assertThat(none).contains("id=\"logoCard\"", "id=\"logoForm\"", "action=\"/dj/logo\"", ">Nie masz jeszcze logo.<",
                        "accept=\"image/png,image/jpeg,image/webp\"", ">PNG, JPEG lub WebP · do 2 MB<", "data-max-bytes=\"2097152\"",
                        "data-max-side=\"512\"", "id=\"logoPreview\" hidden", "id=\"logoUpload\" disabled", ">Brak<")
                .doesNotContain("id=\"logoDeleteForm\"", "id=\"logoCurrent\"");

        PartySettingsEntity withLogo = party().toBuilder().logoVersion("0123456789abcdef".repeat(4)).build();
        String html = render(new PartyStaffService.Access(withLogo, true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""), PL);
        assertThat(html).contains("src=\"/p/" + PARTY + "/logo.png?v=0123456789abcdef\"", "id=\"logoCurrent\"", "id=\"logoDeleteForm\"",
                "action=\"/dj/logo/delete\"", "data-confirm=\"Usunąć logo? Zniknie ze strony gości", ">Wgrane<");
        write("settings-logo.html", html);

        assertThat(render(staff(StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""), PL)).as("the organiser's alone")
                .doesNotContain("logoCard");
    }

    /** The use of each server limit is a badge the DJ notices: grey, yellow from 80 %, red at the limit. */
    @Test
    void theUseOfTheServerLimits_standsOut_andTurnsYellowThenRed() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(30, 10, 300, "");
        for (int i = 0; i < 24; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        String html = render(owner(), limiter, PL);
        assertThat(badge(html, "network")).contains("text-bg-warning").endsWith(">24/30");
        assertThat(badge(html, "party")).contains("text-bg-secondary").endsWith(">24/300");

        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("203.0.113.7", PARTY);
        }
        html = render(owner(), limiter, PL);
        assertThat(badge(html, "network")).contains("text-bg-danger").endsWith(">30/30");
    }

    /** The badge of one limit's use, from its opening tag to its text. */
    private static String badge(String html, String limit) {
        int at = html.indexOf("data-limit-use=\"" + limit + "\"");
        assertThat(at).as("the badge of " + limit).isPositive();
        int start = html.lastIndexOf("<span", at);
        return html.substring(start, html.indexOf("</span>", at));
    }

    /** A co-organiser (V32): the lists and the limits she was given, this device — never the profiles, the money or the staff. */
    @Test
    void aCoOrganiser_seesWhatSheWasGiven_notTheMoneyOrTheStaff() throws IOException {
        String html = render(staff(StaffRole.CO_ORGANISER.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL);

        assertThat(html).contains("id=\"hostListsCard\"", "id=\"limitsCard\"", "id=\"deviceCard\"", "id=\"pushToggle\"", "s2p-fold-state")
                .doesNotContain("id=\"linksCard\"", "action=\"/dj/dashboard/tip-link\"", "action=\"/dj/dashboard/dj-links\"",
                        "href=\"/dj/staff", "id=\"staffRow\"", "id=\"joinStaffForm\"", "id=\"libraryCard\"");
        write("settings-staff.html", html);
    }

    /**
     * "Zmień kod imprezy" (V49): the organiser's card, last, folded to the code; the form asks first ("Stare plakaty i karty przestaną
     * działać") and loads the page. After the change ({@code settings-code-changed.html}) the card is open with the new code and the
     * way to print it. A co-organiser never sees it. For the browser tests ({@code scenarios/party-code.js}).
     */
    @Test
    void theOrganiser_canChangeThePartyCode_aCoOrganiserCannot() throws IOException {
        String html = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL);
        assertThat(html).contains("id=\"partyCodeCard\"", ">Kod imprezy<", "id=\"partyCodeState\"", ">" + PARTY + "<",
                "action=\"/dj/dashboard/party-code\"", "data-confirm=\"Zmienić kod imprezy? Stare plakaty i karty przestaną działać.\"",
                ">Zmień kod imprezy<").doesNotContain("id=\"partyCodeChanged\"");
        assertThat(html.indexOf("id=\"partyCodeCard\"")).as("the last card").isGreaterThan(html.indexOf("id=\"deviceCard\""));

        String changed = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL, com.scan2play.service.Plans.off(),
                mock(DjService.class), Map.of(com.scan2play.controller.ViewAttributes.CODE_CHANGED, PARTY));
        assertThat(changed).contains("id=\"partyCodeChanged\"", "Nowy kod imprezy: " + PARTY + ".",
                "href=\"/dj/qr-print?party=" + PARTY + "\"").containsPattern("id=\"partyCodeCard\"[^>]*open");
        write("settings-code-changed.html", changed);

        assertThat(render(staff(StaffRole.CO_ORGANISER.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL))
                .doesNotContain("id=\"partyCodeCard\"", "/dj/dashboard/party-code");
    }

    /**
     * Someone of the staff with no settings ("Obsługa kolejki"): this device and, as they may open and close the party, its hours (V55)
     * — no row of jumps.
     */
    @Test
    void theStaffWithoutSettings_haveThisDeviceAlone() {
        String html = render(staff(StaffRole.QUEUE.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL);

        assertThat(html).contains("id=\"deviceCard\"", "id=\"pushToggle\"", "id=\"partyHoursCard\"")
                .doesNotContain("id=\"hostListsCard\"", "id=\"limitsCard\"", "id=\"linksCard\"", "id=\"settingsJump\"", "id=\"joinStaffForm\"");
    }

    /** A closed party that opens today at 20:00 and closes tomorrow at 3:00 (V55), seen on Friday at noon. */
    private static PartySettingsEntity planned() {
        PartySettingsEntity party = party();
        party.setActive(false);
        party.setNextOpenAt(DashboardPageRenderTest.OPEN_FRIDAY_20);
        party.setNextCloseAt(DashboardPageRenderTest.CLOSE_SATURDAY_3);
        return party;
    }

    /**
     * "Godziny imprezy" (V55): the first card, folded to the next moment; unfolded, the planned moments with "✕" (a form of its own,
     * so Enter in a field saves), the day and the time of each in Polish time, the quick choices, one "Zapisz" — a page load, back
     * here. Written as {@code settings-hours.html} for {@code scenarios/schedule.js}; nothing planned, it says "Nie ustawiono".
     */
    @Test
    void thePartysHours_areTheFirstCard() throws IOException {
        String html = render(new PartyStaffService.Access(planned(), true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""), PL);

        String card = html.substring(html.indexOf("id=\"partyHoursCard\""), html.indexOf("id=\"hostListsCard\""));
        assertThat(html.indexOf("id=\"partyHoursCard\"")).as("the first card").isLessThan(html.indexOf("id=\"hostListsCard\""));
        assertThat(card).contains("id=\"partyHoursState\">Otworzy się dziś o 20:00<", ">Godziny imprezy<",
                ">Teraz impreza jest zamknięta.<", "action=\"/dj/dashboard/schedule\"", "data-today=\"2026-10-30\"",
                "data-tomorrow=\"2026-10-31\"",
                "name=\"openDate\" value=\"2026-10-30\" min=\"2026-10-30\"", "name=\"openTime\" value=\"20:00\"",
                "name=\"closeDate\" value=\"2026-10-31\"", "name=\"closeTime\" value=\"03:00\"",
                ">⏰ Otworzy się dziś o 20:00<", ">⏰ Zamknie się jutro o 3:00<", "form=\"openHoursCancel\"",
                "id=\"openHoursCancel\"", "name=\"cancel\" value=\"close\"",
                "data-day=\"today\" data-time=\"20:00\" data-hours-fill=\"open\">dziś 20:00<",
                "data-day=\"tomorrow\" data-time=\"03:00\" data-hours-fill=\"close\">jutro 3:00<")
                .doesNotContain(" open=\"open\"", "id=\"partyHoursError\"");
        assertThat(card).contains("id=\"partyHoursNow\"", "data-open=\"Teraz impreza jest otwarta.\"",
                "data-closed=\"Teraz impreza jest zamknięta.\"");
        assertThat(card.indexOf("id=\"openHours\"")).as("\"Otwórz o\" on top").isLessThan(card.indexOf("id=\"closeHours\""));
        PartySettingsEntity closesFirst = planned();
        closesFirst.setActive(true);
        closesFirst.setNextOpenAt(java.time.Instant.parse("2026-10-31T19:00:00Z"));
        String open = render(new PartyStaffService.Access(closesFirst, true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""), PL);
        assertThat(open.indexOf("id=\"openHours\"")).as("\"Otwórz o\" on top, also when the close comes first")
                .isLessThan(open.indexOf("id=\"closeHours\""));
        assertThat(open).as("the folded line: the next moment").contains("id=\"partyHoursState\">Zamknie się jutro o 3:00<");
        assertThat(card.indexOf("id=\"partyHoursSave\"")).as("the save before the cancels: Enter saves")
                .isLessThan(card.indexOf("id=\"openHoursCancel\""));
        write("settings-hours.html", html);

        assertThat(render(owner(), new GuestRequestLimiter(30, 10, 300, ""), Locale.ENGLISH))
                .contains(">Party hours<", "id=\"partyHoursState\">Not set<", ">The party is open now.<", ">tomorrow 3:00<");
        assertThat(render(staff(Set.of(StaffPermission.QUEUE)), new GuestRequestLimiter(30, 10, 300, ""), PL))
                .as("without OPEN_CLOSE: no card").doesNotContain("id=\"partyHoursCard\"");
    }

    /** A plan refused (V55): the card open, why, and the fields as typed — not as saved. {@code settings-hours-error.html}. */
    @Test
    void aPlanRefused_saysWhy_withTheFieldsAsTyped() throws IOException {
        String html = render(new PartyStaffService.Access(planned(), true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""),
                PL, com.scan2play.service.Plans.off(), mock(DjService.class),
                Map.of("scheduleError", "schedule.error.past", "scheduleTyped", new String[]{"2026-10-29", "20:00", "", ""}));

        assertThat(html).contains("id=\"partyHoursCard\" open=\"open\"", ">Ta pora już minęła: wybierz późniejszą.<",
                "name=\"openDate\" value=\"2026-10-29\"", "name=\"closeDate\" value=\"\"",
                "class=\"small text-danger mt-1\" role=\"alert\" id=\"partyHoursError\"");
        assertThat(html.indexOf("id=\"partyHoursError\"")).as("why: under \"Zapisz\", as every card")
                .isGreaterThan(html.indexOf("id=\"partyHoursSave\""));
        write("settings-hours-error.html", html);

        assertThat(render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL, com.scan2play.service.Plans.off(), mock(DjService.class),
                Map.of("scheduleSaved", true))).as("saved: the card open, no sentence — \"✓\" on the button, as every card")
                .contains("id=\"partyHoursCard\" open=\"open\"").doesNotContain("id=\"partyHoursError\"", "Zapisano");
    }

    /**
     * The venue's kiosk (V56): a card folded to "Włączony" / "Wyłączony"; with a link — to copy, to open on the tablet, made again
     * (asking first) or taken away, page loads; without — "Utwórz link kiosku". Whoever has KIOSK; not the queue's staff.
     * {@code settings-kiosk.html} for the browser tests.
     */
    @Test
    void theKiosk_hasACard_withItsLink() throws IOException {
        PartySettingsEntity withKiosk = party();
        withKiosk.setKioskToken("Kiosk_123-xyzKiosk_123-x");
        String html = render(new PartyStaffService.Access(withKiosk, true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""), PL);
        String card = html.substring(html.indexOf("id=\"kioskCard\""), html.indexOf("id=\"linksCard\""));
        assertThat(card).contains(">Kiosk dla gości<", "id=\"kioskState\">Włączony<",
                "value=\"http://localhost:8080/k/Kiosk_123-xyzKiosk_123-x\" id=\"kioskLinkInput\"", "data-copy-target=\"kioskLinkInput\"",
                "id=\"kioskOpen\"", "href=\"http://localhost:8080/k/Kiosk_123-xyzKiosk_123-x\" target=\"_blank\"",
                "action=\"/dj/dashboard/kiosk-link\"", "data-confirm=\"Utworzyć nowy link? Tablet ze starym linkiem przestanie działać.\"",
                ">Nowy link<", "id=\"kioskLinkOffBtn\"", "Dostęp nadzorowany");
        write("settings-kiosk.html", html);

        assertThat(render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL)).contains("id=\"kioskState\">Wyłączony<", ">Utwórz link kiosku<")
                .doesNotContain("id=\"kioskLinkInput\"", "id=\"kioskLinkOffBtn\"");
        assertThat(render(staff(StaffRole.QUEUE.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL))
                .as("the queue's staff: not theirs").doesNotContain("id=\"kioskCard\"");
        assertThat(render(staff(StaffRole.CO_ORGANISER.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL))
                .as("a co-organiser: theirs").contains("id=\"kioskCard\"");
    }

    @Test
    void inEnglish() {
        String html = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), Locale.ENGLISH);
        assertThat(html).contains("<html lang=\"en\">", ">Party settings<", ">Request notifications<", ">Requests per guest<",
                ">per 10 min<", ">per 24 h<", "One network is e.g. the venue’s Wi-Fi");
    }

    // ---------------------------------------------------------------- the plans (V35)

    @Test
    void withThePlansNotEnforced_nothingSaysPro() {
        assertThat(render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL))
                .doesNotContain("s2p-pro", "data-pro-offer", "W planie Pro", "Zobacz, co daje Pro");
    }

    /**
     * An organiser without Pro, the plans enforced (variant B): each card of a Pro feature keeps its title with "PRO" and says, in
     * place of its form, that what was saved waits (the lists, the profiles) or what holds (the limits: 2 / 3 / 15), with the way to
     * /pro; no form of it is on the page. {@code settings-free.html} for the browser tests.
     */
    @Test
    void aFreeOrganiser_seesEachProCard_withWhatWaits_andNoForm() throws IOException {
        PartySettingsEntity saved = party();
        saved.setRequestLimit(5);
        String html = render(new PartyStaffService.Access(saved, true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""),
                PL, com.scan2play.service.Plans.enforced());

        String hosts = html.substring(html.indexOf("id=\"hostListsCard\""), html.indexOf("</summary>", html.indexOf("id=\"hostListsCard\"")));
        assertThat(hosts).as("a folded card of a Pro feature: \"PRO\" by its heading, said once — no state beside it")
                .contains("class=\"s2p-pro\">PRO<").doesNotContain("s2p-fold-state");
        assertThat(html).as("the hosts' lists: kept (⭐ 1), not editable here, the link not shown")
                .contains("id=\"hostListsCard\"", "Twoje listy są zachowane: 🚫 0 · ⭐ 1. Wrócą, gdy włączysz Pro",
                        "href=\"/pro\"", ">Zobacz, co daje Pro<")
                .doesNotContain("id=\"hostListsForm\"", "action=\"/dj/dashboard/host-lists\"", "id=\"hostLinkInput\"",
                        "action=\"/dj/dashboard/host-link\"", ">Hej sokoły</textarea>");
        assertThat(html).as("the profiles and the tip link: kept, not editable")
                .contains("id=\"linksCard\"", "Twoje profile i link do napiwków są zachowane. Wrócą, gdy włączysz Pro.")
                .doesNotContain("id=\"djLinksForm\"", "id=\"tipLinkForm\"", "dj.koko", "buycoffee.to/djkoko");
        assertThat(html).as("the limits: what holds without Pro, the server's use still there")
                .contains("id=\"limitsCard\"", "Każda impreza bez Pro: 2 prośby na gościa, potem 3 min przerwy, bez powtórek z ostatnich 15.",
                        "id=\"serverLimitsInfo\"")
                .doesNotContain("action=\"/dj/dashboard/limits\"", "id=\"requestLimit\"");
        assertThat(html).as("the library: what it would do, no form")
                .contains("id=\"libraryCard\"", "Wgraj swoją bibliotekę, a przy każdej prośbie zobaczysz, czy masz ten utwór.")
                .doesNotContain("id=\"libraryForm\"", "action=\"/dj/library\"");
        assertThat(html).as("the kiosk (V56): the offer, no link to make").contains("id=\"kioskCard\"",
                "Kiosk dla gości bez telefonu — w planie Pro.").doesNotContain("action=\"/dj/dashboard/kiosk-link\"");
        assertThat(html).contains("id=\"deviceCard\"", "id=\"pushToggle\"");
        write("settings-free.html", html);
    }

    /** A new organiser without Pro and nothing saved: "Dostępne w planie Pro." and what the card would do. */
    @Test
    void aFreeOrganiserWithNothingSaved_isToldWhatTheCardsDo() {
        PartySettingsEntity fresh = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).globalVibe(VibeType.ANY).build();
        String html = render(new PartyStaffService.Access(fresh, true, StaffPermission.all()), new GuestRequestLimiter(30, 10, 300, ""),
                PL, com.scan2play.service.Plans.enforced());
        assertThat(html).contains(">Dostępne w planie Pro.<", "Przyciski Twoich profili i „Napiwek dla DJ-a” u gości i na wydruku — w planie Pro.")
                .doesNotContain("zachowane");
    }

    /** A co-organiser of a party without Pro: no card of a Pro feature at all (hidden: not hers to buy). */
    @Test
    void aCoOrganiserOfAFreeParty_seesNoProCard() {
        String html = render(staff(StaffRole.CO_ORGANISER.permissions()), new GuestRequestLimiter(30, 10, 300, ""), PL,
                com.scan2play.service.Plans.enforced());
        assertThat(html).contains("id=\"deviceCard\"")
                .doesNotContain("id=\"hostListsCard\"", "id=\"limitsCard\"", "s2p-pro", "id=\"staffRow\"");
    }

    /** A library uploaded under Pro, the plan gone: it waits, unmarked — and it can still be deleted (the organiser's own data). */
    @Test
    void aFreeOrganisersLibrary_waits_andCanBeDeleted() {
        DjService djService = mock(DjService.class);
        when(djService.libraryOf(PARTY)).thenReturn(java.util.Optional.of(
                new com.scan2play.service.DjLibraryService.Info(10, com.scan2play.service.DjLibraryParser.Format.M3U, Instant.now(), null)));

        String html = render(owner(), new GuestRequestLimiter(30, 10, 300, ""), PL, com.scan2play.service.Plans.enforced(), djService);

        assertThat(html).contains("Twoja biblioteka jest zapisana — znaczniki w kolejce wrócą z planem Pro.", "action=\"/dj/library/delete\"")
                .doesNotContain("id=\"libraryForm\"");
    }
}
