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
                .partyCode("ABC12").ownerId("owner").requestLimit(3).fallbackPlaylistUrl("https://x").build()));

        PartySettingsEntity first = service.getSettings("ABC12");
        PartySettingsEntity second = service.getSettings("ABC12");

        verify(repository, times(1)).findByPartyCode("ABC12");
        assertThat(first).isNotSameAs(second).isEqualTo(second);
        assertThat(first.getId()).isEqualTo(7L);
        assertThat(first.getFallbackPlaylistUrl()).isEqualTo("https://x");
    }

    @Test
    void aChangeToTheCopyIsNotSeenByTheNextCaller() {
        when(repository.findByPartyCode("ABC12")).thenReturn(Optional.of(PartySettingsEntity.builder()
                .partyCode("ABC12").ownerId("owner").requestLimit(3).build()));

        service.getSettings("ABC12").setRequestLimit(99);

        assertThat(service.getSettings("ABC12").getRequestLimit()).isEqualTo(3);
    }

    @Test
    void anUnknownPartyIsAnErrorAndIsNotCached() {
        when(repository.findByPartyCode("NOPE1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettings("NOPE1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getSettings("NOPE1")).isInstanceOf(IllegalArgumentException.class);
        verify(repository, times(2)).findByPartyCode("NOPE1");
    }
}
