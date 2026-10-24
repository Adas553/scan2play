package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The review of 2026-10-21, T2 (the lesson of 2026-10-18, {@code LandingPageSessionWebTest}): the hosts open their page without an
 * account and without a session, and its form's CSRF token is kept in the session — which Spring Session can make only before the
 * response is committed, within the first 8 KB. Thymeleaf writes the token right after the {@code <form>} tag, and the form is at
 * the page's top, so it is made in time however long the two lists are below it. A form moved down the page (under a long text)
 * would need its token loaded in the controller, as the landing page's once did. Here with Spring Session's filter (sessions in
 * memory, as the JDBC ones in production): a first visit with the longest lists gets the whole page, its token and its session.
 */
@WebMvcTest(HostController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, HostPageSessionWebTest.Sessions.class})
class HostPageSessionWebTest {

    private static final String TOKEN = "AbC_123-xyzAbC_123-xyz";

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
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private PartySettingsCommandService partySettingsCommandService;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    @Test
    void aFirstVisit_withLongLists_getsTheWholePage_withTheFormsToken() throws Exception {
        String songs = IntStream.rangeClosed(1, com.scan2play.util.SongList.MAX_LINES)
                .mapToObj(i -> "Wykonawca numer " + i + " - Bardzo długi tytuł piosenki " + i).collect(Collectors.joining("\n"));
        when(partySettingsRepository.findByHostToken(TOKEN)).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("ABC12")
                .djName("DJ Koko").hostBlocked(songs).hostWanted(songs).hostToken(TOKEN).hostTokenMadeAt(Instant.now()).build()));

        MvcResult result = mockMvc.perform(get("/h/" + TOKEN).header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResolvedException()).isNull();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html.length()).as("the lists make the page longer than the 8 KB a session must be made in").isGreaterThan(8192);
        int form = html.indexOf("id=\"hostForm\"");
        assertThat(html.indexOf("name=\"_csrf\"", form)).as("the form's token, within the first 8 KB").isBetween(form, 8192);
        assertThat(html).contains("id=\"hostForm\"", "name=\"_csrf\"", "<footer", "</html>");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).as("the session the form needs").anyMatch(c -> c.startsWith("SESSION="));
    }
}
