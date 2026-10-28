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
import java.util.List;
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
        return render(layout, locale, party, new PartyStaffService.Access(party, true, StaffPermission.all()));
    }

    private static String render(String layout, Locale locale, PartySettingsEntity party, PartyStaffService.Access access)
            throws IOException {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.access(any(), any(), any())).thenReturn(access);
        DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
                new QrCodeService(), sessionHelper, mock(PlayHistoryService.class), new GuestRequestLimiter(30, 10, 300, ""),
                mock(PushNotificationService.class), mock(PartyStaffService.class), mock(com.scan2play.service.StaffInvitationService.class), com.scan2play.service.Plans.off(), new com.scan2play.service.AiHealthMonitor());
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "https://www.scan2play.com.pl/");
        controller.init();

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.qrPrint(layout, null, model, ownerToken(), new MockHttpSession(), locale);
        assertThat(view).isEqualTo("qr-print");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        // the editor's save (V46) sends the token in its header, read from the page's <meta>, as the real page has it
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        String html = engine.process(view, context);
        Files.createDirectories(OUT);
        // the stylesheet and the script by a relative path, so that the file can be opened straight from target/
        Files.writeString(OUT.resolve(layout + "-" + locale.getLanguage() + ".html"),
                html.replace("/css/qr-print.css", "../../src/main/resources/static/css/qr-print.css")
                        .replace("/js/qr-print.js", "../../src/main/resources/static/js/qr-print.js"),
                StandardCharsets.UTF_8);
        // as served, for the browser tests (src/test/browser, page 'qr-print-<layout>'): the real script under the real CSP
        Files.createDirectories(BROWSER_HARNESS);
        String variant = (party.getLogoVersion() != null ? "-logo" : "")
                + (party.getPosterHeading() != null || party.getPosterLine() != null || party.getPosterLanguage() != com.scan2play.model.PosterLanguage.AUTO ? "-own" : "")
                + (access.owner() ? "" : "-staff");
        Files.writeString(BROWSER_HARNESS.resolve("qr-print-" + layout + variant + ".html"), html, StandardCharsets.UTF_8);
        return html;
    }

    @Test
    void thePosterHasTheCodeTheLinkAndBothLanguages() throws IOException {
        String html = render("poster", Locale.forLanguageTag("pl"));

        assertThat(html).contains("class=\"layout-poster theme-classic has-editor\"", "<main class=\"poster\">", "data:image/png;base64,");
        assertThat(html).contains("https://www.scan2play.com.pl/p/" + PARTY, ">" + PARTY + "<");
        // a Polish panel: Polish and English at once; "poproś", as the TV screen and the rest of the app ("prośba"), not "zamów"
        assertThat(html).contains("<span lang=\"pl\">Zeskanuj i poproś o piosenkę</span>", "<span lang=\"en\" class=\"second\">Scan to request a song</span>")
                .doesNotContain("zamów");
        // our logo once, between the title and the code
        assertThat(html).containsOnlyOnce("<p class=\"logo\" data-poster-part=\"ours\"><img src=\"/images/logo.svg\" alt=\"\"><span>Scan<span class=\"two\">2</span>Play</span></p>");
        assertThat(html.indexOf("</h1>")).isLessThan(html.indexOf("class=\"logo\""));
        assertThat(html.indexOf("class=\"logo\"")).isLessThan(html.indexOf("class=\"qr\""));
        assertThat(html).doesNotContain("class=\"cards\"", "??");
        // no numbered steps under the code: the heading says what to do, and the guest page asks for the artist and the title
        assertThat(html).doesNotContain("class=\"steps\"", "Zeskanuj kod aparatem", "DJ zagra Twoją piosenkę", "tekstu", "lyrics");
    }

    @Test
    void theCardsAreEightOnAPage() throws IOException {
        String html = render("cards", Locale.ENGLISH);

        assertThat(html).contains("class=\"layout-cards theme-classic has-editor\"", "<main class=\"cards\">");
        assertThat(html.split("<section class=\"card\">", -1)).hasSize(9);
        assertThat(html.split("data:image/png;base64,", -1)).as("a code on every card").hasSize(9);
        assertThat(html.split("<p class=\"logo\" data-poster-part=\"ours\"><img src=\"/images/logo.svg\"", -1)).as("our logo on every card").hasSize(9);
        // the heading alone says what to do, as on the poster: the guest page asks for the artist and the title
        assertThat(html).contains("Scan to request a song").doesNotContain("??", "lyrics", "The artist and the title");
        assertThat(html).as("an English panel: English alone").doesNotContain("lang=\"pl\"", "Zeskanuj", "Wykonawca", "Kod", "class=\"second\"");
        assertThat(render("cards", Locale.forLanguageTag("pl"))).contains("Zeskanuj i poproś o piosenkę",
                "<span lang=\"en\" class=\"second\">Scan to request a song</span>").doesNotContain("Wykonawca i tytuł");
    }

    /**
     * The owner, 2026-10-23: a DJ abroad printed a poster whose big words were Polish. A panel in any language but Polish prints
     * English alone, as the primary text; a Polish panel keeps both (a Polish party's guests are of both).
     */
    @Test
    void anEnglishPanel_printsAnEnglishPoster() throws IOException {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            String html = render("poster", locale).replaceAll("\\s+", " ");
            assertThat(html).as(locale.toString()).contains("<span lang=\"en\">Scan to request a song</span>",
                    "<span lang=\"en\">Can't scan? Open:</span>",
                    "<span lang=\"en\">Party code</span>: <strong>" + PARTY + "</strong>", "<span lang=\"en\">Tip</span>: <strong>",
                    "alt=\"QR code\"");
            assertThat(html).as(locale.toString()).doesNotContain("lang=\"pl\"", "class=\"second\"", "Zeskanuj", "Kod imprezy",
                    "Napiwek", "kod QR");
        }
    }

    /**
     * The DJ's logo (V45, Pro): the poster's head, above the code on every card in place of ours; ours then a quiet line at the poster's foot (written as
     * {@code qr-print-poster-logo.html} / {@code qr-print-cards-logo.html}: the browser tests check both still fit their paper).
     */
    @Test
    void theDjsLogo_isAtThePostersTop_andOnEveryCard() throws IOException {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).logoVersion("0123456789abcdef".repeat(4))
                .instagramUrl("https://www.instagram.com/dj.koko/").tiktokUrl("https://www.tiktok.com/@dj_koko")
                .facebookUrl("https://www.facebook.com/djkoko").tipUrl("https://revolut.me/djkoko").build();
        String logo = "src=\"/p/" + PARTY + "/logo.png?v=0123456789abcdef\"";

        String poster = render("poster", Locale.forLanguageTag("pl"), party);
        // ours rendered hidden while the DJ's shows: the editor's "logo" box brings it back at once
        assertThat(poster).containsOnlyOnce(logo).containsOnlyOnce("class=\"logo ours\"")
                .containsOnlyOnce("<p class=\"logo\" data-poster-part=\"ours\" hidden=\"hidden\">");
        assertThat(poster).contains("<main class=\"poster with-logo\">", "class=\"powered\"");
        assertThat(poster.indexOf("class=\"powered\"")).as("ours at the foot").isGreaterThan(poster.indexOf("class=\"tip\""));
        assertThat(poster.indexOf(logo)).isLessThan(poster.indexOf("<h1>"));
        String cards = render("cards", Locale.forLanguageTag("pl"), party);
        assertThat(cards.split(java.util.regex.Pattern.quote(logo), -1)).as("on every card").hasSize(9);
        assertThat(cards.split("<section class=\"card with-logo\">", -1)).as("the code made room on every card").hasSize(9);
        assertThat(cards).as("ours not shown on a card beside the DJ's").doesNotContain("<p class=\"logo\" data-poster-part=\"ours\">");

        assertThat(render("poster", Locale.ENGLISH)).doesNotContain("logo.png", "logo ours");
    }

    @Test
    void theDjsProfiles_areUnderTheCode_asAGuestReadsThem() throws IOException {
        String poster = render("poster", Locale.forLanguageTag("pl"));
        assertThat(poster).containsOnlyOnce("class=\"dj-links\"")
                .contains("<span>Instagram @dj.koko</span>", "<span>TikTok @dj_koko</span>", "<span>Facebook djkoko</span>");
        assertThat(poster.indexOf("class=\"code\"")).isLessThan(poster.indexOf("class=\"dj-links\""));
        // the tip link (V27) under them, as a guest types it, in both languages; on a card the last line of the texts
        assertThat(poster.replaceAll("\\s+", " ")).containsOnlyOnce("<p class=\"tip\" data-poster-part=\"tip\">")
                .contains("<span lang=\"pl\">Napiwek</span> / <span lang=\"en\">Tip</span>: <strong>revolut.me/djkoko</strong>");
        assertThat(poster.indexOf("class=\"dj-links\"")).isLessThan(poster.indexOf("class=\"tip\""));

        // a card: the code's column has our logo above the code and the profiles under it (the owner, 2026-10-07)
        String cards = render("cards", Locale.ENGLISH);
        assertThat(cards.split("class=\"dj-links\"", -1)).as("on every card").hasSize(9);
        int start = cards.indexOf("<section class=\"card\">");
        String card = cards.substring(start, cards.indexOf("</section>", start));
        String side = card.substring(card.indexOf("<div class=\"side\">"), card.indexOf("<div class=\"text\">"));
        assertThat(side.indexOf("class=\"logo\"")).isNotNegative().isLessThan(side.indexOf("class=\"qr\""));
        assertThat(side.indexOf("class=\"qr\"")).isLessThan(side.indexOf("class=\"dj-links\""));

        String number = render("whatever", Locale.ENGLISH, PartySettingsEntity.builder().partyCode(PARTY)
                .facebookUrl("https://www.facebook.com/profile.php?id=100012345678").build());
        assertThat(number).as("a Facebook page known only by its number: nothing to read on paper").doesNotContain("dj-links", "Facebook");
        String none = render("whatever", Locale.ENGLISH, PartySettingsEntity.builder().partyCode(PARTY).build());
        assertThat(none).doesNotContain("dj-links");
    }

    /**
     * The print as the DJ set it (V46): the DJ's own heading in place of ours (ours rendered hidden, for the editor's preview), the DJ's
     * line, the language whatever the panel's, and the parts left off rendered hidden.
     */
    @Test
    void thePrint_isAsTheDjSetIt() throws IOException {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).posterHeading("Osiemnastka Dawida")
                .posterLine("Sobota, sala Pod Lipami").posterLanguage(com.scan2play.model.PosterLanguage.PL)
                .instagramUrl("https://www.instagram.com/dj.koko/").tipUrl("https://revolut.me/djkoko")
                .posterShowLinks(false).posterShowAddress(false).build();

        String poster = render("poster", Locale.ENGLISH, party);
        assertThat(poster).contains("<span class=\"own\" data-poster-heading>Osiemnastka Dawida</span>",
                "<span class=\"ours\" data-poster-ours-heading hidden=\"hidden\">",
                "<p class=\"own-line\" data-poster-line>Sobota, sala Pod Lipami</p>",
                "class=\"dj-links\" data-poster-part=\"links\" hidden=\"hidden\"",
                "<p class=\"link\" data-poster-part=\"address\" hidden=\"hidden\">",
                "<p class=\"tip\" data-poster-part=\"tip\">", "alt=\"Kod QR\"");
        // the page itself (its <html lang>, the bar, the editor) is in the panel's language; the printed part is the print's
        assertThat(poster.substring(poster.indexOf("<main"))).as("Polish alone, on an English panel").doesNotContain("lang=\"en\"", "class=\"second\"");

        String english = render("cards", Locale.forLanguageTag("pl"), party.toBuilder()
                .posterLanguage(com.scan2play.model.PosterLanguage.EN).posterHeading(null).posterLine(null).build());
        assertThat(english.substring(english.indexOf("<main"))).as("English alone, on a Polish panel").doesNotContain("lang=\"pl\"", "class=\"second\"")
                .contains("<span lang=\"en\">Scan to request a song</span>",
                        "<span class=\"own\" data-poster-heading hidden=\"hidden\"></span>",
                        "<p class=\"small own-line\" data-poster-line hidden=\"hidden\"></p>");

        String both = render("poster", Locale.ENGLISH, party.toBuilder().posterLanguage(com.scan2play.model.PosterLanguage.BOTH).build());
        assertThat(both).contains("<span lang=\"pl\">Kod imprezy</span> / ",
                "<span lang=\"en\" class=\"second\">Can't scan? Open:</span>");
    }

    /**
     * The look (V47): the theme is the page's class, the DJ's colour its data-accent (qr-print.js makes it readable); the editor offers
     * the four themes and the palette, the saved one picked — a colour not of the palette is "Własny", in the colour field.
     */
    @Test
    void theLook_isTheThemeAndTheColourTheDjPicked() throws IOException {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY)
                .posterTheme(com.scan2play.model.PosterTheme.ELEGANT).posterAccent("#123abc").build();
        String html = render("poster", Locale.forLanguageTag("pl"), party).replaceAll("\\s+", " ");
        assertThat(html).contains("class=\"layout-poster theme-elegant has-editor\"", "data-accent=\"#123abc\"",
                "<option value=\"ELEGANT\" selected=\"selected\">Elegancki</option>", "<option value=\"CLUB\">Klubowy</option>",
                "<option value=\"PASTEL\">Pastelowy</option>", "<option value=\"CLASSIC\">Klasyczny</option>",
                "value=\"#123abc\" checked=\"checked\"", "id=\"posterAccentPicker\" value=\"#123abc\"", "class=\"swatch-dot swatch-c2185b\"");
        String palette = render("poster", Locale.forLanguageTag("pl"), party.toBuilder().posterAccent("#c2185b").build());
        assertThat(palette).contains("value=\"#c2185b\" aria-label=\"#c2185b\" checked=\"checked\"").doesNotContain("value=\"#123abc\"");
        String none = render("poster", Locale.ENGLISH);
        assertThat(none).contains("class=\"layout-poster theme-classic has-editor\"", "<input type=\"radio\" name=\"accent\" value=\"\" checked=\"checked\">")
                .doesNotContain("data-accent=");
    }

    /** The editor (V46): its fields as long as the columns, the values saved; only for whom may change the card "Impreza". */
    @Test
    void theEditor_isForWhomMayChangeTheParty() throws IOException {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).posterHeading("Osiemnastka Dawida").build();
        String html = render("poster", Locale.forLanguageTag("pl"), party);
        assertThat(html).contains("id=\"posterForm\"", "action=\"/dj/qr-print/settings\"",
                "maxlength=\"" + PartySettingsEntity.POSTER_HEADING_MAX + "\"", "maxlength=\"" + PartySettingsEntity.POSTER_LINE_MAX + "\"",
                "value=\"Osiemnastka Dawida\"", "<option value=\"AUTO\" selected=\"selected\">Jak panel</option>",
                "name=\"showAddress\" value=\"true\" data-poster-toggle=\"address\" checked=\"checked\"");
        assertThat(html).as("no logo, no profiles, no tip: no box for them, what was saved sent as it is")
                .doesNotContain("data-poster-toggle=\"logo\"", "data-poster-toggle=\"tip\"")
                .contains("<input type=\"hidden\" name=\"showLogo\" value=\"true\">");

        String viewer = render("poster", Locale.forLanguageTag("pl"), party,
                new PartyStaffService.Access(party, false, java.util.EnumSet.of(StaffPermission.QUEUE)));
        assertThat(viewer).as("the staff without VIBE print it, never change it").doesNotContain("posterForm", "has-editor")
                .contains("<span class=\"own\" data-poster-heading>Osiemnastka Dawida</span>");
    }

    @Test
    void theBarFollowsTheDjsLanguage_andTheLayoutDefaultsToThePoster() throws IOException {
        String polish = render("whatever", Locale.forLanguageTag("pl"));
        assertThat(polish).contains("<html lang=\"pl\"", "Wydruk kodu QR", "Plakat A4", "Drukuj", "class=\"layout-poster theme-classic has-editor\"");

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
