package com.scan2play.template;

import com.scan2play.controller.DjDashboardController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The page to print the party's QR code ({@code qr-print.html}, {@link DjDashboardController#qrPrint}): rendered through the real
 * controller, the real QR generator and the real message bundles. The printed part is in Polish and English whatever the DJ's
 * language; the bar above it follows that language. The pages are also written to {@code target/qr-print/} for a look in a browser.
 */
class QrPrintPageTest {

    private static final String PARTY = "PRNT1";
    private static final Path OUT = Path.of("target", "qr-print");
    private static final Path BROWSER_HARNESS = Path.of("target", "browser-harness");

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

    /**
     * The party of the pages the browser tests get: with all three of the DJ's profiles (V24) — the most a card's column under the
     * code must hold (qr-print-cards-layout).
     */
    private static String render(String layout, Locale locale) throws IOException {
        return render(layout, locale, PartySettingsEntity.builder().partyCode(PARTY)
                .instagramUrl("https://www.instagram.com/dj.koko/").tiktokUrl("https://www.tiktok.com/@dj_koko")
                .facebookUrl("https://www.facebook.com/djkoko").tipUrl("https://revolut.me/djkoko").build());
    }

    private static String render(String layout, Locale locale, PartySettingsEntity party) throws IOException {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.access(any(), any(), any())).thenReturn(new PartyStaffService.Access(party, true, StaffPermission.all()));
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
                new QrCodeService(), sessionHelper, mock(PlayHistoryService.class), new GuestRequestLimiter(30, 10, 300, ""),
                mock(PushNotificationService.class), mock(PartyStaffService.class));
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "https://www.scan2play.com.pl/");
        controller.init();

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.qrPrint(layout, null, model, ownerToken(), new MockHttpSession());
        assertThat(view).isEqualTo("qr-print");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        String html = engine.process(view, context);
        Files.createDirectories(OUT);
        // the stylesheet and the script by a relative path, so that the file can be opened straight from target/
        Files.writeString(OUT.resolve(layout + "-" + locale.getLanguage() + ".html"),
                html.replace("/css/qr-print.css", "../../src/main/resources/static/css/qr-print.css")
                        .replace("/js/qr-print.js", "../../src/main/resources/static/js/qr-print.js"),
                StandardCharsets.UTF_8);
        // as served, for the browser tests (src/test/browser, page 'qr-print-<layout>'): the real script under the real CSP
        Files.createDirectories(BROWSER_HARNESS);
        Files.writeString(BROWSER_HARNESS.resolve("qr-print-" + layout + ".html"), html, StandardCharsets.UTF_8);
        return html;
    }

    @Test
    void thePosterHasTheCodeTheLinkAndBothLanguages() throws IOException {
        String html = render("poster", Locale.forLanguageTag("pl"));

        assertThat(html).contains("class=\"layout-poster\"", "<main class=\"poster\">", "data:image/png;base64,");
        assertThat(html).contains("https://www.scan2play.com.pl/p/" + PARTY, ">" + PARTY + "<");
        assertThat(html).contains("<span lang=\"pl\">Zeskanuj i zamów piosenkę</span>", "<span lang=\"en\" class=\"second\">Scan to request a song</span>");
        // our logo once, between the title and the code
        assertThat(html).containsOnlyOnce("<p class=\"logo\"><img src=\"/images/logo.svg\" alt=\"\"><span>Scan<span class=\"two\">2</span>Play</span></p>");
        assertThat(html.indexOf("</h1>")).isLessThan(html.indexOf("class=\"logo\""));
        assertThat(html.indexOf("class=\"logo\"")).isLessThan(html.indexOf("class=\"qr\""));
        assertThat(html).doesNotContain("class=\"cards\"", "??");
    }

    @Test
    void theCardsAreEightOnAPage() throws IOException {
        String html = render("cards", Locale.ENGLISH);

        assertThat(html).contains("class=\"layout-cards\"", "<main class=\"cards\">");
        assertThat(html.split("<section class=\"card\">", -1)).hasSize(9);
        assertThat(html.split("data:image/png;base64,", -1)).as("a code on every card").hasSize(9);
        assertThat(html.split("<p class=\"logo\"><img src=\"/images/logo.svg\"", -1)).as("our logo on every card").hasSize(9);
        assertThat(html).contains("Zeskanuj i zamów piosenkę", "Scan to request a song").doesNotContain("??");
    }

    @Test
    void theDjsProfiles_areUnderTheCode_asAGuestReadsThem() throws IOException {
        String poster = render("poster", Locale.forLanguageTag("pl"));
        assertThat(poster).containsOnlyOnce("class=\"dj-links\"")
                .contains("<span>Instagram @dj.koko</span>", "<span>TikTok @dj_koko</span>", "<span>Facebook djkoko</span>");
        assertThat(poster.indexOf("class=\"code\"")).isLessThan(poster.indexOf("class=\"dj-links\""));
        // the tip link (V27) under them, as a guest types it, in both languages; on a card the last line of the texts
        assertThat(poster.replaceAll("\\s+", " ")).containsOnlyOnce("<p class=\"tip\">")
                .contains("<span lang=\"pl\">Napiwek</span> / <span lang=\"en\">Tip</span>: <strong>revolut.me/djkoko</strong>");
        assertThat(poster.indexOf("class=\"dj-links\"")).isLessThan(poster.indexOf("class=\"tip\""));

        // a card: the code's column has our logo above the code and the profiles under it (the owner, 2026-10-07)
        String cards = render("cards", Locale.ENGLISH);
        assertThat(cards.split("class=\"dj-links\"", -1)).as("on every card").hasSize(9);
        String card = cards.substring(cards.indexOf("<section class=\"card\">"), cards.indexOf("</section>"));
        String side = card.substring(card.indexOf("<div class=\"side\">"), card.indexOf("<div class=\"text\">"));
        assertThat(side.indexOf("class=\"logo\"")).isNotNegative().isLessThan(side.indexOf("class=\"qr\""));
        assertThat(side.indexOf("class=\"qr\"")).isLessThan(side.indexOf("class=\"dj-links\""));

        String number = render("whatever", Locale.ENGLISH, PartySettingsEntity.builder().partyCode(PARTY)
                .facebookUrl("https://www.facebook.com/profile.php?id=100012345678").build());
        assertThat(number).as("a Facebook page known only by its number: nothing to read on paper").doesNotContain("dj-links", "Facebook");
        String none = render("whatever", Locale.ENGLISH, PartySettingsEntity.builder().partyCode(PARTY).build());
        assertThat(none).doesNotContain("dj-links");
    }

    @Test
    void theBarFollowsTheDjsLanguage_andTheLayoutDefaultsToThePoster() throws IOException {
        String polish = render("whatever", Locale.forLanguageTag("pl"));
        assertThat(polish).contains("<html lang=\"pl\"", "Wydruk kodu QR", "Plakat A4", "Drukuj", "class=\"layout-poster\"");

        String english = render("poster", Locale.ENGLISH);
        assertThat(english).contains("<html lang=\"en\"", "Print the QR code", "Table cards", "Back to the dashboard");
        assertThat(english).contains("id=\"printBtn\"", "src=\"/js/qr-print.js\"").doesNotContain("onclick");
    }

    private static OAuth2AuthenticationToken ownerToken() {
        return new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }
}
