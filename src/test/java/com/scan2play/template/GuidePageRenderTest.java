package com.scan2play.template;

import com.scan2play.controller.LegalController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guide "Jak działa Scan2Play" (the owner, 2026-10-12): public, one file per language, the way back the visitor can use, the
 * sections in the order of a party, the contact address. Leaves {@code guide-pl.html} / {@code guide-en.html} (not logged in) in
 * {@code target/browser-harness/} for the browser tests.
 */
class GuidePageRenderTest {

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

    /** What the controller does for the locale and the visitor, rendered like the view resolver would. */
    private static String render(Locale locale, org.springframework.security.core.Authentication visitor) {
        LocaleContextHolder.setLocale(locale);
        ConcurrentModel model = new ConcurrentModel();
        String view = new LegalController().guide(visitor, model);
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        return engine.process(view, context);
    }

    private static final org.springframework.security.core.Authentication ANONYMOUS =
            new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private static org.springframework.security.core.Authentication dj() {
        TestingAuthenticationToken dj = new TestingAuthenticationToken("dj", null, "ROLE_USER");
        dj.setAuthenticated(true);
        return dj;
    }

    @Test
    @DisplayName("the page in Polish: the sections in the order of a party, the rejected ones in the history (the AI can be checked), the contact address")
    void shouldRenderTheGuideInPolish() throws IOException {
        String html = render(PL, ANONYMOUS);

        assertThat(html).contains("lang=\"pl\"", "<title>Jak działa Scan2Play</title>", ">Jak działa Scan2Play</h1>",
                "Odrzucone przez AI", "w historii, razem z komentarzem AI", "osoby, dla których grasz", "scan2playcontact@gmail.com", "mogą Ci pomagać inne osoby",
                ">Podgląd<", ">Obsługa kolejki<", ">Współorganizator<", ">Własne<",
                ">▶ Zagrane<", ">⏭ Pomiń<", ">⚠ Sprawdź<", ">⭐ Życzenie gospodarzy<", ">↩ Przywróć<", "TAK! albo NIE", "„PRZEKAZANE”")
                .doesNotContain("para młoda", "Barman", "??");
        List<String> sections = List.of("id=\"start\"", "id=\"party\"", "id=\"phone\"", "id=\"after\"", "id=\"staff\"", "id=\"guests\"");
        for (int i = 1; i < sections.size(); i++) {
            assertThat(html.indexOf(sections.get(i - 1))).as(sections.get(i)).isPositive().isLessThan(html.indexOf(sections.get(i)));
        }
        assertThat(html).as("every jump has its section").contains("href=\"#start\"", "href=\"#party\"", "href=\"#phone\"",
                "href=\"#after\"", "href=\"#staff\"", "href=\"#guests\"");
        assertThat(html).as("the pictures of buttons are not controls: no <button> and no form")
                .doesNotContain("<button", "<form");
        DashboardPageRenderTest.assertNothingInline(html);
        write("guide-pl.html", html);
    }

    @Test
    @DisplayName("the way back: the panel for whoever is logged in, the landing page for anyone else")
    void shouldLeadBackWhereTheVisitorCameFrom() {
        assertThat(render(PL, ANONYMOUS)).contains("href=\"/\" id=\"guideBack\"", ">← Strona główna<").doesNotContain("/dj/dashboard");
        assertThat(render(PL, null)).contains(">← Strona główna<");
        assertThat(render(PL, dj())).contains("href=\"/dj/dashboard\" id=\"guideBack\"", ">← Panel<").doesNotContain("Strona główna");
    }

    @Test
    @DisplayName("the page in English for any other language: the same sections and the same address")
    void shouldRenderTheGuideInEnglish() throws IOException {
        String html = render(Locale.ENGLISH, ANONYMOUS);

        assertThat(html).contains("lang=\"en\"", ">How Scan2Play works</h1>", "the AI turned down are in the history",
                "the people you play for", "scan2playcontact@gmail.com", ">▶ Played<", ">↩ Restore<", ">← Home<",
                "id=\"start\"", "id=\"guests\"").doesNotContain("??");
        assertThat(render(Locale.GERMAN, ANONYMOUS)).contains("lang=\"en\"", ">How Scan2Play works</h1>");
        write("guide-en.html", html);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }
}
