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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Main security configuration for Scan2Play.
 * <p>
 * Handles OAuth2 login for DJs and public access for guests.
 * </p>
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * The Content-Security-Policy of every page (review item 5.1). The pages have no inline script and no inline handler; what
     * comes from elsewhere: only the iTunes search of the song suggestions. No frames (the default, {@code 'self'}). No inline style either: no {@code style="…"} attribute and no {@code <style>} element (the classes are in
     * {@code app.css}; scripts change styles through {@code element.style}, which the policy allows). Violations go to {@link com.scan2play.controller.CspReportController}. Public for the browser tests: their stand-in
     * server sends this same policy (written by {@code DashboardPageRenderTest}), enforced.
     */
    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self' https://itunes.apple.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
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

    /** Google's login (Spring's OAuth 2 client): where a DJ's page opened without a session goes. */
    static final String GOOGLE_LOGIN = "/oauth2/authorization/google";

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
     * <li>a DJ's page opened in the browser — straight to Google's login, which usually lets a person signed in to Google through
     *     without a question, and back to that page ({@link #djPagesRemembered}): a DJ whose session expired while the phone was in
     *     the pocket (30 minutes; a hidden panel does not poll) comes back to their panel, not to the landing page for DJs;</li>
     * <li>a fetch of the panel's scripts — the landing page, as before: the script sees the redirect and loads its page again
     *     ({@code polling.js} {@code reloadOnce}), which is then a page opened in the browser;</li>
     * <li>any other address no handler is public for — 404: a mistyped address is "not found", not the landing page (for a search
     *     engine a soft 404).</li>
     * </ul>
     */
    static AuthenticationEntryPoint loginEntryPoint() {
        AuthenticationEntryPoint google = new LoginUrlAuthenticationEntryPoint(GOOGLE_LOGIN);
        AuthenticationEntryPoint landing = new LoginUrlAuthenticationEntryPoint("/");
        return (request, response, refused) -> {
            if (!DJ_PAGES.matches(request)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            } else if (NAVIGATION.matches(request)) {
                google.commence(request, response, refused);
            } else {
                landing.commence(request, response, refused);
            }
        };
    }

    /**
     * The DJ's page asked for before the login, remembered in the session for after it — only a page opened in the browser: a poll of
     * the queue or a fragment of the history would open bare after the login.
     */
    static RequestCache djPagesRemembered() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(request -> DJ_PAGES.matches(request) && NAVIGATION.matches(request));
        return cache;
    }

    /**
     * After the login: the DJ's page remembered before it ({@link #djPagesRemembered}), else the panel. {@code loggedIn} runs first —
     * the site's counter of logins (V40).
     */
    static AuthenticationSuccessHandler loginSuccess(RequestCache remembered, Runnable loggedIn) {
        SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/dj/dashboard");
        success.setRequestCache(remembered);
        return (request, response, authentication) -> {
            loggedIn.run();
            success.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /** The deploy's version the scripts and styles are linked under ({@code /<version>/js/...}, application.properties). */
    @Value("${spring.web.resources.chain.strategy.fixed.version:dev}")
    private String assetVersion;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, MessageSource messageSource, LocaleResolver localeResolver,
                                           ObjectProvider<SiteCounter> siteCounter) throws Exception {
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
                .csrf(csrf -> csrf.ignoringRequestMatchers("/csp-report"))
                // a guest's request from a page open longer than its session: back to the party with the typed song, not a 403
                .exceptionHandling(denied -> denied.accessDeniedHandler(new GuestFormExpiredHandler(messageSource, localeResolver))
                        // no login: a DJ's page → Google's login and back; a fetch of the panel → "/"; any other address → 404
                        .authenticationEntryPoint(loginEntryPoint()))
                .requestCache(cache -> cache.requestCache(remembered))
                .authorizeHttpRequests(auth -> auth
                        // Public resources, landing page, and guest party views
                        .requestMatchers("/", "/p/**", "/css/**", "/js/**", "/webjars/**", "/images/**", "/favicon.ico", "/error").permitAll()
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
                        // The staff's invitation (V30): opened before the login, it names the party (StaffController)
                        .requestMatchers("/join", "/join/*").permitAll()   // GET /join: the link pasted on the landing page
                        // Legal pages (Privacy Policy, Terms of Service) and the guide "Jak działa Scan2Play" (public: a DJ reads it before
                        // the first login, a person of the staff before joining — the owner, 2026-10-12)
                        .requestMatchers("/privacy", "/terms", "/jak-to-dziala", "/how-it-works", "/pro").permitAll()
                        // the pages "Dla kogo" (ForWhomController, 2026-10-21)
                        .requestMatchers("/dj-weselny", "/kluby", "/puby").permitAll()
                        // what a search engine reads first (SeoController); before, both led to the login page, "/"
                        .requestMatchers("/robots.txt", "/sitemap.xml").permitAll()
                        // the demo (DemoController): the demo party's panel, only to look at, and its queue and history
                        .requestMatchers(HttpMethod.GET, "/demo", "/demo/**").permitAll()
                        // OAuth2 login endpoints must be public
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // DJ dashboard is protected
                        .requestMatchers("/dj/**").authenticated()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        // Use root landing page as custom login entry point
                        .loginPage("/")
                        // back to the DJ's page asked for before the login (with its ?party=), else the panel
                        .successHandler(loginSuccess(remembered, () -> siteCounter.ifAvailable(counter -> counter.count(SiteCounter.Event.LOGIN))))
                        .failureHandler((request, response, exception) -> {
                            log.error("OAuth2 Login Failed: {}", exception.getMessage());
                            response.sendRedirect("/?error=auth_failed");
                        })
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
