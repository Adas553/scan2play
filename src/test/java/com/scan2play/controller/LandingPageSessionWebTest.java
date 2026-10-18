package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production, 2026-10-18 (#118): the staff's invitation form moved to the end of the landing page, and a first visit — no session —
 * got the page cut off after 8 KB. The form's CSRF token is kept in the session, which Spring Session can make only before the
 * response is committed; the server had sent the first 8 KB by the time the form was rendered ("Cannot create a session after the
 * response has been committed"). Here with Spring Session's filter (sessions in memory, as the JDBC ones in production) and the
 * mock response's buffer: the whole page, its form and its footer.
 */
@WebMvcTest(HomeController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, LandingPageSessionWebTest.Sessions.class})
class LandingPageSessionWebTest {

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

    @Test
    void aFirstVisit_getsTheWholeLandingPage_withTheInvitationFormAndTheFooter() throws Exception {
        MvcResult result = mockMvc.perform(get("/").header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResolvedException()).isNull();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getContentAsByteArray().length).as("longer than the 8 KB it was cut at").isGreaterThan(8192);
        assertThat(html).contains("id=\"staffLoginForm\"", "name=\"_csrf\"", "<footer", "</html>");
    }
}
