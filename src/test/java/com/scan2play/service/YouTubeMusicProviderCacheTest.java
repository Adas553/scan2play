package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.YoutubeCacheEntity;
import com.scan2play.repository.YoutubeCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code @Cacheable} of {@link YouTubeMusicProvider#findTrackUrl} through a real Spring cache proxy: a video is kept, a
 * search link is not — it is what a song gets while today's search budget is spent, and it must not stick for the cache's 24 h
 * once the API can be asked again.
 */
class YouTubeMusicProviderCacheTest {

    private static final YoutubeCacheRepository REPOSITORY = mock(YoutubeCacheRepository.class);

    @Configuration
    @EnableCaching(proxyTargetClass = true) // class proxies, as in Spring Boot
    static class CacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("youtubeSearch");
        }

        @Bean
        YouTubeMusicProvider provider() {
            // no API key: a song the database does not know gets the search link
            return new YouTubeMusicProvider(RestClient.create(), new ObjectMapper(), REPOSITORY, new YouTubeSearchBudget(80), "");
        }
    }

    private AnnotationConfigApplicationContext context;
    private YouTubeMusicProvider provider;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(REPOSITORY);
        context = new AnnotationConfigApplicationContext(CacheConfig.class);
        provider = context.getBean(YouTubeMusicProvider.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aSearchLink_isNotCached() {
        when(REPOSITORY.findBySearchQuery(anyString())).thenReturn(Optional.empty());

        provider.findTrackUrl("Some Song");
        String second = provider.findTrackUrl("Some Song");

        assertThat(second).startsWith("https://www.youtube.com/results?search_query=");
        verify(REPOSITORY, times(2)).findBySearchQuery("some song");
    }

    @Test
    void aVideo_isCached() {
        when(REPOSITORY.findBySearchQuery(anyString())).thenReturn(Optional.of(YoutubeCacheEntity.builder()
                .searchQuery("some song").videoId("dQw4w9WgXcQ").createdAt(Instant.now()).build()));

        provider.findTrackUrl("Some Song");
        String second = provider.findTrackUrl("Some Song");

        assertThat(second).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        verify(REPOSITORY, times(1)).findBySearchQuery("some song");
    }
}
