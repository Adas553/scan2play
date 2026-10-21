package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
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
        SecurityConfig.loginSuccess(remembered).onAuthenticationSuccess(
                request(session, "/login/oauth2/code/google", null, "navigate"), response, new TestingAuthenticationToken("dj", null));
        return response.getRedirectedUrl();
    }

    @Test
    void theDjsPageOpenedBeforeTheLogin_isWhereTheLoginReturns() throws Exception {
        MockHttpSession session = new MockHttpSession();
        remembered.saveRequest(request(session, "/dj/dashboard", "party=ABC12", "navigate"), new MockHttpServletResponse());

        assertThat(afterTheLogin(session)).contains("/dj/dashboard?party=ABC12");
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
