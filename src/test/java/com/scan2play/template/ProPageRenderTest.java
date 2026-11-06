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

/**
 * The page "Scan2Play Pro" ({@code /pro}, V35; a page of the product since the review of 2026-10-14,
 * https://claude.ai/artifact/PPSwW3nDrRdWFcbM8hWAoN): one action — write that you want Pro —, the plans Free and Pro, what Pro gives,
 * the questions one asks before buying; while the plans are not enforced one card, everything free, and no word of Pro. Leaves
 * {@code pro-pl.html} / {@code pro-en.html} (a DJ logged in) and {@code pro-free-pl.html} / {@code pro-free-en.html} (a visitor, the
 * plans off) in {@code target/browser-harness/} for the browser tests ({@code scenarios/plans.js}).
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
        return render(locale, visitor, com.scan2play.service.Plans.enforced());
    }

    private static String render(Locale locale, Authentication visitor, PlanService plans) {
        LocaleContextHolder.setLocale(locale);
        ConcurrentModel model = new ConcurrentModel();
        LegalController controller = new LegalController(plans);
        model.addAttribute("plansEnforced", controller.plansEnforced());   // the controller's @ModelAttributes, as MVC calls them
        model.addAttribute("pricingLink", controller.pricingLink());
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
                "data-plan=\"free\"", "data-plan=\"pro\"", ">Free<", "data-pro-compare", ">Ocena AI prośby<", ">150 na wieczór<", ">każda prośba<", ">300 na dobę<",
                ">Lista gospodarzy<", ">Twoje profile i napiwki<", "w 100% dla Ciebie, bez prowizji", ">Podsumowanie wieczoru<", ">Komentarze AI z charakterem<",
                ">Własne limity gości i ocena AI każdej prośby<", ">Obsługa do 10 osób<", ">Twoja biblioteka<", ">Kiosk dla gości<", "rano mail z podsumowaniem",
                "„✓ DJ ma” w podpowiedziach gości",
                "data-pro-faq", ">Ile będzie kosztować Pro?<", ">Co, gdy Pro się skończy?<", ">Czy obsługa potrzebuje Pro?<", ">A goście?<",
                "Płatności jeszcze nie ma", "src=\"/js/dj-nav.js\"", "lista gości i ekran TV ich nie pokazują")
                .doesNotContain("??");
        assertThat(html).as("Pro has no AI limit an evening; the 300 is a party's requests a day, for both plans (GuestRequestLimiter)")
                .doesNotContain("300 na wieczór", "300 ocen");
        assertThat(html).as("a screen reader hears \"tak\" and \"nie\", not \"check mark\" and \"dash\"")
                .contains("<span aria-hidden=\"true\">✓</span><span class=\"visually-hidden\">tak</span>",
                        "<span aria-hidden=\"true\">—</span><span class=\"visually-hidden\">nie</span>")
                .doesNotContain("<td>✓</td>", ">—</td>");
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

        assertThat(html).contains("lang=\"en\"", ">Pricing</h1>", ">EARLY ACCESS<", ">Write to us<", ">How much will Pro cost?<", ">150 an evening<", ">every request<", ">300 a day<", "href=\"mailto:contact@scan2play.com.pl?subject=Scan2Play%20Pro\"",
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
    @DisplayName("while the plans are not enforced: one card, everything free, no word of Pro — the terms' section 5 for what may change")
    void withThePlansOff_everythingIsFree() throws IOException {
        PlanService off = com.scan2play.service.Plans.off();
        String pl = render(PL, ANONYMOUS, off);

        assertThat(pl).contains("<title>Cennik Scan2Play</title>", ">Cennik</h1>", "data-plan=\"all\"", ">Za darmo</h2>",
                        "<p class=\"s2p-plan-price\" data-plan-price>0 zł</p>", "class=\"s2p-plans s2p-plans-one\"",
                        "Dla każdego <span class=\"text-nowrap\">DJ-a</span>, na każdą imprezę.", ">Lista gospodarzy:", "obsługa do 10 osób", "kiosk dla gości bez telefonu",
                        ">Czy tak zostanie?<", "dostaną je na 30 dni bez opłat", "href=\"/terms#plans\"", ">Czy potrzebuję karty?<", ">A goście?<",
                        "href=\"/start\" class=\"btn btn-action w-100\" id=\"freeStart\">Zacznij za darmo<", "id=\"footerPricing\"",
                        "content=\"Scan2Play jest za darmo: kod QR, prośby gości sprawdzane przez AI",
                        "<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/pro\">")
                .doesNotContain("??", "data-plan=\"free\"", "data-plan=\"pro\"", "data-feature", "data-pro-compare", "mailto:", "WCZESNY",
                        "150", "s2p-pro\"")
                .doesNotContainPattern("(?U)\\bPro\\b");
        assertThat(count(pl, "btn-action")).as("one action on the page").isEqualTo(1);
        assertThat(render(PL, dj(), off)).as("a DJ logged in has an account already").doesNotContain("id=\"freeStart\"", "btn-action");
        DashboardPageRenderTest.assertNothingInline(pl);
        write("pro-free-pl.html", pl);

        String en = render(Locale.ENGLISH, ANONYMOUS, off);
        assertThat(en).contains(">Pricing</h1>", "data-plan=\"all\"", ">Free</h2>", "For every DJ and every party.", ">Will it stay so?<",
                        ">Do I need a card?<", "href=\"/terms#plans\"", ">Start for free<")
                .doesNotContain("??", "data-plan=\"pro\"", "mailto:")
                .doesNotContainPattern("(?U)\\bPro\\b");
        write("pro-free-en.html", en);
    }

    @Test
    @DisplayName("/cennik and /pricing lead to /pro in their language (one page, one canonical address), the plans enforced or not")
    void theOtherAddresses_leadToThePage() {
        for (PlanService plans : new PlanService[]{com.scan2play.service.Plans.enforced(), com.scan2play.service.Plans.off()}) {
            LegalController controller = new LegalController(plans);
            assertThat(controller.pricing(new MockHttpServletRequest("GET", "/cennik")).getUrl()).isEqualTo("/pro?lang=pl");
            assertThat(controller.pricing(new MockHttpServletRequest("GET", "/pricing")).getUrl()).isEqualTo("/pro?lang=en");
            assertThat(controller.pricingLink()).as("the footer's link").isTrue();
        }
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }
}
