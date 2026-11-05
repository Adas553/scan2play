package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Zaloguj" sends the DJ to Google with {@code prompt=select_account}: Google asks which account every time — signing out of
 * Scan2Play leaves the browser signed in to Google, and the next login on a shared device must not go into the last DJ's account.
 */
class GoogleAccountPickerTest {

    @Test
    void theLogin_asksGoogleWhichAccount() {
        InMemoryClientRegistrationRepository clients = new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE
                .getBuilder("google").clientId("id").clientSecret("secret").build());
        MockHttpServletRequest login = new MockHttpServletRequest("GET", SecurityConfig.GOOGLE_LOGIN);
        login.setServletPath(SecurityConfig.GOOGLE_LOGIN);

        OAuth2AuthorizationRequest request = SecurityConfig.accountPicker(clients).resolve(login);

        assertThat(request).isNotNull();
        assertThat(request.getAdditionalParameters()).containsEntry("prompt", "select_account");
        assertThat(request.getAuthorizationRequestUri()).startsWith("https://accounts.google.com/").contains("prompt=select_account",
                "client_id=id", "scope=openid%20profile%20email");
    }
}
