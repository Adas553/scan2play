package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pages "Dla kogo" (Next 7, 2026-10-21): wedding DJs, clubs, pubs — public, for a search engine and a link sent to a DJ. Each is
 * Polish at its own address and English with {@code ?lang=en} (canonical, hreflang, like the other public pages), with its own
 * heading, four steps, four features and up to three questions, and links in its own language. No form (no session, no CSRF token).
 * Writes the pages of the browser tests ({@code target/browser-harness/for-whom-*.html}).
 */
@WebMvcTest(ForWhomController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class ForWhomPagesWebTest {

    private static final String SITE = "https://www.scan2play.com.pl";
    private static final Map<String, String> HEADINGS_PL = Map.of(
            "/dj-weselny", "Wesele bez kolejki przy konsoli",
            "/kluby", "Sala mówi, czego chce. Gatunek zostaje Twój.",
            "/puby", "Muzyka na życzenie gości, bez stania przy barze");

    private static final Map<String, String> KINDS_PL = Map.of("/dj-weselny", "DJ weselny", "/kluby", "Kluby i imprezy", "/puby", "Puby i bary");
    private static final Map<String, String> STEPS_PL = Map.of("/dj-weselny", "<h2>Jak to wygląda na weselu</h2>",
            "/kluby", "<h2>Jak to wygląda w klubie</h2>", "/puby", "<h2>Jak to wygląda w pubie</h2>");

    @Autowired
    private MockMvc mockMvc;

    private String page(String path, String lang) throws Exception {
        var request = get(path);
        if (lang != null) {
            request = request.param("lang", lang);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void eachPage_isPolish_atItsOwnAddress_withItsEnglishAlternate() throws Exception {
        for (Map.Entry<String, String> heading : HEADINGS_PL.entrySet()) {
            String path = heading.getKey();
            String html = page(path, null);

            assertThat(html).as(path).doesNotContain("??").contains("<html lang=\"pl\"",
                    "<link rel=\"canonical\" href=\"" + SITE + path + "\">",
                    "<link rel=\"alternate\" hreflang=\"pl\" href=\"" + SITE + path + "\">",
                    "<link rel=\"alternate\" hreflang=\"en\" href=\"" + SITE + path + "?lang=en\">",
                    "<link rel=\"alternate\" hreflang=\"x-default\" href=\"" + SITE + path + "\">",
                    "<meta name=\"description\" content=\"",
                    "<h1 class=\"s2p-landing-title\">" + heading.getValue() + "</h1>");
            assertThat(html.split("class=\"s2p-landing-step\"", -1)).as(path + ": four steps").hasSize(5);
            assertThat(section(html, "forWhomFeatures").split("class=\"s2p-landing-card\"", -1)).as(path + ": four features").hasSize(5);
            // three questions, the pubs' two (the owner, 2026-10-21: "Czy trzeba mieć DJ-a?" out)
            assertThat(section(html, "forWhomFaq").split("<details", -1)).as(path + ": its questions")
                    .hasSize(path.equals("/puby") ? 3 : 4);
            // where one is (the owner, 2026-10-21: from the landing page's card the page looked the same): the breadcrumbs, the kind
            // with its card's icon above the heading, the sections' headings of this kind of party
            assertThat(html.indexOf("id=\"forWhomBreadcrumb\"")).as(path + ": the breadcrumbs first")
                    .isPositive().isLessThan(html.indexOf("id=\"forWhomKind\"")).isLessThan(html.indexOf("<h1"));
            assertThat(html).contains("href=\"/#landingWho\">Dla kogo</a>", "aria-current=\"page\">" + KINDS_PL.get(path) + "</li>");
            assertThat(tagAndText(html, "forWhomKind")).contains(KINDS_PL.get(path));
            assertThat(section(html, "forWhomSteps")).contains(STEPS_PL.get(path));
            assertThat(html.split("href=\"/start\"", -1)).as(path + ": the start at the top and at the end").hasSize(3);
            // the other two kinds of party, the home page (the logo) and the guide — in Polish, at their own addresses
            List<String> others = HEADINGS_PL.keySet().stream().filter(p -> !p.equals(path)).toList();
            for (String other : others) {
                assertThat(section(html, "forWhomOthers")).contains("href=\"" + other + "\"");
            }
            assertThat(section(html, "forWhomOthers")).doesNotContain("href=\"" + path + "\"");
            // and the way back at the page's end (the owner, 2026-10-21)
            assertThat(section(html, "forWhomOthers")).contains("href=\"/\" id=\"forWhomBack\" class=\"link-light\">← Wróć na stronę główną</a>");
            assertThat(html).contains("id=\"forWhomHome\"", "href=\"/\"", "href=\"/jak-to-dziala\"", "id=\"langSwitchTop\"",
                    "src=\"/images/landing/dashboard-pl.webp\"");
            assertThat(html).as(path + ": no form — no session, no CSRF token").doesNotContain("<form", "_csrf");
            // no word of Pro while the plans are not enforced (the owner, 2026-10-21)
            assertThat(html).as(path + ": no Pro").doesNotContainPattern("(?U)\\bPro\\b").doesNotContain("/pro\"", "s2p-pro");
            writeForTheBrowserTests("for-whom-" + path.substring(1) + "-pl.html", html);
        }
    }

    @Test
    void eachPage_inEnglish_byItsAddress_withItsLinksInEnglish() throws Exception {
        for (String path : HEADINGS_PL.keySet()) {
            String html = page(path, "en");

            assertThat(html).as(path).doesNotContain("??").contains("<html lang=\"en\"",
                    "<link rel=\"canonical\" href=\"" + SITE + path + "?lang=en\">", "<meta property=\"og:locale\" content=\"en_US\">",
                    "href=\"/?lang=en\"", "href=\"/how-it-works?lang=en\"", "src=\"/images/landing/dashboard-en.webp\"");
            assertThat(section(html, "forWhomOthers")).contains("?lang=en\"").doesNotContainPattern("href=\"/[a-z-]+\"");
            assertThat(section(html, "forWhomOthers")).contains("href=\"/?lang=en\" id=\"forWhomBack\"", ">← Back to the home page</a>");
            assertThat(html).as(path + ": no Pro").doesNotContainPattern("(?U)\\bPro\\b").doesNotContain("/pro\"");
            writeForTheBrowserTests("for-whom-" + path.substring(1) + "-en.html", html);
        }
        assertThat(page("/dj-weselny", "en")).contains("<h1 class=\"s2p-landing-title\">A wedding without a queue at the booth</h1>");
    }

    /** Each page its own: no heading, step, feature or question of one is word for word on another (a doorway page, to a search engine). */
    @Test
    void thePages_doNotRepeatEachOther() throws Exception {
        for (String lang : List.of("pl", "en")) {
            Set<String> seen = new HashSet<>();
            for (String path : HEADINGS_PL.keySet()) {
                String html = page(path, lang);
                for (String id : List.of("forWhomSteps", "forWhomFaq")) {
                    for (String paragraph : section(html, id).split("<p>")) {
                        if (paragraph.contains("</p>")) {
                            String text = paragraph.substring(0, paragraph.indexOf("</p>"));
                            assertThat(seen.add(text)).as(lang + " " + path + ": said on another page too: " + text).isTrue();
                        }
                    }
                }
            }
        }
    }

    @Test
    void karaoke_hasNoPageYet() throws Exception {
        mockMvc.perform(get("/karaoke")).andExpect(status().is3xxRedirection());
    }

    /** The element with this id, up to its first closing tag. */
    private static String tagAndText(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as(id).isPositive();
        return html.substring(start, html.indexOf("</p>", start));
    }

    private static String section(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as(id).isPositive();
        return html.substring(start, html.indexOf("</section>", start));
    }

    private static void writeForTheBrowserTests(String name, String html) {
        try {
            Path out = Path.of("target", "browser-harness", name);
            Files.createDirectories(out.getParent());
            Files.writeString(out, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
