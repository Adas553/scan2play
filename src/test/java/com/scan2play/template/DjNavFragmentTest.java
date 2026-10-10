package com.scan2play.template;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real navigation fragment ({@code fragments/components.html :: dj-nav}) with the real message bundles: the
 * three tabs Panel / Queue / History and the bar that sticks to the top of the screen. The dashboard is behind a login, so
 * this is what catches a missing message key or a broken hook that {@code dashboard.js} (initTabs) looks for.
 */
class DjNavFragmentTest {

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

    private static String render(String activeTab, Locale locale) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        context.setVariable("activeTab", activeTab);
        context.setVariable("isActive", true);
        context.setVariable("partyCode", "ABC12");
        return engine.process("fragments/components", Set.of("dj-nav"), context);
    }

    /** The opening tag of the tab link with the given data-dj-tab, e.g. {@code <a class="nav-link active" data-dj-tab="panel" ...>}. */
    private static String tabLink(String html, String tab) {
        Matcher m = Pattern.compile("<a\\b[^>]*data-dj-tab=\"" + tab + "\"[^>]*>").matcher(html);
        assertThat(m.find()).as("a tab link for %s", tab).isTrue();
        return m.group();
    }

    @Test
    @DisplayName("three tabs, in the order Panel, Queue, History, each with the hook the script looks for and a link that works without it")
    void shouldRenderTheThreeTabs() {
        String html = render("panel", Locale.ENGLISH);

        assertThat(html.indexOf("data-dj-tab=\"panel\"")).isPositive()
                .isLessThan(html.indexOf("data-dj-tab=\"queue\""))
                .isLessThan(html.indexOf("data-dj-tab=\"history\""));
        assertThat(html.indexOf("data-dj-tab=\"queue\"")).isLessThan(html.indexOf("data-dj-tab=\"history\""));
        // the party the page shows (V32): a tab of another party's panel stays on it
        assertThat(tabLink(html, "panel")).contains("href=\"/dj/dashboard?party=ABC12#top\"");
        assertThat(tabLink(html, "queue")).contains("href=\"/dj/dashboard?party=ABC12#queue-content\"");
        assertThat(tabLink(html, "history")).contains("href=\"/dj/history-view?party=ABC12\"");
        assertThat(html).contains(">DJ Panel</a>", ">Queue</a>", ">History</a>");
        assertThat(html).doesNotContain("Queue (Dashboard)", "??", "<html", "<body");
    }

    @Test
    @DisplayName("the labels in Polish, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render("panel", PL);

        assertThat(html).contains(">Panel DJ-a</a>", ">Kolejka</a>", ">Historia</a>");
        assertThat(html).doesNotContain("Kolejka (Panel DJ-a)", "??");
    }

    @Test
    @DisplayName("only the tab of activeTab is lit and marked as the current one")
    void shouldLightOnlyTheActiveTab() {
        for (String tab : new String[] {"panel", "queue", "history"}) {
            String html = render(tab, Locale.ENGLISH);

            for (String other : new String[] {"panel", "queue", "history"}) {
                String link = tabLink(html, other);
                if (other.equals(tab)) {
                    assertThat(link).as("%s lit when activeTab=%s", other, tab).contains("active").contains("aria-current=\"page\"");
                } else {
                    assertThat(link).as("%s not lit when activeTab=%s", other, tab).doesNotContain("active").doesNotContain("aria-current");
                }
            }
        }
    }

    @Test
    @DisplayName("the bar is what sticks: a nav element with the id and class of the script and the style, a direct part of the page (no wrapper element)")
    void shouldRenderTheBarWithoutAWrapper() {
        String html = render("panel", Locale.ENGLISH);

        assertThat(html).contains("<nav id=\"djTabBar\" class=\"dj-tabbar ");
        // position: sticky only works inside a parent as tall as the page: the fragment must not add an element of its own
        assertThat(html.strip()).startsWith("<div id=\"party-closed-banner\"");
        assertThat(count(html, "<div")).as("opening and closing div tags").isEqualTo(count(html, "</div>"));
        // the buttons form a row above the bar, so that only the tabs have to stay in view
        assertThat(html.indexOf("id=\"end-party-form\"")).isLessThan(html.indexOf("id=\"djTabBar\""));
        assertThat(html.indexOf("id=\"djTabBar\"")).isLessThan(html.indexOf("id=\"feedbackModal\""));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }
}
