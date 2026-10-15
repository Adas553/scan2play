package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review item 1.2: the cached settings are one shared entity, so every caller must get its own copy. */
class PartySettingsQueryServiceTest {

    private final PartySettingsRepository repository = mock(PartySettingsRepository.class);
    private final PartySettingsQueryService service =
            new PartySettingsQueryService(repository, new ConcurrentMapCacheManager(PartySettingsQueryService.CACHE));

    @Test
    void readsTheDatabaseOnceAndHandsOutCopies() {
        when(repository.findByPartyCode("ABC12")).thenReturn(Optional.of(PartySettingsEntity.builder().id(7L)
                .partyCode("ABC12").ownerId("owner").requestLimit(3).vibeNote("bez rapu").build()));

        PartySettingsEntity first = service.getSettings("ABC12");
        PartySettingsEntity second = service.getSettings("ABC12");

        verify(repository, times(1)).findByPartyCode("ABC12");
        assertThat(first).isNotSameAs(second).usingRecursiveComparison().isEqualTo(second);
        assertThat(first.getId()).isEqualTo(7L);
        assertThat(first.getVibeNote()).isEqualTo("bez rapu");
    }

    @Test
    void aChangeToTheCopyIsNotSeenByTheNextCaller() {
        when(repository.findByPartyCode("ABC12")).thenReturn(Optional.of(PartySettingsEntity.builder()
                .partyCode("ABC12").ownerId("owner").requestLimit(3).build()));

        service.getSettings("ABC12").setRequestLimit(99);

        assertThat(service.getSettings("ABC12").getRequestLimit()).isEqualTo(3);
    }

    /**
     * A read that took the old row from the database just before a change committed, and a change's eviction that came while that
     * read was still on its way: the old copy must not land in the cache after the eviction — it would stay there 24 hours (review
     * 2026-10-14). The production cache (Caffeine, as AppConfig builds it); the read is held in the repository until the eviction
     * has been asked for.
     */
    @Test
    void aReadOnItsWayDuringAChange_doesNotLeaveTheOldCopyInTheCache() throws Exception {
        org.springframework.cache.support.SimpleCacheManager caches = new org.springframework.cache.support.SimpleCacheManager();
        caches.setCaches(java.util.List.of(new org.springframework.cache.caffeine.CaffeineCache(PartySettingsQueryService.CACHE,
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofHours(24))
                        .maximumSize(500).build())));
        caches.initializeCaches();
        PartySettingsQueryService cached = new PartySettingsQueryService(repository, caches);
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> evicting = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean evicted = new java.util.concurrent.atomic.AtomicBoolean();
        PartySettingsEntity old = PartySettingsEntity.builder().partyCode("ABC12").ownerId("owner").vibeNote("stara").build();
        PartySettingsEntity changed = PartySettingsEntity.builder().partyCode("ABC12").ownerId("owner").vibeNote("nowa").build();
        when(repository.findByPartyCode("ABC12")).thenAnswer(call -> {
            reading.countDown();
            // the eviction has run, or waits for this read to end (a thread blocked on the cache's entry)
            long until = System.nanoTime() + 5_000_000_000L;
            while (!evicted.get() && !(evicting.get() != null && evicting.get().getState() == Thread.State.BLOCKED)
                    && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return Optional.of(old);
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var read = pool.submit(() -> cached.getSettings("ABC12"));
            reading.await(5, java.util.concurrent.TimeUnit.SECONDS);
            var evict = pool.submit(() -> {
                evicting.set(Thread.currentThread());
                caches.getCache(PartySettingsQueryService.CACHE).evict("ABC12");   // what updateSettings does after its commit
                evicted.set(true);
            });
            read.get(5, java.util.concurrent.TimeUnit.SECONDS);
            evict.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        when(repository.findByPartyCode("ABC12")).thenReturn(Optional.of(changed));

        assertThat(cached.getSettings("ABC12").getVibeNote()).isEqualTo("nowa");
    }

    @Test
    void anUnknownPartyIsAnErrorAndIsNotCached() {
        when(repository.findByPartyCode("NOPE1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettings("NOPE1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getSettings("NOPE1")).isInstanceOf(IllegalArgumentException.class);
        verify(repository, times(2)).findByPartyCode("NOPE1");
    }
}
