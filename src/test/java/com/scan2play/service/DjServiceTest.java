package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.scan2play.service.DjService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DjService} — the core queue management and song action logic.
 */
@ExtendWith(MockitoExtension.class)
class DjServiceTest {

    @Mock
    private SongRequestRepository songRequestRepository;
    @Mock
    private PartySettingsQueryService partySettingsQueryService;
    @Mock
    private QueueService queueService;
    @Mock
    private SongEvaluationService songEvaluationService;

    @InjectMocks
    private DjService djService;

    private static final String PARTY_CODE = "XY789";

    // ---- getQueueFingerprint ----

    @Test
    void getQueueFingerprint_shouldReturnFingerprintFromRepository() {
        when(songRequestRepository.computeFingerprint(PARTY_CODE, List.of(DECISION_ACCEPTED)))
                .thenReturn("12-487");

        String result = djService.getQueueFingerprint(PARTY_CODE);

        assertThat(result).isEqualTo("12-487");
    }

    @Test
    void getQueueFingerprint_shouldReturnDefault_whenRepositoryReturnsNull() {
        when(songRequestRepository.computeFingerprint(PARTY_CODE, List.of(DECISION_ACCEPTED)))
                .thenReturn(null);

        String result = djService.getQueueFingerprint(PARTY_CODE);

        assertThat(result).isEqualTo("0-0");
    }

    // ---- markSongAsPlayed ----

    @Test
    void markSongAsPlayed_shouldUpdateDecisionToPlayed() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode(PARTY_CODE)
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));

        djService.markSongAsPlayed(1L, PARTY_CODE);

        assertThat(song.getDecision()).isEqualTo(DECISION_PLAYED);
        verify(songRequestRepository).save(song);
    }

    @Test
    void markSongAsPlayed_shouldDoNothing_whenSongNotFound() {
        when(songRequestRepository.findById(999L)).thenReturn(Optional.empty());

        djService.markSongAsPlayed(999L, PARTY_CODE);

        verify(songRequestRepository, never()).save(any());
    }

    @Test
    void markSongAsPlayed_shouldBlock_whenPartyCodeDoesNotMatch() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode("OTHER")
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));

        djService.markSongAsPlayed(1L, PARTY_CODE);

        assertThat(song.getDecision()).isEqualTo(DECISION_ACCEPTED); // unchanged
        verify(songRequestRepository, never()).save(any());
    }

    // ---- pushToSpotify ----

    @Test
    void pushToSpotify_shouldPushAndMarkAsPlayed_whenSpotifyProviderAndTrackUrlPresent() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode(PARTY_CODE)
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .trackUrl("spotify:track:abc123")
                .build();

        PartySettingsEntity settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.SPOTIFY)
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));
        when(partySettingsQueryService.getSettings(PARTY_CODE)).thenReturn(settings);

        djService.pushToSpotify(1L, PARTY_CODE);

        verify(queueService).addToQueue(PARTY_CODE, "spotify:track:abc123", MusicProviderType.SPOTIFY);
        assertThat(song.getDecision()).isEqualTo(DECISION_PLAYED);
        verify(songRequestRepository).save(song);
    }

    @Test
    void pushToSpotify_shouldNotPush_whenProviderIsYouTube() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode(PARTY_CODE)
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .trackUrl("https://youtube.com/watch?v=abc")
                .build();

        PartySettingsEntity settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.YOUTUBE)
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));
        when(partySettingsQueryService.getSettings(PARTY_CODE)).thenReturn(settings);

        djService.pushToSpotify(1L, PARTY_CODE);

        verify(queueService, never()).addToQueue(any(), any(), any());
        assertThat(song.getDecision()).isEqualTo(DECISION_ACCEPTED); // unchanged
    }

    @Test
    void pushToSpotify_shouldNotPush_whenTrackUrlIsNull() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode(PARTY_CODE)
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .trackUrl(null)
                .build();

        PartySettingsEntity settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.SPOTIFY)
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));
        when(partySettingsQueryService.getSettings(PARTY_CODE)).thenReturn(settings);

        djService.pushToSpotify(1L, PARTY_CODE);

        verify(queueService, never()).addToQueue(any(), any(), any());
    }

    @Test
    void pushToSpotify_shouldBlock_whenPartyCodeDoesNotMatch() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L)
                .partyCode("OTHER")
                .songName("Test Song")
                .decision(DECISION_ACCEPTED)
                .trackUrl("spotify:track:abc123")
                .build();

        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));

        djService.pushToSpotify(1L, PARTY_CODE);

        verify(queueService, never()).addToQueue(any(), any(), any());
        verify(songRequestRepository, never()).save(any());
    }

    // ---- addDjPick ----

    @Test
    void addDjPick_shouldNormalizeNameAndSaveWithResolvedTrack() {
        PartySettingsEntity settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.YOUTUBE)
                .build();

        when(songEvaluationService.normalizeSongName("nirvanna smells"))
                .thenReturn("Nirvana - Smells Like Teen Spirit");
        when(partySettingsQueryService.getSettings(PARTY_CODE)).thenReturn(settings);
        when(queueService.resolveTrack("Nirvana - Smells Like Teen Spirit", MusicProviderType.YOUTUBE))
                .thenReturn("https://www.youtube.com/watch?v=hTWKbfoikeg");

        djService.addDjPick(PARTY_CODE, "nirvanna smells");

        ArgumentCaptor<SongRequestEntity> captor = ArgumentCaptor.forClass(SongRequestEntity.class);
        verify(songRequestRepository).save(captor.capture());

        SongRequestEntity saved = captor.getValue();
        assertThat(saved.getPartyCode()).isEqualTo(PARTY_CODE);
        assertThat(saved.getSongName()).isEqualTo("Nirvana - Smells Like Teen Spirit");
        assertThat(saved.getDecision()).isEqualTo(DECISION_ACCEPTED);
        assertThat(saved.getStyle()).isEqualTo("DJ Pick");
        assertThat(saved.getDjComment()).contains("DJ");
        assertThat(saved.getEnergyLevel()).isZero();
        assertThat(saved.getTrackUrl()).isEqualTo("https://www.youtube.com/watch?v=hTWKbfoikeg");
        assertThat(saved.getRequestedAt()).isNotNull();
    }

    @Test
    void addDjPick_shouldStillSave_whenTrackResolutionFails() {
        PartySettingsEntity settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.YOUTUBE)
                .build();

        when(songEvaluationService.normalizeSongName("Some Song")).thenReturn("Some Song");
        when(partySettingsQueryService.getSettings(PARTY_CODE)).thenReturn(settings);
        when(queueService.resolveTrack(any(), any())).thenThrow(new RuntimeException("API down"));

        djService.addDjPick(PARTY_CODE, "Some Song");

        ArgumentCaptor<SongRequestEntity> captor = ArgumentCaptor.forClass(SongRequestEntity.class);
        verify(songRequestRepository).save(captor.capture());

        SongRequestEntity saved = captor.getValue();
        assertThat(saved.getTrackUrl()).isNull();
        assertThat(saved.getDecision()).isEqualTo(DECISION_ACCEPTED);
    }
}

