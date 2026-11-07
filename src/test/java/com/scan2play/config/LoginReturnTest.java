package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Map;
import org.springframework.security.web.savedrequest.RequestCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review of 2026-10-21, C2: after Google's login the DJ comes back to the page asked for before it, with its party — not to the
 * default panel ({@code defaultSuccessUrl("/dj/dashboard", true)} did that: a person of the staff came back to their own party).
 * Only a page opened in the browser is remembered; the panel's fetches are not.
 */
class LoginReturnTest {

    private final RequestCache remembered = SecurityConfig.djPagesRemembered();

    private static MockHttpServletRequest request(MockHttpSession session, String path, String query, String fetchMode) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setQueryString(query);
        request.setSession(session);
        if (fetchMode != null) {
            request.addHeader("Sec-Fetch-Mode", fetchMode);
        }
        return request;
    }

    /** Where the login sends the person, with what the session remembers. */
    private String afterTheLogin(MockHttpSession session) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.loginSuccess(remembered, () -> logins++).onAuthenticationSuccess(
                request(session, "/login/oauth2/code/google", null, "navigate"), response, new TestingAuthenticationToken("dj", null));
        return response.getRedirectedUrl();
    }

    /** The logins the site's counter was told of (V40). */
    private int logins;

    /** Every login is counted once (V40, SiteCounter.Event.LOGIN), wherever it leads. */
    @Test
    void aLogin_isCounted() throws Exception {
        afterTheLogin(new MockHttpSession());
        afterTheLogin(new MockHttpSession());
        org.assertj.core.api.Assertions.assertThat(logins).isEqualTo(2);
    }

    @Test
    void theDjsPageOpenedBeforeTheLogin_isWhereTheLoginReturns() throws Exception {
        MockHttpSession session = new MockHttpSession();
        remembered.saveRequest(request(session, "/dj/dashboard", "party=ABC12", "navigate"), new MockHttpServletResponse());

        assertThat(afterTheLogin(session)).contains("/dj/dashboard?party=ABC12");
    }

    /** Where a failed login sends the person, with what the session holds. */
    private static String afterAFailedLogin(MockHttpSession session) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.loginFailure().onAuthenticationFailure(request(session, "/login/oauth2/code/google", "code=x&state=y", "navigate"),
                response, new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));
        return response.getRedirectedUrl();
    }

    /**
     * A second "Zaloguj" replaces the first login on its way, whose return from Google then fails: a DJ logged in already went to
     * "Błąd logowania" while their panel worked. They go back to the panel; a person not logged in still reads the message.
     */
    @Test
    void aFailedReturnFromGoogle_ofAPersonLoggedInAlready_opensThePanel() throws Exception {
        MockHttpSession loggedIn = new MockHttpSession();
        OAuth2AuthenticationToken dj = new OAuth2AuthenticationToken(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "dj"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        loggedIn.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(dj));
        assertThat(afterAFailedLogin(loggedIn)).isEqualTo("/dj/dashboard");

        assertThat(afterAFailedLogin(new MockHttpSession())).isEqualTo("/?error=auth_failed");
    }

    @Test
    void nothingRemembered_theLoginOpensThePanel() throws Exception {
        assertThat(afterTheLogin(new MockHttpSession())).isEqualTo("/dj/dashboard");
    }

    @Test
    void aFetchOfThePanel_isNotRemembered() throws Exception {
        MockHttpSession session = new MockHttpSession();
        remembered.saveRequest(request(session, "/dj/dashboard/updates", "partyCode=ABC12", "cors"), new MockHttpServletResponse());
        remembered.saveRequest(request(session, "/dj/history-view/fragment", "partyCode=ABC12", null), new MockHttpServletResponse());

        assertThat(afterTheLogin(session)).isEqualTo("/dj/dashboard");
    }
}
