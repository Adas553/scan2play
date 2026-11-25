package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.service.AdminReportService;
import com.scan2play.service.Mailer;
import com.scan2play.service.NewsEmailService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The owner's page of the news e-mail ({@code /dj/stats/news}, V80) through the real security and template: the owner sees the counts,
 * the words and the send, whose button names how many one send gives (at most {@link NewsEmailService#MAX_PER_SEND}); its forms carry
 * the CSRF token; another DJ gets a 404 and sends nothing. The page goes to {@code target/browser-harness/stats-news.html} for the
 * pages' audit (320 / 390 / 1280 px).
 */
@WebMvcTest(AdminNewsController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
@TestPropertySource(properties = "scan2play.admin-id=owner-1")
class AdminNewsWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NewsEmailService news;
    @MockitoBean
    private AdminReportService report;
    @MockitoBean
    private Mailer mailer;

    private static MockHttpSession loggedIn(String sub) {
        DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google")));
        return session;
    }

    @Test
    void theOwner_seesTheCounts_andSendsAtMostOneSendsWorth() throws Exception {
        when(news.counts()).thenReturn(new NewsEmailService.Counts(260, 10));
        when(news.previewText("pl")).thenReturn("Cześć!\n\ndodaliśmy w Scan2Play trzy rzeczy:\n\n🎧 Zagrane samo. Mały program…");
        when(news.previewText("en")).thenReturn("Hi!\n\nwe've added three things to Scan2Play:");
        when(news.sendLater()).thenReturn(true);
        when(news.sendTry("me@example.com", "en")).thenReturn(true);
        when(mailer.isOn()).thenReturn(true);
        when(report.address()).thenReturn("me@example.com");
        MockHttpSession owner = loggedIn("owner-1");

        String html = mockMvc.perform(get("/dj/stats/news").session(owner)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Zgody: <b>260</b>", "czeka: <b>250</b>", "id=\"newsTryPl\"", "id=\"newsTryEn\"", "Zagrane samo",
                "name=\"_csrf\"", "</html>");
        assertThat(html).as("one send gives at most 200: the button says so, and what is left")
                .contains(">Wyślij do 200<", "Reszta (50)");
        write("stats-news.html", html);

        mockMvc.perform(post("/dj/stats/news/send").session(owner).param("_csrf", csrfToken(html)))
                .andExpect(redirectedUrl("/dj/stats/news?done=sending"));
        verify(news).sendLater();
        mockMvc.perform(post("/dj/stats/news/try").session(owner).param("_csrf", csrfToken(html)).param("lang", "en"))
                .andExpect(redirectedUrl("/dj/stats/news?done=try"));
    }

    @Test
    void whileASendIsOnItsWay_noSecondSendIsOffered() throws Exception {
        when(news.counts()).thenReturn(new NewsEmailService.Counts(3, 1));
        when(news.isSending()).thenReturn(true);
        when(mailer.isOn()).thenReturn(true);

        String html = mockMvc.perform(get("/dj/stats/news").session(loggedIn("owner-1"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"newsSendingNow\"").doesNotContain("id=\"newsSendForm\"");
    }

    @Test
    void anotherDj_getsA404_andSendsNothing() throws Exception {
        MockHttpSession dj = loggedIn("dj-2");
        mockMvc.perform(get("/dj/stats/news").session(dj)).andExpect(status().isNotFound());
        verify(news, never()).counts();
        // without the form's token the POST is refused before the controller; and the controller itself refuses another DJ
        mockMvc.perform(post("/dj/stats/news/send").session(dj)).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/dj/stats/news/try").session(dj)).andExpect(status().is4xxClientError());
        verify(news, never()).sendLater();
        verify(news, never()).sendTry(anyString(), anyString());
    }

    @Test
    void anotherDj_withAValidToken_stillGetsA404() throws Exception {
        when(news.counts()).thenReturn(new NewsEmailService.Counts(1, 0));
        when(mailer.isOn()).thenReturn(true);
        MockHttpSession owner = loggedIn("owner-1");
        String html = mockMvc.perform(get("/dj/stats/news").session(owner)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // the same session's token, but the session's account changed to another DJ's
        MockHttpSession dj = loggedIn("dj-2");
        owner.getAttributeNames().asIterator().forEachRemaining(name -> {
            if (!name.equals(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)) {
                dj.setAttribute(name, owner.getAttribute(name));
            }
        });

        mockMvc.perform(post("/dj/stats/news/send").session(dj).param("_csrf", csrfToken(html))).andExpect(status().isNotFound());
        verify(news, never()).sendLater();
    }

    private static String csrfToken(String html) {
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("the form's CSRF token").isTrue();
        return token.group(1);
    }

    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }
}
