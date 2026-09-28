package com.scan2play.service;

import com.scan2play.service.FallbackImportException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackPlaylistServiceTest {

    private static final String PARTY = "ABC12";

    @Mock
    private YouTubePlaylistClient playlistClient;
    @Mock
    private FallbackTrackCommandService trackCommandService;

    @InjectMocks
    private FallbackPlaylistService service;

    @Test
    @DisplayName("a playlist is fetched from YouTube and stored as the party's fallback tracks")
    void shouldImportPlaylist() {
        when(playlistClient.fetchPlayableVideoIds("PLx")).thenReturn(List.of("a", "b", "c"));
        when(trackCommandService.replaceTracks(PARTY, "PLx", List.of("a", "b", "c"))).thenReturn(3);

        assertThat(service.syncFallbackTracks(PARTY, "PLx")).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed import leaves the existing tracks untouched (database is never written)")
    void shouldNotTouchDatabase_whenImportFails() {
        when(playlistClient.fetchPlayableVideoIds("PLx"))
                .thenThrow(new FallbackImportException(Reason.API_ERROR, "quota"));

        assertThatThrownBy(() -> service.syncFallbackTracks(PARTY, "PLx"))
                .isInstanceOf(FallbackImportException.class);

        verifyNoInteractions(trackCommandService);
    }

    @Test
    @DisplayName("a playlist without playable videos is an error and does not wipe the current tracks")
    void shouldRejectEmptyResult_andKeepExistingTracks() {
        when(playlistClient.fetchPlayableVideoIds("PLx")).thenReturn(List.of());

        assertThatThrownBy(() -> service.syncFallbackTracks(PARTY, "PLx"))
                .isInstanceOf(FallbackImportException.class)
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.NO_PLAYABLE_TRACKS);

        verifyNoInteractions(trackCommandService);
    }

    @Test
    @DisplayName("a single video needs no YouTube API call (and therefore no API key)")
    void shouldStoreSingleVideoWithoutCallingTheApi() {
        when(trackCommandService.replaceTracks(PARTY, "V:dQw4w9WgXcQ", List.of("dQw4w9WgXcQ"))).thenReturn(1);

        assertThat(service.syncFallbackTracks(PARTY, "V:dQw4w9WgXcQ")).isEqualTo(1);

        verifyNoInteractions(playlistClient);
    }

    @Test
    @DisplayName("clearing the playlist (null or blank) cancels the queued tracks and calls no API")
    void shouldCancelQueuedTracks_whenPlaylistIsCleared() {
        assertThat(service.syncFallbackTracks(PARTY, null)).isZero();
        assertThat(service.syncFallbackTracks(PARTY, "  ")).isZero();

        verify(trackCommandService, org.mockito.Mockito.times(2)).cancelQueuedTracks(PARTY);
        verifyNoInteractions(playlistClient);
    }
}
