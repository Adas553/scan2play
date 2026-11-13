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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
        String view = new LegalController(com.scan2play.service.Plans.off()).guide(visitor, model);
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
                "Odrzucone przez AI", "w historii, razem z komentarzem AI", "osoby, dla których grasz", "contact@scan2play.com.pl", "mogą Ci pomagać inne osoby",
                ">Podgląd<", ">Obsługa kolejki<", ">Współorganizator<", ">Własne<",
                ">▶ Zagrane<", ">⏭ Pomiń<", ">⚠ Sprawdź<", ">⭐ Życzenie gospodarzy<", ">↩ Przywróć<", "TAK! albo NIE", "„PRZEKAZANE”")
                .doesNotContain("para młoda", "Barman", "??");
        List<String> sections = List.of("id=\"start\"", "id=\"party\"", "id=\"phone\"", "id=\"after\"", "id=\"staff\"", "id=\"guests\"");
        for (int i = 1; i < sections.size(); i++) {
            assertThat(html.indexOf(sections.get(i - 1))).as(sections.get(i)).isPositive().isLessThan(html.indexOf(sections.get(i)));
        }
        assertThat(html).as("every jump has its section").contains("href=\"#start\"", "href=\"#party\"", "href=\"#phone\"",
                "href=\"#after\"", "href=\"#staff\"", "href=\"#guests\"");
        // the guide's own text; after it only the dialog of a tapped picture, whose "✕" is a real button
        assertThat(html.substring(0, html.indexOf("<dialog"))).as("the pictures of buttons are not controls: no <button> and no form")
                .doesNotContain("<button", "<form");
        assertThat(html.substring(html.indexOf("<dialog"))).doesNotContain("<form").containsOnlyOnce("<button");
        assertThat(html).as("the party's hours (V55), in the party's section").contains("id=\"guideHours\"", "Godziny imprezy", "Prośby od 20:00");
        assertThat(html.indexOf("id=\"guideHours\"")).isGreaterThan(html.indexOf("id=\"party\"")).isLessThan(html.indexOf("id=\"phone\""));
        assertThat(html).as("the venue's kiosk (V56)").contains("id=\"guideKiosk\"", "Kiosk dla gości", "Następny gość");
        String kiosk = html.substring(html.indexOf("id=\"guideKiosk\""), html.indexOf("</section>", html.indexOf("id=\"guideKiosk\"")));
        assertThat(kiosk).as("the kiosk: set up on a tablet logged out, kept in the kiosk, its limits, a new link, its marks").contains(
                "nie jesteś zalogowany", "Dostęp nadzorowany", "przypinanie ekranu", "3 prośby na minutę", "20 👍 na 10 minut",
                "Nowy link", "🖥 kiosk", "Z kiosku");
        assertThat(html.substring(html.indexOf("Współorganizator"), html.indexOf("Własne"))).as("a co-organiser has the kiosk (V56)")
                .contains("kiosk dla gości");
        assertThePictures(html, "pl");
        DashboardPageRenderTest.assertNothingInline(html);
        write("guide-pl.html", html);
    }

    @Test
    @DisplayName("the way back: the panel for whoever is logged in, the landing page for anyone else")
    void shouldLeadBackWhereTheVisitorCameFrom() {
        assertThat(render(PL, ANONYMOUS)).contains("href=\"/\" id=\"guideBack\"", ">← Strona główna<").doesNotContain("/dj/dashboard");
        // our logo is the way home too, and says so (the owner, 2026-10-24): a link with its title
        assertThat(render(PL, ANONYMOUS)).contains("<a href=\"/\" class=\"s2p-home-logo\"", "title=\"Scan2Play — strona główna\"",
                "aria-label=\"Scan2Play — strona główna\"");
        assertThat(render(PL, null)).contains(">← Strona główna<");
        assertThat(render(PL, dj())).contains("href=\"/dj/dashboard\" id=\"guideBack\"", ">← Panel<").doesNotContain("Strona główna");
    }

    @Test
    @DisplayName("the page in English for any other language: the same sections and the same address")
    void shouldRenderTheGuideInEnglish() throws IOException {
        String html = render(Locale.ENGLISH, ANONYMOUS);

        assertThat(html).contains("lang=\"en\"", ">How Scan2Play works</h1>", "the AI turned down are in the history",
                "the people you play for", "contact@scan2play.com.pl", ">▶ Played<", ">↩ Restore<", ">← Home<",
                "id=\"start\"", "id=\"guests\"").doesNotContain("??");
        String kiosk = html.substring(html.indexOf("id=\"guideKiosk\""), html.indexOf("</section>", html.indexOf("id=\"guideKiosk\"")));
        assertThat(kiosk).as("the kiosk, as in Polish").contains("not logged in", "Guided Access", "screen pinning",
                "3 requests a minute", "20 👍 per 10 minutes", "New link", "🖥 kiosk", "From the kiosk");
        assertThat(html.substring(html.indexOf("Co-organiser"), html.indexOf("Custom"))).contains("guest kiosk");
        assertThat(render(Locale.GERMAN, ANONYMOUS)).contains("lang=\"en\"", ">How Scan2Play works</h1>");
        assertThePictures(html, "en");
        write("guide-en.html", html);
    }

    /**
     * The three pictures in the page's language, each in its section — the panel on a computer (the landing page's picture) during the
     * party, the panel on a phone, the evening summary after the party, by its row —, its file there (a missing one would be a broken
     * picture on a public page), its size given and the file's own (no jump when it loads), loaded only when scrolled to, described, and a link to itself (a tap
     * shows it whole in the middle of the screen — guide-pictures.js —; without the script the link opens the file).
     */
    private static void assertThePictures(String html, String lang) {
        List<String> files = List.of("landing/dashboard-" + lang, "guide/phone-" + lang, "guide/summary-" + lang);
        List<String> sections = List.of("party", "phone", "after");
        for (int n = 1; n <= 3; n++) {
            int at = html.indexOf("id=\"guidePicture" + n + "\"");
            assertThat(at).as("picture %d", n).isPositive();
            String figure = html.substring(at, html.indexOf("</figure>", at));
            String file = files.get(n - 1);
            Path picture = Path.of("src/main/resources/static/images", file + ".webp");
            assertThat(picture).as("picture %d's file", n).isRegularFile();
            int[] size = webpSize(picture);
            assertThat(figure).as("picture %d", n).contains("<a href=\"/images/" + file + ".webp\">", "src=\"/images/" + file + ".webp\"",
                    "width=\"" + size[0] + "\" height=\"" + size[1] + "\"", "loading=\"lazy\"", "<figcaption>")
                    .containsPattern("alt=\"[^\"]{20,}\"");
            int start = html.indexOf("<section id=\"" + sections.get(n - 1) + "\"");
            assertThat(start).as("section #%s", sections.get(n - 1)).isPositive();
            assertThat(at).as("picture %d in #%s", n, sections.get(n - 1)).isGreaterThan(start)
                    .isLessThan(html.indexOf("</section>", start));
        }
        assertThat(html).as("a tap shows a picture whole, on the same page (guide-pictures.js)").contains(
                "<dialog class=\"s2p-picture-dialog\" id=\"guidePictureDialog\" aria-label=\"", "class=\"btn-close btn-close-white\"",
                "<script src=\"/js/guide-pictures.js\"></script>");
        assertThat(html).as("the phone's picture is upright").contains("s2p-guide-picture s2p-guide-picture-tall\" id=\"guidePicture2\"");
        assertThat(html.indexOf("id=\"guidePicture3\"")).as("the summary's picture under its row")
                .isGreaterThan(html.indexOf(lang.equals("pl") ? ">Podsumowanie wieczoru</span>" : ">Evening summary</span>"));
    }

    /**
     * A WebP's width and height, from its header (the extended format Chrome writes: "VP8X", each less one in 24 bits): the page's
     * width / height must be the file's, or the place kept for it has another shape than the picture after screenshots.py made it again.
     */
    private static int[] webpSize(Path file) {
        try {
            byte[] head = Arrays.copyOf(Files.readAllBytes(file), 30);
            assertThat(new String(head, 0, 4, StandardCharsets.US_ASCII) + new String(head, 8, 8, StandardCharsets.US_ASCII))
                    .as("%s: a WebP of screenshots.py", file).isEqualTo("RIFFWEBPVP8X");
            return new int[]{1 + threeBytes(head, 24), 1 + threeBytes(head, 27)};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int threeBytes(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8 | (bytes[at + 2] & 0xff) << 16;
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), html.getBytes(StandardCharsets.UTF_8));
    }
}
