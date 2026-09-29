package com.scan2play.service;

import com.scan2play.model.PlaylistTrack;
import com.scan2play.service.FallbackImportException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackPlaylistServiceTest {

    private static final String PARTY = "ABC12";
    private static final List<PlaylistTrack> TRACKS = List.of(
            new PlaylistTrack("a", "Song A"), new PlaylistTrack("b", "Song B"), new PlaylistTrack("c", null));

    @Mock
    private YouTubePlaylistClient playlistClient;
    @Mock
    private FallbackTrackCommandService trackCommandService;

    @InjectMocks
    private FallbackPlaylistService service;

    @Test
    @DisplayName("a playlist is fetched from YouTube and stored as the party's fallback tracks")
    void shouldImportPlaylist() {
        when(playlistClient.fetchPlayableTracks("PLx")).thenReturn(TRACKS);
        when(trackCommandService.replaceTracks(PARTY, "PLx", TRACKS, false)).thenReturn(3);

        assertThat(service.syncFallbackTracks(PARTY, "PLx", false)).isEqualTo(3);
    }

    @Test
    @DisplayName("the DJ's shuffle setting is passed on, so the imported tracks get the right order")
    void shouldPassShuffleSettingOn() {
        when(playlistClient.fetchPlayableTracks("PLx")).thenReturn(TRACKS);
        when(trackCommandService.replaceTracks(PARTY, "PLx", TRACKS, true)).thenReturn(3);

        assertThat(service.syncFallbackTracks(PARTY, "PLx", true)).isEqualTo(3);

        verify(trackCommandService).replaceTracks(PARTY, "PLx", TRACKS, true);
    }

    @Test
    @DisplayName("a failed import leaves the existing tracks untouched (database is never written)")
    void shouldNotTouchDatabase_whenImportFails() {
        when(playlistClient.fetchPlayableTracks("PLx"))
                .thenThrow(new FallbackImportException(Reason.API_ERROR, "quota"));

        assertThatThrownBy(() -> service.syncFallbackTracks(PARTY, "PLx", true))
                .isInstanceOf(FallbackImportException.class);

        verifyNoInteractions(trackCommandService);
    }

    @Test
    @DisplayName("a playlist without playable videos is an error and does not wipe the current tracks")
    void shouldRejectEmptyResult_andKeepExistingTracks() {
        when(playlistClient.fetchPlayableTracks("PLx")).thenReturn(List.of());

        assertThatThrownBy(() -> service.syncFallbackTracks(PARTY, "PLx", true))
                .isInstanceOf(FallbackImportException.class)
                .extracting(e -> ((FallbackImportException) e).getReason()).isEqualTo(Reason.NO_PLAYABLE_TRACKS);

        verifyNoInteractions(trackCommandService);
    }

    @Test
    @DisplayName("a single video is stored without listing a playlist; its title is looked up best-effort")
    void shouldStoreSingleVideo_withTitleWhenAvailable() {
        when(playlistClient.findTitle("dQw4w9WgXcQ")).thenReturn(Optional.of("Never Gonna Give You Up"));
        List<PlaylistTrack> single = List.of(new PlaylistTrack("dQw4w9WgXcQ", "Never Gonna Give You Up"));
        when(trackCommandService.replaceTracks(PARTY, "V:dQw4w9WgXcQ", single, true)).thenReturn(1);

        assertThat(service.syncFallbackTracks(PARTY, "V:dQw4w9WgXcQ", true)).isEqualTo(1);

        verify(playlistClient).findTitle("dQw4w9WgXcQ");
        verifyNoMoreInteractions(playlistClient); // no playlist call, so no API key is needed to play it
    }

    @Test
    @DisplayName("a single video whose title cannot be looked up (no API key, API down) is still stored — untitled")
    void shouldStoreSingleVideo_withoutTitle_whenLookupFails() {
        when(playlistClient.findTitle("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        List<PlaylistTrack> single = List.of(new PlaylistTrack("dQw4w9WgXcQ", null));
        when(trackCommandService.replaceTracks(PARTY, "V:dQw4w9WgXcQ", single, false)).thenReturn(1);

        assertThat(service.syncFallbackTracks(PARTY, "V:dQw4w9WgXcQ", false)).isEqualTo(1);
    }

    @Test
    @DisplayName("clearing the playlist (null or blank) cancels the queued tracks and calls no API")
    void shouldCancelQueuedTracks_whenPlaylistIsCleared() {
        assertThat(service.syncFallbackTracks(PARTY, null, true)).isZero();
        assertThat(service.syncFallbackTracks(PARTY, "  ", true)).isZero();

        verify(trackCommandService, org.mockito.Mockito.times(2)).cancelQueuedTracks(PARTY);
        verifyNoInteractions(playlistClient);
    }

    @Test
    @DisplayName("switching shuffle only re-orders the queue in the database — no YouTube call")
    void shouldApplyShuffleSettingWithoutCallingTheApi() {
        service.applyShuffleSetting(PARTY, "PLx", true);

        verify(trackCommandService).applyShuffleSetting(PARTY, "PLx", true);
        verifyNoInteractions(playlistClient);
    }
}
