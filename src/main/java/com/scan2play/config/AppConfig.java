package com.scan2play.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
@EnableScheduling
@EnableAsync
public class AppConfig {

    /**
     * Shared RestClient with connect/read timeouts.
     * Prevents hung threads when external APIs (YouTube, Spotify) are slow or unreachable.
     */
    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Custom CacheManager with per-cache TTL configuration via Caffeine.
     * <ul>
     *     <li>{@code partySettings} / {@code qrCodes} — long-lived, evicted manually via @CachePut</li>
     *     <li>{@code youtubeSearch} — 24h TTL, avoids redundant YouTube Data API calls (100 quota/search)</li>
     *     <li>{@code dashboardQueue} — 3s TTL, auto-expires to keep polling data fresh (the guest page reads it too)</li>
     * </ul>
     */
    @Bean
        public CacheManager cacheManager() {
            SimpleCacheManager cacheManager = new SimpleCacheManager();
            cacheManager.setCaches(List.of(
                    buildCache("partySettings", Duration.ofHours(24), 500),
                    buildCache("qr-codes", Duration.ofHours(24), 1000),
                    buildCache("youtubeSearch", Duration.ofHours(24), 1000),
                    buildCache("dashboardQueue", Duration.ofSeconds(3), 200)
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
