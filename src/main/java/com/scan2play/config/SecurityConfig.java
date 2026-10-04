package com.scan2play.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .headers(headers -> headers.contentSecurityPolicy(csp -> {
                    csp.policyDirectives(CONTENT_SECURITY_POLICY);
                    if (!enforceCsp) {
                        csp.reportOnly();
                    }
                }))
                // A browser sends its CSP report without a CSRF token
                .csrf(csrf -> csrf.ignoringRequestMatchers("/csp-report"))
                .authorizeHttpRequests(auth -> auth
                        // Public resources, landing page, and guest party views
                        .requestMatchers("/", "/p/**", "/css/**", "/js/**", "/webjars/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // The landing page's tiles: the kind of party chosen, then Google's login (HomeController.start)
                        .requestMatchers("/start/**").permitAll()
                        // The browsers' reports of the Content-Security-Policy
                        .requestMatchers("/csp-report").permitAll()
                        // Legal pages (Privacy Policy, Terms of Service)
                        .requestMatchers("/privacy", "/terms").permitAll()
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
                        .defaultSuccessUrl("/dj/dashboard", true)
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
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }
}
