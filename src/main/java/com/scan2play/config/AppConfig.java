package com.scan2play.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    public RestClient restClient() {
        // Use the static factory method which doesn't require a builder to be injected.
        return RestClient.create();
    }
}
