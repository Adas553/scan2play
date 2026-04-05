package com.scan2play.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Diagnostic configuration that logs registered OAuth2 client registrations at startup.
 * <b>Active only with the "dev" profile</b> to avoid leaking client IDs in production logs.
 */
@Configuration
@Profile("dev")
@Slf4j
public class OAuth2DebugConfig {

    @Bean
    public CommandLineRunner logOAuth2Clients(ClientRegistrationRepository repository) {
        return args -> {
            log.info("Checking registered OAuth2 clients...");
            // ClientRegistrationRepository usually is an Iterable in standard Boot implementations (InMemoryClientRegistrationRepository)
            if (repository instanceof Iterable) {
                for (Object registration : (Iterable<?>) repository) {
                    if (registration instanceof ClientRegistration client) {
                        log.info("Found OAuth2 Client: ID={}, Name={}, ClientID={}",
                                client.getRegistrationId(), client.getClientName(), client.getClientId());
                    }
                }
            } else {
                // Try to manually fetch 'spotify' to see if it exists
                try {
                    ClientRegistration spotify = repository.findByRegistrationId("spotify");
                    if (spotify != null) {
                        log.info("Found OAuth2 Client: spotify (ClientID={})", spotify.getClientId());
                    } else {
                        log.error("OAuth2 Client 'spotify' NOT FOUND in repository!");
                    }
                } catch (Exception e) {
                    log.error("Error retrieving 'spotify' client: {}", e.getMessage());
                }
            }
        };
    }
}