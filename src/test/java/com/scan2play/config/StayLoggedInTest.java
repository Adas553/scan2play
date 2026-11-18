package com.scan2play.config;

import com.scan2play.controller.PasswordLoginController;
import com.scan2play.entity.DjAccountEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Nie wylogowuj mnie na tym urządzeniu" at the login ({@link SecurityConfig#loginSuccess}, {@link StayLoggedIn}): Google's login by the
 * choice kept before the redirect to Google — across the login's new session id —, a password's by its form's box; anything else the
 * usual session. The request attribute is what Spring Boot's cookie serializer gives a persistent cookie by ({@code StayLoggedInIT}
 * shows the cookie itself).
 */
class StayLoggedInTest {

    private static final int USUAL = 30 * 60;
    private static final int LONG = 14 * 24 * 60 * 60;

    /** A clock the tests move: the Google choice's life. */
    private Instant now = Instant.parse("2026-11-18T20:00:00Z");
    private final StayLoggedIn stayLoggedIn = new StayLoggedIn(Duration.ofSeconds(USUAL), new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private static Authentication google() {
        DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"), Map.of("sub", "1074"), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }

    private static Authentication password() {
        return PasswordLoginController.authentication(DjAccountEntity.builder().id(7L).email("ola@example.com").passwordHash("x")
                .createdAt(Instant.EPOCH).build());
    }

    /** A session of the login page (the usual life), the button or the form pressed in it. */
    private static MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        session.setMaxInactiveInterval(USUAL);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSession(session);
        request.setRequestedSessionId(session.getId());   // the browser's cookie named it (Spring Security changes only such an id)
        request.setRequestedSessionIdValid(true);
        return request;
    }

    /** The login's end as Spring Security runs it: a new session id (session fixation), then the success handler. */
    private MockHttpServletRequest logIn(MockHttpServletRequest request, Authentication authentication) throws Exception {
        String before = request.getSession().getId();
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, new MockHttpServletResponse());
        assertThat(request.getSession().getId()).as("a new session id").isNotEqualTo(before);
        SecurityConfig.loginSuccess(SecurityConfig.djPagesRemembered(), () -> { }, stayLoggedIn)
                .onAuthenticationSuccess(request, new MockHttpServletResponse(), authentication);
        return request;
    }

    private static boolean persistentCookie(MockHttpServletRequest request) {
        return request.getAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR) != null;
    }

    private void pressGoogle(MockHttpSession session, String box) {
        MockHttpServletRequest button = request("POST", "/login/google", session);
        if (box != null) {
            button.addParameter(StayLoggedIn.PARAMETER, box);
        }
        stayLoggedIn.keepForGoogle(button);
    }

    @Test
    void google_withTheBox_isLong_afterTheNewSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, "true");

        MockHttpServletRequest back = logIn(request("GET", "/login/oauth2/code/google", session), google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(LONG);
        assertThat(persistentCookie(back)).isTrue();
        assertThat(back.getSession().getAttribute(StayLoggedIn.CHOSEN_FOR_GOOGLE)).as("read once").isNull();
    }

    @Test
    void google_withoutTheBox_isTheUsualSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, null);

        MockHttpServletRequest back = logIn(request("GET", "/login/oauth2/code/google", session), google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(back)).isFalse();
    }

    /** The box ticked, then cleared, then the button again (a second tab, "back"): the last press counts. */
    @Test
    void google_theBoxClearedAtTheLastPress_isTheUsualSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, "true");
        pressGoogle(session, null);

        MockHttpServletRequest back = logIn(request("GET", "/login/oauth2/code/google", session), google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(back)).isFalse();
    }

    /** Google's return is Google's redirect: a box's parameter on it (a crafted link) is not a choice. */
    @Test
    void google_aParameterOnTheReturn_isNoChoice() throws Exception {
        MockHttpServletRequest back = request("GET", "/login/oauth2/code/google", new MockHttpSession());
        back.addParameter(StayLoggedIn.PARAMETER, "true");

        logIn(back, google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(back)).isFalse();
    }

    @Test
    void password_withTheBox_isLong_withoutIt_usual() throws Exception {
        MockHttpServletRequest ticked = request("POST", "/login/password", new MockHttpSession());
        ticked.addParameter(StayLoggedIn.PARAMETER, "true");
        MockHttpServletRequest clear = request("POST", "/login/password", new MockHttpSession());

        logIn(ticked, password());
        logIn(clear, password());

        assertThat(ticked.getSession().getMaxInactiveInterval()).isEqualTo(LONG);
        assertThat(persistentCookie(ticked)).isTrue();
        assertThat(clear.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(clear)).isFalse();
    }

    /** "Zaloguj się przez Google" with the box, back, then the password without it: the password's own box counts. */
    @Test
    void password_afterAGooglePressWithTheBox_goesByItsOwnBox() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, "true");

        MockHttpServletRequest login = logIn(request("POST", "/login/password", session), password());

        assertThat(login.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(login)).isFalse();
        assertThat(login.getSession().getAttribute(StayLoggedIn.CHOSEN_FOR_GOOGLE)).isNull();
    }

    /** Google's login abandoned (the tab closed), Google's login opened by its address 11 minutes later: no choice any more. */
    @Test
    void google_aChoiceOlderThanTenMinutes_isNoChoice() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, "true");
        now = now.plus(StayLoggedIn.GOOGLE_CHOICE_LIFE).plusSeconds(60);

        MockHttpServletRequest back = logIn(request("GET", "/login/oauth2/code/google", session), google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(back)).isFalse();
    }

    /** Google's login failed (refused, cancelled): its choice goes with it — the next Google login is a usual one. */
    @Test
    void google_aFailedLogin_forgetsTheChoice() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pressGoogle(session, "true");

        SecurityConfig.loginFailure().onAuthenticationFailure(request("GET", "/login/oauth2/code/google", session),
                new MockHttpServletResponse(), new org.springframework.security.oauth2.core.OAuth2AuthenticationException("access_denied"));
        MockHttpServletRequest back = logIn(request("GET", "/login/oauth2/code/google", session), google());

        assertThat(back.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(back)).isFalse();
    }

    /** The account's confirmation and a new password have no box: a "remember-me" added to their request by hand is no choice. */
    @Test
    void password_theLinksPages_areUsualWhateverTheRequestSays() throws Exception {
        for (String page : new String[] {"/register/confirm", "/password/reset"}) {
            MockHttpServletRequest login = request("POST", page, new MockHttpSession());
            login.addParameter(StayLoggedIn.PARAMETER, "true");

            logIn(login, password());

            assertThat(login.getSession().getMaxInactiveInterval()).as(page).isEqualTo(USUAL);
            assertThat(persistentCookie(login)).as(page).isFalse();
        }
    }

    /** A long session whose owner logs in again without the box (another account, say): the usual life again. */
    @Test
    void aLongSession_loggedInAgainWithoutTheBox_isUsualAgain() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest login = request("POST", "/login/password", session);
        session.setMaxInactiveInterval(LONG);

        logIn(login, password());

        assertThat(login.getSession().getMaxInactiveInterval()).isEqualTo(USUAL);
        assertThat(persistentCookie(login)).isFalse();
    }
}
