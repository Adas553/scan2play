package com.scan2play.controller;

import com.scan2play.service.AccountSessions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** "Wyloguj na innych urządzeniach": the account's sessions but this one (StayLoggedInIT shows it on the real sessions). */
class OtherDevicesControllerTest {

    @Test
    void theAccountsOtherSessionsEnd_thisOneStays_andThePanelOpens() {
        AccountSessions sessions = mock(AccountSessions.class);
        OAuth2AuthenticationToken dj = new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("sub", "dj-42"), "sub"), AuthorityUtils.createAuthorityList("OAUTH2_USER"), "google");
        MockHttpSession session = new MockHttpSession();

        String view = new OtherDevicesController(sessions).logOutOthers(dj, session);

        verify(sessions).endAllBut("dj-42", session.getId());
        assertThat(view).isEqualTo("redirect:/dj/dashboard");
    }
}
