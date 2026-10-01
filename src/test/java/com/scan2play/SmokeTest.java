package com.scan2play;

import com.scan2play.config.SecurityConfig;
import com.scan2play.controller.HomeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Smoke test — verifies that the application boots, routes are wired correctly,
 * and the security layer works as expected.
 * Uses @WebMvcTest (no DB, no external APIs — fast and reliable).
 */
@WebMvcTest(HomeController.class)
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
