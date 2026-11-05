package com.scan2play.template;

import com.scan2play.controller.LegalController;
import com.scan2play.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The page "Scan2Play Pro" ({@code /pro}, V35; a page of the product since the review of 2026-10-14,
 * https://claude.ai/artifact/PPSwW3nDrRdWFcbM8hWAoN): one action — write that you want Pro —, the plans Free and Pro, what Pro gives,
 * the questions one asks before buying; a 404 while the plans are not enforced. Leaves {@code pro-pl.html} / {@code pro-en.html} (a DJ
 * logged in) in {@code target/browser-harness/} for the browser tests ({@code scenarios/plans.js}).
 */
class ProPageRenderTest {

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

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** What the controller does for the locale and the visitor (the plans enforced), rendered like the view resolver would. */
    private static String render(Locale locale, Authentication visitor) {
        LocaleContextHolder.setLocale(locale);
        ConcurrentModel model = new ConcurrentModel();
        LegalController controller = new LegalController(com.scan2play.service.Plans.enforced());
        model.addAttribute("plansEnforced", controller.plansEnforced());   // the controller's @ModelAttribute, as MVC calls it
        String view = controller.pro(visitor, model);
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        context.setVariable("siteUrl", "https://www.scan2play.com.pl");   // SiteUrlAdvice: the head of a public page needs it whole
        return engine.process(view, context);
    }

    private static final Authentication ANONYMOUS =
            new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private static Authentication dj() {
        TestingAuthenticationToken dj = new TestingAuthenticationToken("dj", null, "ROLE_USER");
        dj.setAuthenticated(true);
        return dj;
    }

    private static int count(String html, String part) {
        return html.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    @DisplayName("in Polish: one action (write to us), the plans Free and Pro, each Pro feature named with what it gives, the questions, the address as text")
    void shouldRenderThePageInPolish() throws IOException {
        String html = render(PL, dj());

        assertThat(html).contains("lang=\"pl\"", "<title>Cennik Scan2Play</title>", ">Cennik</h1>", ">0 zł <small>zawsze</small><", ">WCZESNY DOSTĘP<", ">Napisz do nas<",
                "href=\"mailto:contact@scan2play.com.pl?subject=Scan2Play%20Pro\"", ">Napisz, że chcesz Pro<",
                "data-copy-text=\"contact@scan2play.com.pl\"", "data-copied=\"Skopiowano\"", ">Kopiuj adres<",
                "data-plan=\"free\"", "data-plan=\"pro\"", ">Free<", "data-pro-compare", ">Ocena AI każdej prośby<", ">do 150 na wieczór<", ">do 300 na wieczór<",
                ">Lista gospodarzy<", ">Twoje profile i napiwki<", "w 100% dla Ciebie, bez prowizji", ">Podsumowanie wieczoru<", ">Komentarze AI z charakterem<",
                ">Własne limity gości i więcej ocen AI<", ">Obsługa do 10 osób<", ">Twoja biblioteka<", ">Kiosk dla gości<", "rano mail z podsumowaniem",
                "„✓ DJ ma” w podpowiedziach gości",
                "data-pro-faq", ">Ile będzie kosztować Pro?<", ">Co, gdy Pro się skończy?<", ">Czy obsługa potrzebuje Pro?<", ">A goście?<",
                "Płatności jeszcze nie ma", "src=\"/js/dj-nav.js\"")
                .doesNotContain("??");
        assertThat(count(html, "btn-action")).as("one action on the page").isEqualTo(1);
        assertThat(html).as("the head for search engines and shared links").contains(
                "<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/pro\">",
                "hreflang=\"en\" href=\"https://www.scan2play.com.pl/pro?lang=en\"", "property=\"og:title\"");
        assertThat(html).as("the address also as text, to copy by hand").contains("data-pro-address");
        assertThat(html.indexOf("data-plan=\"free\"")).as("Free first, then Pro").isLessThan(html.indexOf("data-plan=\"pro\""));
        assertThat(html).as("Free costs 0 zł; Pro has no price: there is no payment yet").doesNotContain("PLN", "/ mies", "/mies", "$", "€");
        assertThat(count(html.substring(html.indexOf("<body")), " zł")).as("on the page only Free's price").isEqualTo(1);
        assertThat(html).as("a DJ logged in has an account already").doesNotContain("id=\"freeStart\"");
        assertThat(html).as("the footer's link to this page").contains("id=\"footerPricing\"", ">Cennik</a>");
        DashboardPageRenderTest.assertNothingInline(html);
        write("pro-pl.html", html);
    }

    @Test
    @DisplayName("in English for any other language: the same parts")
    void shouldRenderThePageInEnglish() throws IOException {
        String html = render(Locale.ENGLISH, dj());

        assertThat(html).contains("lang=\"en\"", ">Pricing</h1>", ">EARLY ACCESS<", ">Write to us<", ">How much will Pro cost?<", ">up to 150 an evening<", "href=\"mailto:contact@scan2play.com.pl?subject=Scan2Play%20Pro\"",
                ">Tell us you want Pro<", "data-copied=\"Copied\"", ">Copy address<", "data-plan=\"free\"", "data-plan=\"pro\"",
                "data-pro-compare", ">The hosts’ list<", ">Staff of up to 10<", ">Your library<", ">The guests’ kiosk<", "data-pro-faq", ">What if Pro ends?<")
                .doesNotContain("??");
        assertThat(count(html, "btn-action")).isEqualTo(1);
        assertThat(render(Locale.GERMAN, dj())).contains("lang=\"en\"");
        DashboardPageRenderTest.assertNothingInline(html);
        write("pro-en.html", html);
    }

    @Test
    @DisplayName("every feature of the Pro plan is named on the page, in both languages: a new PlanFeature without its line is red here")
    void everyPlanFeature_isOnThePage() {
        for (Locale locale : new Locale[]{PL, Locale.ENGLISH}) {
            String html = render(locale, dj());
            java.util.Set<String> named = new java.util.HashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-feature=\"([A-Z_ ]+)\"").matcher(html);
            while (m.find()) {
                named.addAll(java.util.List.of(m.group(1).split(" ")));
            }
            for (com.scan2play.model.PlanFeature feature : com.scan2play.model.PlanFeature.values()) {
                assertThat(named).as("%s on /pro (%s)", feature, locale).contains(feature.name());
            }
        }
    }

    @Test
    @DisplayName("the way back: the panel for whoever is logged in, the landing page for anyone else")
    void shouldLeadBackWhereTheVisitorCameFrom() {
        assertThat(render(PL, ANONYMOUS)).contains("href=\"/\" id=\"proBack\"", ">← Strona główna<").doesNotContain("href=\"/dj/dashboard\"");
        assertThat(render(PL, ANONYMOUS)).as("a visitor can start for free").contains("href=\"/start\"", "id=\"freeStart\"", ">Zacznij za darmo<");
        // our logo is the way home too, and says so (the owner, 2026-10-24): a link with its title
        assertThat(render(PL, ANONYMOUS)).contains("<a href=\"/\" class=\"s2p-home-logo\"", "title=\"Scan2Play — strona główna\"",
                "aria-label=\"Scan2Play — strona główna\"");
        assertThat(render(PL, dj())).contains("href=\"/dj/dashboard\" id=\"proBack\"", ">← Panel<");
    }

    @Test
    @DisplayName("while the plans are not enforced the page is a 404: nothing says \"Pro\"")
    void withThePlansOff_isNotFound() {
        PlanService off = com.scan2play.service.Plans.off();
        assertThatThrownBy(() -> new LegalController(off).pro(dj(), new ConcurrentModel()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    @DisplayName("/cennik and /pricing lead to /pro in their language (one page, one canonical address); a 404 while the plans are off")
    void theOtherAddresses_leadToThePage() {
        LegalController on = new LegalController(com.scan2play.service.Plans.enforced());
        assertThat(on.pricing(new MockHttpServletRequest("GET", "/cennik"))).isEqualTo("redirect:/pro?lang=pl");
        assertThat(on.pricing(new MockHttpServletRequest("GET", "/pricing"))).isEqualTo("redirect:/pro?lang=en");
        LegalController off = new LegalController(com.scan2play.service.Plans.off());
        assertThat(off.plansEnforced()).as("no footer link").isFalse();
        assertThatThrownBy(() -> off.pricing(new MockHttpServletRequest("GET", "/cennik")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }
}
