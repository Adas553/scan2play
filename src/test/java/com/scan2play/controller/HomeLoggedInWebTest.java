package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.service.SiteCounter;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The home page for a DJ who is logged in (the owner, 2026-10-24): the panel's logo leads to "/", and "/" sent a logged-in DJ straight
 * back to the panel — the logo seemed to do nothing. Now everyone gets the home page; a logged-in DJ's start buttons lead to the
 * panel ("Przejdź do panelu"), and their visit is not a visitor's in the site's counter. The home page's own logo is the way home too.
 */
@WebMvcTest(HomeController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class HomeLoggedInWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SiteCounter siteCounter;

    private static MockHttpSession loggedIn() {
        DefaultOAuth2User dj = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "dj-1", "name", "DJ Koko"), "sub");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(dj, dj.getAuthorities(), "google")));
        return session;
    }

    @Test
    void aLoggedInDj_getsTheHomePage_withTheWayToThePanel_notCounted() throws Exception {
        String html = mockMvc.perform(get("/").session(loggedIn()).header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"landingStart\"", "</html>");
        assertThat(html.split("href=\"/dj/dashboard\"", -1)).as("the start at the top and at the end lead to the panel").hasSize(3);
        assertThat(html).contains(">Przejdź do panelu<").doesNotContain("href=\"/start\"");
        verify(siteCounter, never()).countVisit(any(), any());
    }

    @Test
    void aVisitor_getsTheStart_andTheLogoLeadsHome() throws Exception {
        String html = mockMvc.perform(get("/").header("Accept-Language", "pl-PL").header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html.split("href=\"/start\"", -1)).hasSize(3);
        assertThat(html).doesNotContain("Przejdź do panelu").contains("<a href=\"/\" class=\"s2p-home-logo\"");
        verify(siteCounter).countVisit(any(), any());
    }
}
