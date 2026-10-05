package com.scan2play.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.QrCodeService;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
@EnableScheduling
public class AppConfig {

    /**
     * Custom CacheManager with per-cache TTL configuration via Caffeine.
     * <ul>
     *     <li>{@code partySettings} — long-lived; read through {@code PartySettingsQueryService} (a copy per caller),
     *         evicted after every committed change ({@code PartySettingsCommandService.updateSettings})</li>
     *     <li>{@code qr-codes} — long-lived</li>
     *     <li>{@code dashboardQueue} — 3s TTL, auto-expires to keep polling data fresh (the guest page reads it too)</li>
     * </ul>
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                buildCache(PartySettingsQueryService.CACHE, Duration.ofHours(24), 500),
                buildCache(QrCodeService.QR_CODE_CACHE, Duration.ofHours(24), 1000),
                buildCache(DjService.QUEUE_CACHE, Duration.ofSeconds(3), 200)
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
