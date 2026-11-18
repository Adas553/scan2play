package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;

import java.net.URI;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The policy lets the login page's Google button go to Google: the button is a form (POST /login/google) answered by redirects to
 * Google's login, and a browser holds a form's whole chain of redirects to {@code form-action} — with {@code 'self'} alone Chrome
 * stops the login on our own page ("Zaloguj się przez Google" did nothing).
 */
class ContentSecurityPolicyTest {

    @Test
    void formAction_allowsGooglesLoginPage_andNothingElseOutside() {
        String formAction = Arrays.stream(SecurityConfig.CONTENT_SECURITY_POLICY.split("; "))
                .filter(directive -> directive.startsWith("form-action ")).findFirst().orElseThrow();
        URI google = URI.create(CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("x")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").build().getProviderDetails().getAuthorizationUri());

        assertThat(formAction).isEqualTo("form-action 'self' " + google.getScheme() + "://" + google.getHost());
    }
}
