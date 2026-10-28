package com.scan2play.controller;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.service.AccountDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.ui.ExtendedModelMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The welcome card (V48) on a real PostgreSQL, through the panel itself: a new organiser's first panel shows it, a closed one never
 * again — and an account deleted ("Usuń konto") and made again by the next login is a new account: the card is back.
 */
class WelcomeAfterDeletionIT extends PostgresIntegrationTest {

    @Autowired DjDashboardController dashboard;
    @Autowired AccountDeletionService deletion;

    private static OAuth2AuthenticationToken ola(String sub) {
        DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("sub", sub, "name", "Ola", "email", sub + "@gmail.com", "email_verified", true), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }

    private Object welcome(OAuth2AuthenticationToken who, MockHttpSession session) {
        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(dashboard.dashboard(null, model, who, session)).isEqualTo("dashboard");
        return model.get(ViewAttributes.WELCOME);
    }

    @Test
    void aNewAccount_isWelcomed_untilClosed_andAgainAfterUsunKonto() {
        OAuth2AuthenticationToken ola = ola("welcome-" + System.nanoTime());

        assertThat(welcome(ola, new MockHttpSession())).as("the first login").isEqualTo(true);
        MockHttpSession session = new MockHttpSession();
        welcome(ola, session);
        String partyCode = (String) session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE);
        dashboard.dismissWelcome(partyCode, ola, session);
        assertThat(welcome(ola, new MockHttpSession())).as("closed: another device").isEqualTo(false);

        deletion.deleteAllUserData(ola.getName());

        assertThat(welcome(ola, new MockHttpSession())).as("the account made again").isEqualTo(true);
    }
}
