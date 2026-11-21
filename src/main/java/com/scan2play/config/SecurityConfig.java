package com.scan2play.config;

import jakarta.servlet.http.HttpServletResponse;
import com.scan2play.service.SiteCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Clock;
import java.time.Duration;

/**
 * Main security configuration for Scan2Play.
 * <p>
 * Handles the DJs' login (Google's OAuth2, or an e-mail address and a password — PasswordLoginController) and public access for guests.
 * </p>
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * The Content-Security-Policy of every page (review item 5.1). The pages have no inline script and no inline handler; what
     * comes from elsewhere: only the iTunes search of the song suggestions. No frames (the default, {@code 'self'}). No inline style either: no {@code style="…"} attribute and no {@code <style>} element (the classes are in
     * {@code app.css}; scripts change styles through {@code element.style}, which the policy allows). {@code form-action} names Google's
     * login page: the login page's Google button is a form (POST /login/google, the box "Nie wylogowuj mnie" with it) answered by
     * redirects to {@link #GOOGLE_AUTHORIZATION}, and a browser holds a form's whole chain of redirects to the directive — with
     * {@code 'self'} alone Chrome stops the login on our own page. Violations go to {@link com.scan2play.controller.CspReportController}. Public for the browser tests: their stand-in
     * server sends this same policy (written by {@code DashboardPageRenderTest}), enforced.
     */
    /** Where Google's login sends the browser (Spring's {@code CommonOAuth2Provider.GOOGLE}: {@code https://accounts.google.com/o/oauth2/v2/auth}). */
    static final String GOOGLE_AUTHORIZATION = "https://accounts.google.com";

    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self' https://itunes.apple.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self' " + GOOGLE_AUTHORIZATION,
            "frame-ancestors 'none'",
            "report-uri /csp-report");

    /**
     * Whether the policy is enforced ({@code security.csp.enforce}, env {@code CSP_ENFORCE}). Off: the browsers only report what it
     * would block ({@code Content-Security-Policy-Report-Only}) — the first step, until the reports are quiet.
     */
    @Value("${security.csp.enforce:false}")
    private boolean enforceCsp;

    /**
     * What a browser may use on our pages: nothing of this — no page asks for a camera, a microphone, the location, a payment or a
     * device (review 2026-10-14). The notifications are not a feature of this header (the browser asks the person).
     */
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), "
            + "browsing-topics=()";

    /**
     * The login page (PasswordLoginController): Google's login or an e-mail address and a password (V72) — where a DJ's page opened
     * without a session goes.
     */
    static final String LOGIN_PAGE = "/login";

    /**
     * Where the forms' CSRF tokens are kept (the session) — one instance, so that a login made outside Spring Security's filters
     * (PasswordLoginController) can give the session a new token the way Spring Security reads it.
     */
    public static final CsrfTokenRepository CSRF_TOKENS = new HttpSessionCsrfTokenRepository();

    /** Google's login (Spring's OAuth 2 client): where the login page's Google button leads (through {@code POST /login/google}). */
    public static final String GOOGLE_LOGIN = "/oauth2/authorization/google";

    /** The DJ's pages and their fetches. */
    private static final RequestMatcher DJ_PAGES = PathPatternRequestMatcher.withDefaults().matcher("/dj/**");

    /**
     * A page the browser opens (a link, the address bar, a reload, a notification), not a fetch of a page's script: a GET the browser
     * marks {@code Sec-Fetch-Mode: navigate}. A browser without the header counts as a fetch — the landing page, as before.
     */
    private static final RequestMatcher NAVIGATION = request -> "GET".equals(request.getMethod())
            && "navigate".equals(request.getHeader("Sec-Fetch-Mode"));

    /**
     * Where a request without a login goes (the review of 2026-10-21, C2, C3):
     * <ul>
     * <li>a DJ's page opened in the browser — the login page (Google or a password), and after it back to that page
     *     ({@link #djPagesRemembered}): a DJ whose session expired while the phone was in the pocket (30 minutes; a hidden panel does
     *     not poll) comes back to their panel, not to the landing page for DJs;</li>
     * <li>a fetch of the panel's scripts — the landing page, as before: the script sees the redirect and loads its page again
     *     ({@code polling.js} {@code reloadOnce}), which is then a page opened in the browser;</li>
     * <li>any other address no handler is public for — 404: a mistyped address is "not found", not the landing page (for a search
     *     engine a soft 404).</li>
     * </ul>
     */
    static AuthenticationEntryPoint loginEntryPoint() {
        AuthenticationEntryPoint login = new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE);
        AuthenticationEntryPoint landing = new LoginUrlAuthenticationEntryPoint("/");
        return (request, response, refused) -> {
            if (!DJ_PAGES.matches(request)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            } else if (NAVIGATION.matches(request)) {
                login.commence(request, response, refused);
            } else {
                landing.commence(request, response, refused);
            }
        };
    }

    /**
     * The DJ's page asked for before the login, remembered in the session for after it — only a page opened in the browser: a poll of
     * the queue or a fragment of the history would open bare after the login.
     */
    public static RequestCache djPagesRemembered() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(request -> DJ_PAGES.matches(request) && NAVIGATION.matches(request));
        return cache;
    }

    /**
     * After the login — Google's, or a password's (PasswordLoginController), its session's id new already: the session long or
     * usual by "Nie wylogowuj mnie na tym urządzeniu" ({@link StayLoggedIn}), then the DJ's page remembered before it
     * ({@link #djPagesRemembered}), else the panel. {@code loggedIn} runs first — the site's counter of logins (V40).
     */
    public static AuthenticationSuccessHandler loginSuccess(RequestCache remembered, Runnable loggedIn, StayLoggedIn stayLoggedIn) {
        SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/dj/dashboard");
        success.setRequestCache(remembered);
        return (request, response, authentication) -> {
            loggedIn.run();
            stayLoggedIn.atLogin(request, response, authentication);
            success.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /**
     * "Nie wylogowuj mnie na tym urządzeniu" — a bean of this configuration, so that every test that imports it has it. The usual
     * life is Spring Session's own default ({@code spring.session.timeout}, else {@code server.servlet.session.timeout}, else 30 min).
     */
    @Bean
    StayLoggedIn stayLoggedIn(@Value("${spring.session.timeout:${server.servlet.session.timeout:30m}}") Duration usual) {
        return new StayLoggedIn(usual, Clock.systemUTC());
    }

    /**
     * A login that failed. Spring keeps one login on its way per session: a second "Zaloguj" (another tab, a double tap, "back" to
     * Google's account picker) replaces the first, whose return from Google then fails ({@code authorization_request_not_found}). A
     * person who is logged in already goes back to the panel instead of reading "Błąd logowania" about a session that works; anyone
     * else gets the landing page's "Błąd logowania. Spróbuj ponownie."
     */
    static AuthenticationFailureHandler loginFailure() {
        HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
        return (request, response, failure) -> {
            StayLoggedIn.forgetGoogleChoice(request);   // "Nie wylogowuj mnie" was for this login, not a later one
            // the context of this request was cleared before the handler runs; the session still holds the person's login
            Authentication already = contexts.loadDeferredContext(request).get().getAuthentication();
            if (already instanceof OAuth2AuthenticationToken && already.isAuthenticated()) {
                log.info("OAuth2 login failed ({}) for a person logged in already: back to the panel", failure.getMessage());
                response.sendRedirect("/dj/dashboard");
                return;
            }
            log.error("OAuth2 Login Failed: {}", failure.getMessage());
            response.sendRedirect("/?error=auth_failed");
        };
    }

    /**
     * Google's login always asks which account ({@code prompt=select_account}): signing out of Scan2Play does not sign out of Google,
     * and without it the next "Zaloguj" on a shared device (a club's computer) went straight into the last DJ's account.
     */
    static OAuth2AuthorizationRequestResolver accountPicker(ClientRegistrationRepository clients) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(clients,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(request -> request.additionalParameters(parameters ->
                parameters.put("prompt", "select_account")));
        return resolver;
    }

    /** The deploy's version the scripts and styles are linked under ({@code /<version>/js/...}, application.properties). */
    @Value("${spring.web.resources.chain.strategy.fixed.version:dev}")
    private String assetVersion;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, MessageSource messageSource, LocaleResolver localeResolver,
                                           ObjectProvider<SiteCounter> siteCounter, ClientRegistrationRepository clients,
                                           StayLoggedIn stayLoggedIn) throws Exception {
        RequestCache remembered = djPagesRemembered();
        http
                .headers(headers -> headers.contentSecurityPolicy(csp -> {
                            csp.policyDirectives(CONTENT_SECURITY_POLICY);
                            if (!enforceCsp) {
                                csp.reportOnly();
                            }
                        })
                        // only our own pages learn which page a click came from: the hosts' (/h/…) and the invitation's (/join/…)
                        // addresses are their secrets, and a page linked from them (the legal pages' links out) must not get them
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY)))
                // A browser sends its CSP report without a CSRF token
                // A mail program's one-click "Unsubscribe" (RFC 8058, the automatic e-mails' "Nie wysyłaj mi tego", V64) neither
                // An event's hosts' plan (V77): its link's secret lets it write, no session or cookie does — and a plan is
                // written slowly: a form kept open past the session must not lose it to a refused token
                // The DJ's program on the DJ's computer (V78, DeviceApiController) sends a token of its own, never a cookie
                .csrf(csrf -> csrf.csrfTokenRepository(CSRF_TOKENS).ignoringRequestMatchers("/csp-report", "/email/stop/*",
                        "/h/e/*", "/h/e/*/template", "/api/device/pair", "/api/device/plays"))
                // a guest's request from a page open longer than its session: back to the party with the typed song, not a 403
                .exceptionHandling(denied -> denied.accessDeniedHandler(new GuestFormExpiredHandler(messageSource, localeResolver))
                        // no login: a DJ's page → the login page and back; a fetch of the panel → "/"; any other address → 404
                        .authenticationEntryPoint(loginEntryPoint()))
                .requestCache(cache -> cache.requestCache(remembered))
                .authorizeHttpRequests(auth -> auth
                        // Public resources, landing page, and guest party views
                        .requestMatchers("/", "/p/**", "/css/**", "/js/**", "/fonts/**", "/webjars/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // The landing page's button (and the old tile links /start/{kind}): Google's login (HomeController.start)
                        .requestMatchers("/start/**").permitAll()
                        // The web app's manifest and the service worker of the notifications: a browser fetches them without
                        // the login cookie (the manifest), or before any page asks (an update of the worker)
                        .requestMatchers("/manifest.webmanifest", "/sw.js").permitAll()
                        // The scripts and styles under the deploy's version (/<version>/js/..., spring.web.resources.chain.strategy.fixed)
                        // — that version only: "/*/js/**" let /dj/js/… past the login too (review 2026-10-14)
                        .requestMatchers("/" + assetVersion + "/js/**", "/" + assetVersion + "/css/**").permitAll()
                        // The browsers' reports of the Content-Security-Policy
                        .requestMatchers("/csp-report").permitAll()
                        // The hosts' lists (V29): a page opened by its secret link, without an account (HostController)
                        .requestMatchers("/h/*").permitAll()
                        // An event's plan for its hosts (V77): the page, its save and its template, by the event's hosts' link
                        .requestMatchers("/h/e/*", "/h/e/*/template").permitAll()
                        // The hosts' read-only link of an evening summary (V61): a page opened by its secret link (SharedSummaryController)
                        .requestMatchers(HttpMethod.GET, "/s/*").permitAll()
                        // the venue's kiosk (V56): a tablet without a login, its secret link and what its page sends; "/k" the
                        // same page by the kiosk's cookie
                        .requestMatchers("/k", "/k/*", "/k/*/*").permitAll()
                        // "Życzenia przed imprezą" (V68): an event's guests' page and its form, by its secret link (WishController)
                        .requestMatchers("/w/*").permitAll()
                        // The staff's invitation (V30): opened before the login, it names the party (StaffController)
                        // "Nie wysyłaj mi tego" of an e-mail (V64): its secret link, without a login (EmailStopController)
                        .requestMatchers("/email/stop/*").permitAll()
                        .requestMatchers("/join", "/join/*").permitAll()   // GET /join: the link pasted on the landing page
                        // Legal pages (Privacy Policy, Terms of Service) and the guide "Jak działa Scan2Play" (public: a DJ reads it before
                        // the first login, a person of the staff before joining — the owner, 2026-10-12)
                        .requestMatchers("/privacy", "/terms", "/jak-to-dziala", "/how-it-works", "/pro", "/cennik", "/pricing").permitAll()
                        // the pages "Dla kogo" (ForWhomController, 2026-10-21)
                        .requestMatchers("/dj-weselny", "/kluby", "/puby").permitAll()
                        // what a search engine reads first (SeoController); before, both led to the login page, "/"
                        .requestMatchers("/robots.txt", "/sitemap.xml").permitAll()
                        // what the uptime monitor asks (HealthController): the application and its database; HEAD too, because
                        // the free plans of the monitors check with HEAD, and a HEAD would otherwise meet "authenticated"
                        .requestMatchers(HttpMethod.GET, "/healthz").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/healthz").permitAll()
                        // the demo (DemoController): the demo party's panel, only to look at, and its queue and history
                        .requestMatchers(HttpMethod.GET, "/demo", "/demo/**").permitAll()
                        // OAuth2 login endpoints must be public; "/login/**" holds the password's form and "/login/google" (the
                        // Google button: the box "Nie wylogowuj mnie" kept, then Google's login) — both POSTs with the CSRF token
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // the login with a password (V72, PasswordLoginController): a new account, its confirmation, a new
                        // password — "/login" and "/login/**" (the login page, its form, "Sprawdź skrzynkę") are above
                        .requestMatchers("/register", "/register/confirm", "/password/forgot", "/password/reset").permitAll()
                        // the DJ's program on the DJ's computer (V78): its pairing code, then its own token (DeviceApiController)
                        .requestMatchers(HttpMethod.POST, "/api/device/pair", "/api/device/plays").permitAll()
                        // DJ dashboard is protected
                        .requestMatchers("/dj/**").authenticated()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        // the login page: Google's button and the password's form
                        .loginPage(LOGIN_PAGE)
                        // Google asks which account every time, also after "Wyloguj"
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(accountPicker(clients)))
                        // back to the DJ's page asked for before the login (with its ?party=), else the panel
                        .successHandler(loginSuccess(remembered, () -> siteCounter.ifAvailable(counter -> counter.count(SiteCounter.Event.LOGIN)),
                                stayLoggedIn))
                        .failureHandler(loginFailure())
                )
                .logout(logout -> logout
                        .logoutUrl("/dj/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        // Spring Session's cookie (the sessions are in the database, V11), not the servlet container's JSESSIONID
                        .deleteCookies("SESSION")
                );

        return http.build();
    }
}
