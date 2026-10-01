package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.model.MoveDirection;
import com.scan2play.repository.FallbackTrackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackQueueServiceTest {

    private static final String PARTY = "ABC12";
    private static final String PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";
    private static final String PLAYLIST_URL = "https://www.youtube.com/playlist?list=" + PLAYLIST;
    /** The same order the player is served in: lowest play order first, ties by playlist position. */
    private static final PageRequest UPCOMING = PageRequest.of(0, FallbackQueueService.UPCOMING_LIMIT,
            Sort.by("playOrder", "playlistPosition"));

    @Mock
    private PartySettingsQueryService partySettingsQueryService;
    @Mock
    private FallbackTrackRepository fallbackTrackRepository;
    @Mock
    private FallbackTrackCommandService fallbackTrackCommandService;

    @InjectMocks
    private FallbackQueueService service;

    private void givenSettings(String playlistUrl, boolean shuffle) {
        when(partySettingsQueryService.getSettings(PARTY)).thenReturn(
                PartySettingsEntity.builder().partyCode(PARTY).fallbackPlaylistUrl(playlistUrl).fallbackShuffle(shuffle).build());
    }

    private static FallbackTrackEntity entity(long id, String videoId, String title) {
        return FallbackTrackEntity.builder().id(id).videoId(videoId).title(title).status(QUEUED).build();
    }

    @Test
    @DisplayName("the upcoming tracks come in play order, with the count of everything still queued in this round")
    void shouldListUpcomingTracksInPlayOrder() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING))
                .thenReturn(List.of(entity(5, "aaaaaaaaaaa", "Song A"), entity(9, "bbbbbbbbbbb", null)));
        when(fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(37L);

        FallbackQueueView view = service.getUpcoming(PARTY);

        assertThat(view.hasPlaylist()).isTrue();
        assertThat(view.shuffle()).isTrue();
        assertThat(view.remaining()).isEqualTo(37);
        assertThat(view.tracks()).containsExactly(
                new FallbackQueueView.Track(5L, "aaaaaaaaaaa", "Song A"),
                new FallbackQueueView.Track(9L, "bbbbbbbbbbb", null));
    }

    // ---- version: lets a window that does not play notice that the list changed elsewhere ----

    // What changes the fingerprint of the queue (a hand-out, a move, a skip...) is SQL: FallbackQueueIT checks it on PostgreSQL.

    private void givenQueue(boolean shuffle, String fingerprint) {
        givenSettings(PLAYLIST_URL, shuffle);
        when(fallbackTrackRepository.queueFingerprint(PARTY, PLAYLIST, "QUEUED", "SKIPPED")).thenReturn(fingerprint);
    }

    @Test
    @DisplayName("version: the same queue gives the same version, however often it is asked for, and no track is read")
    void shouldGiveTheSameVersion_whenNothingChanged() {
        givenQueue(true, "f1");

        assertThat(service.getVersion(PARTY)).isEqualTo(service.getVersion(PARTY)).isNotBlank();
        verify(fallbackTrackRepository, never()).findByPartyCodeAndPlaylistIdAndStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("version: it changes when the database's fingerprint of the queue does")
    void shouldChangeTheVersion_whenTheQueueChanges() {
        givenQueue(false, "f1");
        String before = service.getVersion(PARTY);

        givenQueue(false, "f2");

        assertThat(service.getVersion(PARTY)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("version: it changes with the shuffle switch even when the order it produces happens to be the same")
    void shouldChangeTheVersion_whenShuffleIsSwitched() {
        givenQueue(false, "f1");
        String before = service.getVersion(PARTY);

        givenQueue(true, "f1");

        assertThat(service.getVersion(PARTY)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("version: it changes when the DJ clears the playlist, and asks the database nothing then")
    void shouldChangeTheVersion_whenThePlaylistIsCleared() {
        givenQueue(false, "f1");
        String before = service.getVersion(PARTY);

        givenSettings(null, false);

        assertThat(service.getVersion(PARTY)).isNotEqualTo(before);
        verify(fallbackTrackRepository).queueFingerprint(any(), any(), any(), any());   // only the first time
    }

    @Test
    @DisplayName("the list covers a whole round — as many tracks as a playlist can be imported with, so it is never cut")
    void shouldListAWholeRound() {
        assertThat(FallbackQueueService.UPCOMING_LIMIT).isEqualTo(YouTubePlaylistClient.MAX_TRACKS).isEqualTo(500);
    }

    @Test
    @DisplayName("the shuffle flag of the party is reported as it is")
    void shouldReportShuffleOff() {
        givenSettings(PLAYLIST_URL, false);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING)).thenReturn(List.of());

        assertThat(service.getUpcoming(PARTY).shuffle()).isFalse();
    }

    @Test
    @DisplayName("a playlist without any queued tracks (import failed, or still running) gives an empty list")
    void shouldReturnEmptyList_whenNothingIsQueued() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING)).thenReturn(List.of());

        FallbackQueueView view = service.getUpcoming(PARTY);

        assertThat(view.hasPlaylist()).isTrue();
        assertThat(view.tracks()).isEmpty();
        assertThat(view.remaining()).isZero();
    }

    @Test
    @DisplayName("without a fallback playlist there is nothing to show — and the tracks table is not queried")
    void shouldReturnNoPlaylist_whenTheDjHasNone() {
        givenSettings(null, true);

        FallbackQueueView view = service.getUpcoming(PARTY);

        assertThat(view).isEqualTo(FallbackQueueView.noPlaylist(true));
        assertThat(view.hasPlaylist()).isFalse();
        verifyNoInteractions(fallbackTrackRepository);
    }

    @Test
    @DisplayName("a single-video fallback is looked up as V:<id>, like everywhere else")
    void shouldUseSingleVideoId() {
        givenSettings("https://youtu.be/dQw4w9WgXcQ", true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, "V:dQw4w9WgXcQ", QUEUED, UPCOMING))
                .thenReturn(List.of(entity(1, "dQw4w9WgXcQ", "Never Gonna Give You Up")));
        when(fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, "V:dQw4w9WgXcQ", QUEUED)).thenReturn(1L);

        assertThat(service.getUpcoming(PARTY).tracks()).hasSize(1);

        verify(fallbackTrackRepository).countByPartyCodeAndPlaylistIdAndStatus(PARTY, "V:dQw4w9WgXcQ", QUEUED);
    }

    // ---- manual order flag ----

    @Test
    @DisplayName("the list says whether the DJ has moved tracks by hand (the shuffle switch asks before undoing that)")
    void shouldReportManualOrder() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING)).thenReturn(List.of());
        when(fallbackTrackRepository.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(PARTY, PLAYLIST, QUEUED)).thenReturn(true);

        assertThat(service.getUpcoming(PARTY).manualOrder()).isTrue();
    }

    @Test
    @DisplayName("without manual moves the order is not reported as changed by hand")
    void shouldNotReportManualOrder_byDefault() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING)).thenReturn(List.of());

        assertThat(service.getUpcoming(PARTY).manualOrder()).isFalse();
    }

    // ---- moveTrack ----

    @Test
    @DisplayName("a move is done in the party's current playlist - resolved from the setting, never from the request")
    void moveTrack_shouldMoveInTheCurrentPlaylist() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackCommandService.moveTrack(PARTY, PLAYLIST, 7L, MoveDirection.UP)).thenReturn(true);

        assertThat(service.moveTrack(PARTY, 7L, MoveDirection.UP)).isTrue();

        verify(fallbackTrackCommandService).moveTrack(PARTY, PLAYLIST, 7L, MoveDirection.UP);
    }

    @Test
    @DisplayName("a refused move is reported as such")
    void moveTrack_shouldReportARefusedMove() {
        givenSettings("https://youtu.be/dQw4w9WgXcQ", true);
        when(fallbackTrackCommandService.moveTrack(PARTY, "V:dQw4w9WgXcQ", 7L, MoveDirection.TOP)).thenReturn(false);

        assertThat(service.moveTrack(PARTY, 7L, MoveDirection.TOP)).isFalse();
    }

    @Test
    @DisplayName("without a fallback playlist there is nothing to move")
    void moveTrack_shouldRefuse_whenThereIsNoPlaylist() {
        givenSettings(null, true);

        assertThat(service.moveTrack(PARTY, 7L, MoveDirection.DOWN)).isFalse();

        verifyNoInteractions(fallbackTrackCommandService);
    }

    // ---- placeTrack ----

    @Test
    @DisplayName("a drop is done in the party's current playlist - resolved from the setting, never from the request")
    void placeTrack_shouldPlaceInTheCurrentPlaylist() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackCommandService.placeTrack(PARTY, PLAYLIST, 7L, 9L)).thenReturn(true);
        when(fallbackTrackCommandService.placeTrack(PARTY, PLAYLIST, 7L, null)).thenReturn(false);

        assertThat(service.placeTrack(PARTY, 7L, 9L)).isTrue();
        assertThat(service.placeTrack(PARTY, 7L, null)).isFalse();
    }

    @Test
    @DisplayName("without a fallback playlist there is nothing to drag")
    void placeTrack_shouldRefuse_whenThereIsNoPlaylist() {
        givenSettings(null, true);

        assertThat(service.placeTrack(PARTY, 7L, 9L)).isFalse();

        verifyNoInteractions(fallbackTrackCommandService);
    }

    // ---- skipping a track (for this round) ----

    @Test
    @DisplayName("the list says how many tracks the DJ has skipped in this round")
    void shouldReportTheSkippedTracks() {
        givenSettings(PLAYLIST_URL, false);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING))
                .thenReturn(List.of(entity(1, "aaaaaaaaaaa", "A")));
        when(fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(3L);
        assertThat(service.getUpcoming(PARTY).skipped()).isZero();

        when(fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, FallbackTrackStatus.SKIPPED)).thenReturn(2L);

        assertThat(service.getUpcoming(PARTY).skipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("skipping a track hands the current playlist and the shuffle setting to the command service, and says what it answered")
    void skipTrack_shouldDelegateWithThePlaylistAndTheShuffleSetting() {
        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackCommandService.skipTrack(PARTY, PLAYLIST, 42L, true)).thenReturn(true);
        when(fallbackTrackCommandService.skipTrack(PARTY, PLAYLIST, 43L, true)).thenReturn(false);

        assertThat(service.skipTrack(PARTY, 42L)).isTrue();
        assertThat(service.skipTrack(PARTY, 43L)).isFalse();
    }

    @Test
    @DisplayName("without a fallback playlist there is nothing to skip — the command service is not asked")
    void skipTrack_shouldRefuse_whenThereIsNoPlaylist() {
        givenSettings(null, true);

        assertThat(service.skipTrack(PARTY, 42L)).isFalse();

        verifyNoInteractions(fallbackTrackCommandService);
    }

    // ---- a single video ----

    @Test
    @DisplayName("a video link is reported as a single video (there is nothing to skip to), a playlist is not")
    void shouldReportASingleVideo() {
        givenSettings("https://youtu.be/dQw4w9WgXcQ", true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, "V:dQw4w9WgXcQ", QUEUED, UPCOMING))
                .thenReturn(List.of(entity(1, "dQw4w9WgXcQ", "Never Gonna Give You Up")));

        assertThat(service.getUpcoming(PARTY).singleVideo()).isTrue();

        givenSettings(PLAYLIST_URL, true);
        when(fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, UPCOMING))
                .thenReturn(List.of(entity(1, "aaaaaaaaaaa", "A")));

        assertThat(service.getUpcoming(PARTY).singleVideo()).isFalse();
    }
}
