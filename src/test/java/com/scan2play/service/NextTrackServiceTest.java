package com.scan2play.service;

import com.scan2play.entity.FallbackPlayEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.model.NextTrackResponse;
import com.scan2play.model.NextTrackResponse.Source;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.service.FallbackImportException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextTrackServiceTest {

    private static final String PARTY = "ABC12";
    private static final String PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";
    private static final String PLAYLIST_URL = "https://www.youtube.com/playlist?list=" + PLAYLIST;

    @Mock
    private DjService djService;
    @Mock
    private PartySettingsQueryService partySettingsQueryService;
    @Mock
    private FallbackPlaylistService fallbackPlaylistService;
    @Mock
    private FallbackTrackCommandService fallbackTrackCommandService;
    @Mock
    private FallbackTrackRepository fallbackTrackRepository;

    @InjectMocks
    private NextTrackService service;

    /** What the queue hands out: a row of the play log — its id is the one the client is told (B:<id>). */
    private FallbackPlayEntity play;

    @BeforeEach
    void setUp() {
        play = FallbackPlayEntity.builder().id(7L).videoId("dQw4w9WgXcQ").build();
    }

    private void givenFallbackPlaylist(String url, boolean shuffle) {
        when(partySettingsQueryService.getSettings(PARTY)).thenReturn(
                PartySettingsEntity.builder().partyCode(PARTY).fallbackPlaylistUrl(url).fallbackShuffle(shuffle).build());
    }

    private void givenNoGuestWaiting() {
        when(djService.findNextPlayableGuestTrack(anyString(), any())).thenReturn(Optional.empty());
    }

    private void givenFreshPlaylist() {
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.of(LocalDateTime.now().minusDays(1)));
    }

    // ---- guest first ----

    @Test
    @DisplayName("a waiting guest song always wins — the fallback playlist is not even consulted")
    void shouldPreferGuestSong() {
        when(djService.findNextPlayableGuestTrack(PARTY, Set.of(3L)))
                .thenReturn(Optional.of(new NextGuestTrackResponse(42L, "hTWKbfoikeg")));

        Optional<NextTrackResponse> next = service.findNextTrack(PARTY, Set.of(3L));

        assertThat(next).contains(new NextTrackResponse(Source.GUEST, 42L, "hTWKbfoikeg"));
        verifyNoInteractions(partySettingsQueryService, fallbackTrackCommandService, fallbackPlaylistService);
    }

    // ---- background ----

    @Test
    @DisplayName("without a guest song the next background track is served, honouring the shuffle setting")
    void shouldServeBackgroundTrack() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        givenFreshPlaylist();
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true)).thenReturn(Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of()))
                .contains(new NextTrackResponse(Source.BACKGROUND, 7L, "dQw4w9WgXcQ", PLAYLIST));
        verify(fallbackPlaylistService, never()).syncFallbackTracks(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("shuffle off is passed through (tracks play in playlist order)")
    void shouldPassShuffleFlag() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, false);
        givenFreshPlaylist();
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, false)).thenReturn(Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).isPresent();
    }

    @Test
    @DisplayName("a party without a fallback playlist gets nothing — and nothing is imported or queried")
    void shouldReturnEmpty_whenNoFallbackPlaylist() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(null, true);

        assertThat(service.findNextTrack(PARTY, Set.of())).isEmpty();
        verifyNoInteractions(fallbackTrackCommandService, fallbackPlaylistService, fallbackTrackRepository);
    }

    @Test
    @DisplayName("a single-video fallback is looked up as V:<id>")
    void shouldUseSingleVideoId() {
        givenNoGuestWaiting();
        givenFallbackPlaylist("https://youtu.be/dQw4w9WgXcQ", true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, "V:dQw4w9WgXcQ")).thenReturn(Optional.of(LocalDateTime.now()));
        when(fallbackTrackCommandService.takeNextTrack(PARTY, "V:dQw4w9WgXcQ", true)).thenReturn(Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).map(NextTrackResponse::playlistId).contains("V:dQw4w9WgXcQ");
    }

    // ---- lazy import ----

    @Test
    @DisplayName("nothing to play (e.g. playlist set before server-side import existed) → import on demand, then serve")
    void shouldImportOnDemand_whenThereIsNothingToPlay() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.empty());
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true))
                .thenReturn(Optional.empty(), Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).contains(new NextTrackResponse(Source.BACKGROUND, 7L, "dQw4w9WgXcQ", PLAYLIST));

        verify(fallbackPlaylistService).syncFallbackTracks(PARTY, PLAYLIST, true);
    }

    @Test
    @DisplayName("an on-demand import uses the DJ's shuffle setting for the order of the imported tracks")
    void shouldImportWithTheShuffleSetting() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, false);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.empty());
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, false))
                .thenReturn(Optional.empty(), Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).isPresent();

        verify(fallbackPlaylistService).syncFallbackTracks(PARTY, PLAYLIST, false);
    }

    @Test
    @DisplayName("a failed on-demand import is not retried for a few minutes (no API hammering)")
    void shouldNotRetryFailedImportImmediately() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.empty());
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true)).thenReturn(Optional.empty());
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST, true))
                .thenThrow(new FallbackImportException(Reason.NO_API_KEY, "no key"));

        assertThat(service.findNextTrack(PARTY, Set.of())).isEmpty();
        assertThat(service.findNextTrack(PARTY, Set.of())).isEmpty();
        assertThat(service.findNextTrack(PARTY, Set.of())).isEmpty();

        verify(fallbackPlaylistService, times(1)).syncFallbackTracks(PARTY, PLAYLIST, true);
    }

    @Test
    @DisplayName("a failure for one playlist does not block imports for another party")
    void shouldTrackImportFailuresPerPartyAndPlaylist() {
        givenNoGuestWaiting();
        when(partySettingsQueryService.getSettings(anyString())).thenAnswer(inv -> PartySettingsEntity.builder()
                .partyCode(inv.getArgument(0)).fallbackPlaylistUrl(PLAYLIST_URL).build());
        when(fallbackTrackRepository.findLatestFetchedAt(anyString(), anyString())).thenReturn(Optional.empty());
        when(fallbackTrackCommandService.takeNextTrack(anyString(), anyString(), anyBoolean())).thenReturn(Optional.empty());
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST, true))
                .thenThrow(new FallbackImportException(Reason.API_ERROR, "quota"));

        service.findNextTrack(PARTY, Set.of());
        service.findNextTrack("OTHER", Set.of());

        verify(fallbackPlaylistService).syncFallbackTracks(PARTY, PLAYLIST, true);
        verify(fallbackPlaylistService).syncFallbackTracks("OTHER", PLAYLIST, true);
    }

    @Test
    @DisplayName("tracks close to the 30-day retention limit are refreshed before serving")
    void shouldRefreshStalePlaylist() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(NextTrackService.REFRESH_AFTER_DAYS).minusHours(1)));
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true)).thenReturn(Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).isPresent();

        InOrder order = inOrder(fallbackPlaylistService, fallbackTrackCommandService);
        order.verify(fallbackPlaylistService).syncFallbackTracks(PARTY, PLAYLIST, true);
        order.verify(fallbackTrackCommandService).takeNextTrack(PARTY, PLAYLIST, true);
    }

    @Test
    @DisplayName("a playlist fetched 28 days ago is still fresh")
    void shouldNotRefreshFreshPlaylist() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.of(LocalDateTime.now().minusDays(28)));
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true)).thenReturn(Optional.of(play));

        service.findNextTrack(PARTY, Set.of());

        verify(fallbackPlaylistService, never()).syncFallbackTracks(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("if refreshing a stale playlist fails, the existing tracks keep playing")
    void shouldKeepServingOldTracks_whenRefreshFails() {
        givenNoGuestWaiting();
        givenFallbackPlaylist(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findLatestFetchedAt(PARTY, PLAYLIST)).thenReturn(Optional.of(LocalDateTime.now().minusDays(29).minusHours(1)));
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST, true))
                .thenThrow(new FallbackImportException(Reason.API_ERROR, "quota"));
        when(fallbackTrackCommandService.takeNextTrack(PARTY, PLAYLIST, true)).thenReturn(Optional.of(play));

        assertThat(service.findNextTrack(PARTY, Set.of())).contains(new NextTrackResponse(Source.BACKGROUND, 7L, "dQw4w9WgXcQ", PLAYLIST));
    }
}
