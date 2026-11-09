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
        when(service.preview()).thenReturn(new AdminReportService.Report("Scan2Play — raport za 7.11: 40 wejść, 1 impreza", text, "", view()));
        when(service.address()).thenReturn("scan2playcontact@gmail.com");
        when(service.canSend()).thenReturn(true);
        when(service.sendNow()).thenReturn(true);
        when(service.schedule()).thenReturn(new AdminReportService.Schedule(true, java.time.LocalTime.of(9, 10),
                java.util.EnumSet.range(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY)));
        MockHttpSession owner = loggedIn("owner-1");

        String html = mockMvc.perform(get("/dj/stats/report").session(owner)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"reportView\"", "Raport za sobota 7.11.2026", "<progress max=\"100\" value=\"100\"",
                "s2p-dot s2p-dot-bad", "id=\"reportParties\"");
        assertThat(html).contains("id=\"reportText\"", "Wesele Ani i Tomka", "scan2playcontact@gmail.com", "id=\"reportSend\"",
                "name=\"_csrf\"", "</html>");
        write("stats-report.html", html);
        // the form's own token, as the browser sends it (no spring-security-test here)
        mockMvc.perform(post("/dj/stats/report").session(owner).param("_csrf", csrfToken(html)))
                .andExpect(redirectedUrl("/dj/stats/report?sent=yes"));
        verify(service).sendNow();

        // the schedule: the form's fields as the browser sends them
        assertThat(html).contains("id=\"reportTime\"", "value=\"09:10\"", "id=\"reportDayMONDAY\"", ">pon.<");
        assertThat(html).containsPattern("id=\"reportDayFRIDAY\"[^>]*checked").doesNotContainPattern("id=\"reportDaySUNDAY\"[^>]*checked");
        mockMvc.perform(post("/dj/stats/report/schedule").session(owner).param("_csrf", csrfToken(html)).param("on", "true")
                .param("time", "07:45").param("days", "MONDAY", "SUNDAY")).andExpect(redirectedUrl("/dj/stats/report?saved=yes"));
        verify(service).saveSchedule(new AdminReportService.Schedule(true, java.time.LocalTime.of(7, 45),
                java.util.Set.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SUNDAY)));
    }

    @Test
    void anotherDj_getsA404_andCannotSend() throws Exception {
        MockHttpSession dj = loggedIn("dj-2");
        mockMvc.perform(get("/dj/stats/report").session(dj)).andExpect(status().isNotFound());
        verify(service, never()).preview();
        // a POST without the form's token is refused before the controller, and with none to take from a page, so is this one
        mockMvc.perform(post("/dj/stats/report").session(dj)).andExpect(status().is4xxClientError());
        verify(service, never()).sendNow();
    }

    /** A full report for the eye: a week of visits, a new DJ, two parties (one with a long address and title), a failing check. */
    static AdminReportService.View view() {
        java.util.List<com.scan2play.service.SiteCounter.Day> days = new java.util.ArrayList<>();
        for (int back = 0; back < 7; back++) {
            java.util.Map<com.scan2play.service.SiteCounter.Event, Integer> counts = new java.util.EnumMap<>(com.scan2play.service.SiteCounter.Event.class);
            for (com.scan2play.service.SiteCounter.Event event : com.scan2play.service.SiteCounter.Event.values()) {
                counts.put(event, back * 3 % 7);
            }
            counts.put(com.scan2play.service.SiteCounter.Event.LANDING_PL, 40 - back * 5);
            days.add(new com.scan2play.service.SiteCounter.Day(java.time.LocalDate.parse("2026-11-07").minusDays(back), counts));
        }
        return AdminReportService.view(java.time.LocalDate.parse("2026-11-07"), days,
                java.util.List.of(new AdminReportService.NewOrganiser(java.time.Instant.parse("2026-11-06T13:02:00Z"), "AB12",
                        "a-very-long-address-of-an-organiser@example-of-a-long-domain.com", "Jan Kowalski", "PRO", 12)),
                java.util.List.of(new AdminReportService.PartyLine("AB12", "a-very-long-address-of-an-organiser@example-of-a-long-domain.com",
                                "PRO", "Wesele Ani i Tomka", 48, 31, 5, 3, 4, 2, 6),
                        new AdminReportService.PartyLine("CD34", null, "FREE", null, 2, 0, 0, 0, 0, 0, 0)), false,
                new AdminReportService.Health(false, true, new com.scan2play.service.Mailer.Status(null, java.time.Instant.parse("2026-11-07T08:00:00Z"),
                        "the key was refused"), new com.scan2play.service.LogTally.Tally(java.time.Instant.parse("2026-11-07T08:10:00Z"), 1, 2,
                        java.util.List.of(new com.scan2play.service.LogTally.Line("WARN Mailer: E-mail not sent: {} [java.net.SocketTimeoutException]", 2)))),
                "https://www.scan2play.com.pl/dj/stats/report");
    }

    private static String csrfToken(String html) {
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("the form's CSRF token").isTrue();
        return token.group(1);
    }

    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }
}
