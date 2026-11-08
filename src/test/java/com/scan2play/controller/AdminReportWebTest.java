package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.service.AdminReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The owner's report page through the real security and template: the owner sees the report and the button, its form carries the
 * CSRF token and sends; another DJ gets a 404. The rendered page goes to {@code target/browser-harness/stats-report.html} for the
 * pages' audit (320 / 390 / 1280 px, {@code tools/page_audit.py}).
 */
@WebMvcTest(AdminReportController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
@TestPropertySource(properties = "scan2play.admin-id=owner-1")
class AdminReportWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminReportService service;

    private static MockHttpSession loggedIn(String sub) {
        DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google")));
        return session;
    }

    @Test
    void theOwner_seesTheReport_andSendsIt() throws Exception {
        String text = "Scan2Play — raport za sobota 7.11.2026\n\nRUCH (ludzie, bez robotów) — wczoraj · średnio 7 dni\n"
                + "Strona główna PL      40 ·   10,0\n\nIMPREZY WCZORAJ — wieczór 7.11, 6:00–6:00 (1)\nAB12  PRO  "
                + "a-very-long-address-of-an-organiser@example-of-a-long-domain.com · „Wesele Ani i Tomka”\n"
                + "  prośby 48 · zagrane 31 · AI odrzuciło 5 · przed AI 3 · kiosk 4 · napiwki 2 · dodane przez DJ-a 6\n";
        when(service.preview()).thenReturn(new AdminReportService.Report("Scan2Play — raport za 7.11: 40 wejść, 1 impreza", text, ""));
        when(service.address()).thenReturn("scan2playcontact@gmail.com");
        when(service.canSend()).thenReturn(true);
        when(service.sendNow()).thenReturn(true);
        MockHttpSession owner = loggedIn("owner-1");

        String html = mockMvc.perform(get("/dj/stats/report").session(owner)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"reportText\"", "Wesele Ani i Tomka", "scan2playcontact@gmail.com", "id=\"reportSend\"",
                "name=\"_csrf\"", "</html>");
        write("stats-report.html", html);
        mockMvc.perform(post("/dj/stats/report").session(owner).with(csrf())).andExpect(redirectedUrl("/dj/stats/report?sent=yes"));
        verify(service).sendNow();
    }

    @Test
    void anotherDj_getsA404_andCannotSend() throws Exception {
        MockHttpSession dj = loggedIn("dj-2");
        mockMvc.perform(get("/dj/stats/report").session(dj)).andExpect(status().isNotFound());
        mockMvc.perform(post("/dj/stats/report").session(dj).with(csrf())).andExpect(status().isNotFound());
        verify(service, never()).sendNow();
    }

    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }
}
