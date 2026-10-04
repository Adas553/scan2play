package com.scan2play;

import com.scan2play.config.SecurityConfig;
import com.scan2play.controller.CspReportController;
import com.scan2play.controller.HomeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Smoke test — verifies that the application boots, routes are wired correctly,
 * and the security layer works as expected.
 * Uses @WebMvcTest (no DB, no external APIs — fast and reliable).
 */
@WebMvcTest({HomeController.class, CspReportController.class})
@Import(SecurityConfig.class)
class SmokeTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- Public routes ----

    @Test
    @DisplayName("GET / → 200 landing page for unauthenticated user")
    void landingPage_shouldReturn200() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"));
    }

    @Test
    @DisplayName("Landing page renders HTML with Scan2Play branding")
    void landingPage_shouldContainBranding() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Scan2Play")));
    }

    @Test
    @DisplayName("The landing page offers two kinds of party, both through /start, which keeps the choice; no Spotify any more")
    void landingPage_offersTheRequestsOnlyTile() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("href=\"/start/youtube\"")))
                .andExpect(content().string(containsString("href=\"/start/requests\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("spotify"))));
    }

    @Test
    @DisplayName("The tile for DJs (requests only) comes first, on a row of its own, recommended; YouTube follows")
    void landingPage_putsTheTileForDjsFirst() throws Exception {
        String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        int requests = html.indexOf("href=\"/start/requests\"");
        int lineBreak = html.indexOf("provider-cards-break");
        assertThat(requests).isPositive().isLessThan(lineBreak);
        assertThat(lineBreak).isLessThan(html.indexOf("href=\"/start/youtube\""));
        // the recommended one is the tile for DJs, and only it
        assertThat(html).containsOnlyOnce("provider-card--recommended");
        assertThat(html.indexOf("provider-card--recommended")).isLessThan(lineBreak);
    }

    @Test
    @DisplayName("GET /start/requests (public) keeps the choice in the session and goes on to Google's login; another kind goes home")
    void start_keepsTheChosenKind_andGoesToGooglesLogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/start/requests"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"))
                .andReturn();
        assertThat(result.getRequest().getSession().getAttribute("djChosenProvider")).isEqualTo("REQUESTS_ONLY");

        mockMvc.perform(get("/start/youtube")).andExpect(redirectedUrl("/oauth2/authorization/google"));
        mockMvc.perform(get("/start/vinyl")).andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("Every page carries the Content-Security-Policy — reported only, until it is switched on (review 5.1)")
    void pages_shouldCarryTheContentSecurityPolicy_reportOnly() throws Exception {
        mockMvc.perform(get("/"))
                // no 'unsafe-inline' for scripts: the pages have no inline script and no inline handler
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString(
                        "script-src 'self' https://www.youtube.com https://s.ytimg.com;")))
                // and none for styles: the templates have no style="…" (NoInlineCodeInTemplatesTest)
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString(
                        "style-src 'self';")))
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("'unsafe-inline'"))))
                // no other host for scripts and styles: Bootstrap comes from the app (/webjars)
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("cdn.jsdelivr.net"))))
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString("object-src 'none'")))
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString("report-uri /csp-report")))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    @DisplayName("A browser's CSP report is taken without a login or a CSRF token")
    void cspReport_isTakenWithoutLoginOrCsrf() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/csp-report")
                        .contentType("application/csp-report")
                        .content("{\"csp-report\":{\"document-uri\":\"https://scan2play.com.pl/p/ABC12?x=1\","
                                + "\"effective-directive\":\"script-src-elem\",\"blocked-uri\":\"inline\"}}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Static CSS asset is accessible")
    void staticCss_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Static JS asset is accessible")
    void staticJs_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/js/dashboard/main.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/js/dashboard/events.js"))   // a module imported by the others
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bootstrap is served by the app without a login, its version left out of the URL (webjars-locator-lite)")
    void bootstrap_isServedByTheApp() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bootstrap")));
        mockMvc.perform(get("/webjars/bootstrap/js/bootstrap.bundle.min.js"))
                .andExpect(status().isOk());
    }

    // ---- Protected routes (security check) ----

    @Test
    @DisplayName("GET /dj/dashboard → 302 redirect for unauthenticated user")
    void dashboard_shouldRedirectUnauthenticated() throws Exception {
        mockMvc.perform(get("/dj/dashboard"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("GET /dj/history-view → 302 redirect for unauthenticated user")
    void historyView_shouldRedirectUnauthenticated() throws Exception {
        mockMvc.perform(get("/dj/history-view"))
                .andExpect(status().is3xxRedirection());
    }

    // ---- YouTube player is NOT scrapeable from server-rendered HTML ----

    @Test
    @DisplayName("Landing page HTML does not contain any <iframe> (YouTube player is client-side only)")
    void landingPage_shouldNotContainIframe() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<iframe"))));
    }
}
