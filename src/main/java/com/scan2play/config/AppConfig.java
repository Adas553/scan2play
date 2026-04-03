package com.scan2play.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    /**
     * Custom CacheManager with per-cache TTL configuration via Caffeine.
     * <ul>
     *     <li>{@code partySettings} / {@code qrCodes} — long-lived, evicted manually via @CachePut</li>
     *     <li>{@code dashboardQueue} — 3s TTL / {@code publicQueue} — 5s TTL, auto-expires to keep polling data fresh</li>
     * </ul>
     */
    @Bean
        public CacheManager cacheManager() {
            SimpleCacheManager cacheManager = new SimpleCacheManager();
            cacheManager.setCaches(List.of(
                    buildCache("partySettings", Duration.ofHours(24), 500),
                    buildCache("qr-codes", Duration.ofHours(24), 1000),
                    buildCache("dashboardQueue", Duration.ofSeconds(3), 200),
                    buildCache("publicQueue", Duration.ofSeconds(5), 200)
            ));
            return cacheManager;
        }

    private CaffeineCache buildCache(String name, Duration ttl, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build());
    }
}
