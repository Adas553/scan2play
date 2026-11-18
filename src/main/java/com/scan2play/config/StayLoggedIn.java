package com.scan2play.config;

import com.scan2play.controller.PasswordLoginController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * "Nie wylogowuj mnie na tym urządzeniu" of the login page — for Google's login and a password's alike. Chosen: the session lives
 * {@link #LONG} without a request instead of the usual 30 minutes (the session's own {@code MAX_INACTIVE_INTERVAL} in
 * {@code SPRING_SESSION}, V11 — the same session, only longer), and its cookie outlives the browser. Not chosen — the default, a club's
 * shared computer —: the usual session, a cookie that ends with the browser.
 * <p>
 * Spring Session's {@link SpringSessionRememberMeServices} does both: the session's interval, and a request attribute after which
 * Spring Boot's cookie serializer ({@code DefaultCookieSerializer.setRememberMeRequestAttribute}, set by Boot whenever Spring Security
 * is there) gives the cookie a {@code Max-Age} — {@code Integer.MAX_VALUE}, not 14 days: the cookie is written only when the session's
 * id changes (the login), so a cookie of 14 days would log out a DJ who uses the panel every day 14 days after the login. Browsers
 * keep a cookie 400 days at most, so that is the cookie's real life (the privacy policy says so). The server ends the session after
 * 14 days without a request, and then the cookie names nothing. Its {@code Secure}, {@code HttpOnly} and {@code SameSite} are the
 * serializer's ({@code server.servlet.session.cookie.*}), the same as every session's.
 * <p>
 * Applied only at a login ({@link SecurityConfig#loginSuccess}), to the session that has just got a new id — never to a session
 * someone has already, and never by a request without a login. The box is read only where it is on the page: the login form
 * ({@link PasswordLoginController#LOGIN_FORM}) and Google's button; the account's confirmation and a new password log in for the
 * usual time whatever the request says (the link of an e-mail is often opened on a device that is not one's own).
 */
public class StayLoggedIn {

    /** How long a chosen session lives without a request. */
    public static final Duration LONG = Duration.ofDays(14);

    /** The box's name (Spring Security's usual one); its value "true" — {@link SpringSessionRememberMeServices} takes "on", "yes", "1" too. */
    public static final String PARAMETER = "remember-me";

    /**
     * When "Zaloguj się przez Google" was pressed with the box ticked ({@link #keepForGoogle}), read at the return from Google: the
     * return is Google's redirect, which carries no field of our form. Kept by the login's new session id (Spring Security changes
     * the id, the attributes stay).
     */
    static final String CHOSEN_FOR_GOOGLE = StayLoggedIn.class.getName() + ".CHOSEN_FOR_GOOGLE";

    /**
     * How long a Google choice waits for Google's return: a login abandoned at Google (the tab closed, "back") must not make a later
     * one long — on a shared computer the next person's, who opens Google's login by its address without seeing the box.
     */
    static final Duration GOOGLE_CHOICE_LIFE = Duration.ofMinutes(10);

    /** A password's login: the box is a field of the form posted with it. */
    private final SpringSessionRememberMeServices fromTheForm = services(false);
    /** Google's login: the box was read before the redirect to Google. */
    private final SpringSessionRememberMeServices chosenBefore = services(true);
    private final int usualSeconds;
    private final Clock clock;

    /** @param usual a session's usual life without a request ({@code server.servlet.session.timeout}) */
    public StayLoggedIn(Duration usual, Clock clock) {
        this.usualSeconds = (int) usual.toSeconds();
        this.clock = clock;
    }

    private static SpringSessionRememberMeServices services(boolean always) {
        SpringSessionRememberMeServices services = new SpringSessionRememberMeServices();
        services.setRememberMeParameterName(PARAMETER);
        services.setValiditySeconds((int) LONG.toSeconds());
        services.setAlwaysRemember(always);
        return services;
    }

    /** "Zaloguj się przez Google": the box's state kept in the session for the return from Google (box clear — nothing kept). */
    public void keepForGoogle(HttpServletRequest request) {
        HttpSession session = request.getSession();
        if (chosen(request)) {
            session.setAttribute(CHOSEN_FOR_GOOGLE, clock.instant());
        } else {
            session.removeAttribute(CHOSEN_FOR_GOOGLE);
        }
    }

    /** A login that failed (Google's): its choice goes with it. */
    static void forgetGoogleChoice(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(CHOSEN_FOR_GOOGLE);
        }
    }

    /** Whether the request's box is ticked — the values {@link SpringSessionRememberMeServices} takes. */
    public static boolean chosen(HttpServletRequest request) {
        String value = request.getParameter(PARAMETER);
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("yes")
                || value.equals("1"));
    }

    /**
     * At a login, its session's id new: long when it was chosen — by the login form's box, or before Google's login (within
     * {@link #GOOGLE_CHOICE_LIFE}) — else the usual life (a session that was long before, its owner logging in again without the
     * box, is long no more). A Google choice is read once: a later password's login of the same browser goes by its own box.
     */
    void atLogin(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession();
        boolean chosenForGoogle = session.getAttribute(CHOSEN_FOR_GOOGLE) instanceof Instant pressed
                && !clock.instant().isAfter(pressed.plus(GOOGLE_CHOICE_LIFE));
        session.removeAttribute(CHOSEN_FOR_GOOGLE);
        if (session.getMaxInactiveInterval() != usualSeconds) {
            session.setMaxInactiveInterval(usualSeconds);
        }
        if (byPassword(authentication)) {
            if (fromTheLoginForm(request)) {
                fromTheForm.loginSuccess(request, response, authentication);   // only with the form's box
            }
        } else if (chosenForGoogle) {
            chosenBefore.loginSuccess(request, response, authentication);
        }
    }

    private static boolean fromTheLoginForm(HttpServletRequest request) {
        return (request.getContextPath() + PasswordLoginController.LOGIN_FORM).equals(request.getRequestURI());
    }

    private static boolean byPassword(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token
                && PasswordLoginController.REGISTRATION_ID.equals(token.getAuthorizedClientRegistrationId());
    }
}
