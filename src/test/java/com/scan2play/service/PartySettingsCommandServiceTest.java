package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PartySettingsCommandService} — party lifecycle management.
 */
@ExtendWith(MockitoExtension.class)
class PartySettingsCommandServiceTest {

    @Mock
    private PartySettingsRepository partySettingsRepository;

    @Spy
    private CacheManager cacheManager = new ConcurrentMapCacheManager("partySettings");

    @InjectMocks
    private PartySettingsCommandService service;

    // ---- getOrCreatePartyForDj ----

    @Test
    void getOrCreatePartyForDj_shouldReturnExistingParty_whenOwnerAlreadyHasOne() {
        PartySettingsEntity existing = PartySettingsEntity.builder()
                .id(1L)
                .ownerId("owner-123")
                .partyCode("ABC12")
                .activeProvider(MusicProviderType.REQUESTS_ONLY)
                .build();

        when(partySettingsRepository.findByOwnerId("owner-123")).thenReturn(Optional.of(existing));

        PartySettingsEntity result = service.getOrCreatePartyForDj("owner-123", MusicProviderType.YOUTUBE);

        assertThat(result.getPartyCode()).isEqualTo("ABC12");
        assertThat(result.getActiveProvider()).isEqualTo(MusicProviderType.REQUESTS_ONLY); // original preserved
        verify(partySettingsRepository, never()).save(any());
    }

    @Test
    void getOrCreatePartyForDj_shouldCreateNewParty_whenOwnerHasNone() {
        when(partySettingsRepository.findByOwnerId("new-owner")).thenReturn(Optional.empty());
        when(partySettingsRepository.save(any(PartySettingsEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PartySettingsEntity result = service.getOrCreatePartyForDj("new-owner", MusicProviderType.YOUTUBE);

        assertThat(result.getOwnerId()).isEqualTo("new-owner");
        assertThat(result.getActiveProvider()).isEqualTo(MusicProviderType.YOUTUBE);
        assertThat(result.getGlobalVibe()).isEqualTo(VibeType.ANY);
        assertThat(result.getPlaybackMode()).isEqualTo(PlaybackMode.MANUAL);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getPartyCode()).isNotBlank().hasSize(5);
        verify(partySettingsRepository).save(any());
    }

    // ---- updateSettings ----

    @Test
    void updateSettings_shouldApplyUpdaterAndSave() {
        PartySettingsEntity existing = PartySettingsEntity.builder()
                .partyCode("XYZ99")
                .ownerId("owner-1")
                .globalVibe(VibeType.ANY)
                .requestLimit(2)
                .build();

        when(partySettingsRepository.findByPartyCode("XYZ99")).thenReturn(Optional.of(existing));
        when(partySettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PartySettingsEntity result = service.updateSettings("XYZ99", s -> {
            s.setGlobalVibe(VibeType.ROCK_AND_METAL);
            s.setRequestLimit(5);
        });

        assertThat(result.getGlobalVibe()).isEqualTo(VibeType.ROCK_AND_METAL);
        assertThat(result.getRequestLimit()).isEqualTo(5);
        verify(partySettingsRepository).save(existing);
    }

    @Test
    void updateSettings_shouldDropTheCachedSettingsOfThatPartyOnly() {
        cacheManager.getCache("partySettings").put("XYZ99", PartySettingsEntity.builder().partyCode("XYZ99").build());
        cacheManager.getCache("partySettings").put("OTHER", PartySettingsEntity.builder().partyCode("OTHER").build());
        when(partySettingsRepository.findByPartyCode("XYZ99"))
                .thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("XYZ99").build()));
        when(partySettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateSettings("XYZ99", s -> s.setRequestLimit(5));   // no transaction here: evicted at once

        assertThat(cacheManager.getCache("partySettings").get("XYZ99")).isNull();
        assertThat(cacheManager.getCache("partySettings").get("OTHER")).isNotNull();
    }

    @Test
    void updateSettings_shouldThrow_whenPartyNotFound() {
        when(partySettingsRepository.findByPartyCode("NOPE1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSettings("NOPE1", s -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Party not found");
    }
}

