package com.scan2play.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF protection
                .csrf(csrf -> csrf.disable())

                // Configure authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Publicly accessible paths - no authentication required
                        .requestMatchers("/", "/request", "/css/**", "/js/**").permitAll()

                        // Protected paths - require authentication
                        .requestMatchers("/dashboard/**").authenticated()

                        // All other requests are allowed without authentication
                        .anyRequest().permitAll()
                )

                // Configure OAuth2 Login
                .oauth2Login(oauth2 -> oauth2
                        // After successful login, always redirect to /dashboard
                        .defaultSuccessUrl("/dashboard", true)

                        // Custom failure handler - logs the error and redirects back to home
                        .failureHandler((request, response, exception) -> {
                            log.error("=== OAUTH2 LOGIN ERROR ===");
                            log.error("Failure reason: {}", exception.getMessage(), exception);
                            response.sendRedirect("/");
                        })
                );

        return http.build();
    }
}