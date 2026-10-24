package com.scan2play.template;

import com.scan2play.controller.DemoController;
import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.VibeType;
import com.scan2play.service.DemoParty;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.SiteCounter;
import com.scan2play.service.StaffInvitationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The demo's page (DemoController, the owner 2026-10-24) as the real controllers build it and the real template renders it: the
 * panel of the demo party, only to look at — no DJ's bar, the demo's bar and note, the page marked {@code data-demo} for its script —,
 * and of the waiting requests the examples and this visitor's own, never another visitor's. Leaves {@code dashboard-demo.html} in
 * {@code target/browser-harness/} for the browser tests (the scenario {@code demo-sends-nothing}).
 */
class DemoPageRenderTest {

    private static final Locale PL = Locale.forLanguageTag("pl");
    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/156.0 Safari/537.36";

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

    private static SongRequestEntity request(String code, long id, String song, boolean checked, int number) {
        return SongRequestEntity.builder().id(id).partyCode(code).songName(song).guestText(song.toLowerCase())
                .decision("accepted").votes(1).requestNumber(number).checked(checked).djComment("ok")
                .requestedAt(Instant.now()).build();
    }

    private final SiteCounter siteCounter = mock(SiteCounter.class);

    /** The demo page for a visitor whose session sent request 99 (request 98 is another visitor's). */
    private String render(Locale locale) {
        String code = DemoParty.codeFor(locale);
        PartySettingsEntity demo = PartySettingsEntity.builder().partyCode(code).ownerId("scan2play-demo").active(true)
                .djName("DJ Demo").globalVibe(VibeType.ANY).hostWanted("Wilki - Baśka").build();
        List<SongRequestEntity> waiting = List.of(request(code, 1, "Wilki - Baśka", true, 1), request(code, 2, "ABBA - Dancing Queen", true, 2),
                request(code, 98, "Another visitor's words", false, 3), request(code, 99, "My own request", false, 4));

        DjService djService = mock(DjService.class);
        when(djService.getWaiting(code)).thenReturn(waiting);
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.staffOf(code)).thenReturn(List.of());
        QrCodeService qr = mock(QrCodeService.class);
        when(qr.generateQrCodeBase64(anyString(), anyInt(), anyInt())).thenReturn(null);
        DjDashboardController panel = new DjDashboardController(djService, mock(PartySettingsCommandService.class), qr,
                mock(DjSessionHelper.class), mock(PlayHistoryService.class), new GuestRequestLimiter(30, 10, 300, ""),
                mock(PushNotificationService.class), staff, mock(StaffInvitationService.class), com.scan2play.service.Plans.off(), new com.scan2play.service.AiHealthMonitor());
        ReflectionTestUtils.setField(panel, "rawBaseUrl", "http://localhost:8080/");
        panel.init();

        PartySettingsQueryService settings = mock(PartySettingsQueryService.class);
        when(settings.getSettings(code)).thenReturn(demo);
        MockHttpSession session = new MockHttpSession();
        GuestSessionService guestSessions = mock(GuestSessionService.class);
        when(guestSessions.myRequestIds(any(), any())).thenReturn(Set.of(99L));
        DemoController controller = new DemoController(panel, settings, djService, guestSessions, siteCounter);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        ConcurrentModel model = new ConcurrentModel();
        String view = controller.demo(model, request, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "demo-token"), locale, CHROME);
        assertThat(view).isEqualTo("dashboard");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        context.setVariable("langSwitch", new com.scan2play.controller.LanguageSwitchAdvice.LanguageSwitch("/demo?lang=pl", "/demo?lang=en"));
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        return engine.process(view, context);
    }

    @Test
    void theDemo_isThePanelToLookAt_withTheExamplesAndTheVisitorsOwn() throws IOException {
        String html = render(PL);

        assertThat(html).contains("<body class=\"page-dj\" data-demo=\"true\">", "id=\"demoBar\"", "id=\"demoNotice\"",
                "To jest demo: przykładowa impreza", "<a href=\"/\" class=\"s2p-home-logo\"", "title=\"Scan2Play — strona główna\"",
                "id=\"demoHome\" class=\"btn btn-sm btn-outline-light text-nowrap\">← Strona główna<", "<title>Demo — Scan2Play</title>", "Demo: przykładowa impreza. Wyślij prośbę", "href=\"/p/DEMO\"", "href=\"/p/DEMO/tv?lang=pl\"", "href=\"/start\"",
                "src=\"/js/dashboard/main.js\"");
        assertThat(html).as("no DJ's bar: no logout, no end of the party").doesNotContain("/dj/logout", "id=\"end-party-form\"");
        assertThat(html).contains("Wilki - Baśka", "ABBA - Dancing Queen", "My own request")
                .as("another visitor's words never").doesNotContain("Another visitor");
        assertThat(html).as("the visitor's own request is not marked unchecked in the demo").doesNotContain("s2p-request-unchecked");
        assertThat(html).doesNotContain("??");
        verify(siteCounter).countVisit(SiteCounter.Event.DEMO, CHROME);

        Path out = Path.of("target", "browser-harness");
        Files.createDirectories(out);
        Files.writeString(out.resolve("dashboard-demo.html"), html, StandardCharsets.UTF_8);
    }

    @Test
    void inEnglish_theBarSpeaksEnglish_andShowsTheEnglishDemo() {
        // the English demo party (the owner, 2026-10-24): its own examples and comments; its guest page and TV
        assertThat(render(Locale.ENGLISH)).contains("This is a demo: an example party", "Start your own party →", "href=\"/p/DEME\"",
                "href=\"/p/DEME/tv?lang=en\"", "value=\"DEME\"").doesNotContain("??", "/p/DEMO\"");
        assertThat(render(Locale.GERMAN)).as("any other language: the English demo").contains("href=\"/p/DEME\"");
    }
}
