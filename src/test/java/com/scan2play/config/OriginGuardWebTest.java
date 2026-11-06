package com.scan2play.config;

import com.scan2play.controller.HomeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guard in the real chain, with the secret set: it runs before Spring Session and Spring Security — a request past Cloudflare
 * gets a 403 and leaves no session behind; the same request with Cloudflare's header is the app's as before.
 */
@WebMvcTest(controllers = HomeController.class, properties = "scan2play.origin-secret=" + OriginGuardWebTest.SECRET)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, OriginGuardWebTest.Sessions.class})
class OriginGuardWebTest {

    static final String SECRET = "from-cloudflare";

    @TestConfiguration
    static class Sessions {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 50)
        SessionRepositoryFilter<MapSession> springSessionRepositoryFilter() {
            return new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    private static boolean madeASession(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream().anyMatch(cookie -> cookie.startsWith("SESSION="));
    }

    @Test
    void pastCloudflare_isRefused_beforeASessionOrTheLogin() throws Exception {
        MvcResult page = mockMvc.perform(get("/")).andExpect(status().isForbidden()).andReturn();
        assertThat(page.getResponse().getContentAsString()).isEqualTo("Forbidden");
        // a DJ's page opened in the browser: the login would remember it in a new session — not past Cloudflare
        MvcResult djPage = mockMvc.perform(get("/dj/dashboard").header("Sec-Fetch-Mode", "navigate"))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(madeASession(djPage)).as("a session made").isFalse();
    }

    @Test
    void throughCloudflare_theAppAnswersAsBefore() throws Exception {
        mockMvc.perform(get("/").header(OriginGuardFilter.HEADER, SECRET)).andExpect(status().isOk());
        MvcResult djPage = mockMvc.perform(get("/dj/dashboard").header(OriginGuardFilter.HEADER, SECRET)
                        .header("Sec-Fetch-Mode", "navigate"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(djPage.getResponse().getRedirectedUrl()).endsWith("/oauth2/authorization/google");
        assertThat(madeASession(djPage)).as("the login remembers the page in a session, as before").isTrue();
    }
}
