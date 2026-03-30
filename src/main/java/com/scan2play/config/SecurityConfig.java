package com.scan2play.config;

import lombok.extern.slf4j.Slf4j;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Public resources, landing page, and guest party views
                        .requestMatchers("/", "/p/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // OAuth2 login endpoints must be public
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // Spotify API OAuth endpoints (custom flow for playback token)
                        .requestMatchers("/spotify/**").permitAll()
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
