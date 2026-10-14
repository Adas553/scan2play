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
        String view = new LegalController(com.scan2play.service.Plans.enforced()).pro(visitor, model);
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
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

        assertThat(html).contains("lang=\"pl\"", "<title>Scan2Play Pro</title>", ">Scan2Play Pro</h1>",
                "href=\"mailto:scan2playcontact@gmail.com?subject=Scan2Play%20Pro\"", ">Napisz, że chcesz Pro<",
                "data-copy-text=\"scan2playcontact@gmail.com\"", "data-copied=\"Skopiowano\"", ">Kopiuj adres<",
                "data-plan=\"free\"", "data-plan=\"pro\"", ">Free<", ">Pro<", "Wszystko z Free, a do tego:",
                ">Lista gospodarzy<", ">Twoje profile i napiwki<", ">Podsumowanie wieczoru<", ">Komentarze AI z charakterem<",
                ">Własne limity gości<", ">Obsługa do 10 osób<",
                "data-pro-faq", ">Co, gdy Pro się skończy?<", ">Czy obsługa potrzebuje Pro?<", ">A goście?<",
                "Płatności w aplikacji jeszcze nie ma", "src=\"/js/dj-nav.js\"")
                .doesNotContain("??");
        assertThat(count(html, "btn-action")).as("one action on the page").isEqualTo(1);
        assertThat(html).as("the address also as text, to copy by hand").contains("data-pro-address");
        assertThat(html.indexOf("data-plan=\"free\"")).as("Free first, then Pro").isLessThan(html.indexOf("data-plan=\"pro\""));
        assertThat(html).as("no price: there is no payment yet").doesNotContain("zł", "PLN", "/ mies");
        DashboardPageRenderTest.assertNothingInline(html);
        write("pro-pl.html", html);
    }

    @Test
    @DisplayName("in English for any other language: the same parts")
    void shouldRenderThePageInEnglish() throws IOException {
        String html = render(Locale.ENGLISH, dj());

        assertThat(html).contains("lang=\"en\"", ">Scan2Play Pro</h1>", "href=\"mailto:scan2playcontact@gmail.com?subject=Scan2Play%20Pro\"",
                ">Tell us you want Pro<", "data-copied=\"Copied\"", ">Copy address<", "data-plan=\"free\"", "data-plan=\"pro\"",
                "Everything in Free, plus:", ">The hosts’ list<", ">Staff of up to 10<", "data-pro-faq", ">What if Pro ends?<")
                .doesNotContain("??");
        assertThat(count(html, "btn-action")).isEqualTo(1);
        assertThat(render(Locale.GERMAN, dj())).contains("lang=\"en\"");
        DashboardPageRenderTest.assertNothingInline(html);
        write("pro-en.html", html);
    }

    @Test
    @DisplayName("the way back: the panel for whoever is logged in, the landing page for anyone else")
    void shouldLeadBackWhereTheVisitorCameFrom() {
        assertThat(render(PL, ANONYMOUS)).contains("href=\"/\" id=\"proBack\"", ">← Strona główna<").doesNotContain("href=\"/dj/dashboard\"");
        assertThat(render(PL, dj())).contains("href=\"/dj/dashboard\" id=\"proBack\"", ">← Panel<");
    }

    @Test
    @DisplayName("while the plans are not enforced the page is a 404: nothing says \"Pro\"")
    void withThePlansOff_isNotFound() {
        PlanService off = com.scan2play.service.Plans.off();
        assertThatThrownBy(() -> new LegalController(off).pro(dj(), new ConcurrentModel()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }
}
