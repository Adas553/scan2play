package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.PartyStaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The staff's invitation page and the panel switcher (V30). */
class StaffControllerTest {

    private static final String TOKEN = "invite-token";

    private PartyStaffService staff;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StaffController(staff, sessionHelper)).build();
        when(staff.partyOfLink(anyString())).thenReturn(Optional.empty());
        when(staff.partyOfLink(TOKEN)).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("PUB01").djName("Klub Ola").build()));
    }

    private static OAuth2AuthenticationToken user(String id) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", id), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    void theInvitation_namesTheParty_keepsTheTokenForTheLogin_andLeadsThroughGoogle() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/join/" + TOKEN).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("join"))
                .andExpect(model().attribute("joinPartyName", "Klub Ola"))
                .andExpect(model().attribute("joinUrl", "/start"));

        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isEqualTo(TOKEN);
    }

    @Test
    void loggedInAlready_theInvitationLeadsStraightToThePanel() throws Exception {
        mockMvc.perform(get("/join/" + TOKEN).principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(model().attribute("joinUrl", "/dj/dashboard"));
    }

    @Test
    void anOldOrUnknownLink_isNotFound_andKeepsNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/join/old-token").session(session)).andExpect(status().isNotFound());

        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isNull();
    }

    /** A link pasted in the app (its browser has a login of its own): the token waits for the panel, which joins. */
    @Test
    void aPastedLink_keepsItsTokenForThePanel_whateverShapeItIsPastedIn() throws Exception {
        String[][] pasted = {
                {"https://www.scan2play.com.pl/join/AbC_12-x", "AbC_12-x"},
                {"  http://localhost:8080/join/AbC_12-x/  ", "AbC_12-x"},
                {"AbC_12-x", "AbC_12-x"},
                {"https://evil.example/whatever?x=1", "-"},
        };
        for (String[] link : pasted) {
            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(post("/dj/join").param("link", link[0]).principal(user("kasia")).session(session))
                    .andExpect(redirectedUrl("/dj/dashboard"));
            assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).as(link[0]).isEqualTo(link[1]);
        }
    }

    @Test
    void theSwitcher_opensAPartyTheyWorkAt_orTheirOwnPanel() throws Exception {
        mockMvc.perform(post("/dj/panel").param("party", "PUB01").principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard"));
        mockMvc.perform(post("/dj/panel").principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard"));

        verify(sessionHelper).switchTo(eq("PUB01"), any(), any());
        verify(sessionHelper).switchToOwnParty(any(), any());
        verify(sessionHelper, never()).switchTo(eq(""), any(), any());
    }
}
