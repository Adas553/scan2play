package com.scan2play.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Public resources and landing page
                        .requestMatchers("/", "/p/**", "/css/**", "/js/**", "/images/**").permitAll()
                        // OAuth2 login endpoints MUST be public
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // DJ dashboard is protected
                        .requestMatchers("/dj/**").authenticated()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        // We use the root page as our custom login page
                        .loginPage("/")
                        // The default Spring Security authorization endpoint is /oauth2/authorization/{registrationId}
                        // We don't need to customize authorizationEndpoint() if we stick to defaults,
                        // but ensure the link in HTML matches /oauth2/authorization/spotify
                        .defaultSuccessUrl("/dj/dashboard", true)
                        .failureHandler((request, response, exception) -> {
                            log.error("=== OAUTH2 LOGIN ERROR ===");
                            log.error("Failure reason: {}", exception.getMessage(), exception);
                            response.sendRedirect("/?error");
                        })
                )
                .logout(logout -> logout
                        .logoutUrl("/dj/logout") // Standard logout URL
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }
}