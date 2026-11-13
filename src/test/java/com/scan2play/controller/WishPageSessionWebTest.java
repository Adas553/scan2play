package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.WishService;
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

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guests' page of "Życzenia przed imprezą" (V68) on a first visit — no session yet — through Spring Session's filter (sessions in
 * memory, as the JDBC ones in production) and the mock response's buffer: the whole page, its form with the CSRF token and its
 * footer. The trap of the landing page (2026-10-18): the token is kept in the session, which can be made only before the response is
 * committed, and the page was cut after 8 KB.
 */
@WebMvcTest(WishController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, GuestRequestLimiter.class, WishPageSessionWebTest.Sessions.class})
class WishPageSessionWebTest {

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
    private WishService wishService;
    @MockitoBean
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    @Test
    void aFirstVisit_getsTheWholePage_withTheFormsTokenAndTheFooter() throws Exception {
        WishService.Event event = WishWebTest.event(false);
        when(wishService.byToken(event.token())).thenReturn(Optional.of(event));
        when(partySettingsRepository.findByPartyCode("WSH01")).thenReturn(Optional.of(
                PartySettingsEntity.builder().partyCode("WSH01").ownerId("owner").active(false).djName("DJ Koko").build()));

        MvcResult result = mockMvc.perform(get("/w/" + event.token()).header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResolvedException()).isNull();
        String html = WishWebTest.html(result);
        WishWebTest.csrf(html);
        assertThat(html).contains("id=\"wishForm\"", "<footer", "</html>");
    }
}
