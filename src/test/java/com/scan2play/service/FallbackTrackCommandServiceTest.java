package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.repository.FallbackTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

import static com.scan2play.model.FallbackTrackStatus.CANCELLED;
import static com.scan2play.model.FallbackTrackStatus.PLAYED;
import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackTrackCommandServiceTest {

    private static final String PARTY = "ABC12";
    private static final String PLAYLIST = "PLx";
    private static final PageRequest FIRST = PageRequest.of(0, 1, Sort.by("playlistPosition"));

    @Mock
    private FallbackTrackRepository repository;

    private FallbackTrackCommandService service;

    /** What the injected "random" returns, and the bound it was last asked for. */
    private int nextRandom;
    private int lastBound;

    @BeforeEach
    void setUp() {
        service = new FallbackTrackCommandService(repository, bound -> {
            lastBound = bound;
            return nextRandom;
        });
    }

    private static FallbackTrackEntity track(long id) {
        return FallbackTrackEntity.builder().id(id).partyCode(PARTY).playlistId(PLAYLIST).videoId("video" + id)
                .status(QUEUED).build();
    }

    // ---- replaceTracks / cancelQueuedTracks / purge ----

    @Test
    @DisplayName("replaceTracks cancels the still-queued tracks first, then inserts the new ones as QUEUED")
    void replaceTracks_shouldSoftInvalidateThenInsert() {
        int inserted = service.replaceTracks(PARTY, PLAYLIST, List.of("a", "b", "c"));

        assertThat(inserted).isEqualTo(3);

        InOrder order = inOrder(repository);
        order.verify(repository).updateStatus(PARTY, QUEUED, CANCELLED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FallbackTrackEntity>> captor = ArgumentCaptor.forClass(List.class);
        order.verify(repository).saveAll(captor.capture());

        List<FallbackTrackEntity> saved = captor.getValue();
        assertThat(saved).extracting(FallbackTrackEntity::getVideoId).containsExactly("a", "b", "c");
        assertThat(saved).extracting(FallbackTrackEntity::getPlaylistPosition).containsExactly(0, 1, 2);
        assertThat(saved).allSatisfy(t -> {
            assertThat(t.getPartyCode()).isEqualTo(PARTY);
            assertThat(t.getPlaylistId()).isEqualTo(PLAYLIST);
            assertThat(t.getStatus()).isEqualTo(QUEUED);
            assertThat(t.getPlayedAt()).isNull();
        });
        // one import = one fetch timestamp (basis of the 30-day retention, and how a batch is identified)
        assertThat(saved).extracting(FallbackTrackEntity::getFetchedAt).doesNotContainNull().containsOnly(saved.get(0).getFetchedAt());
    }

    @Test
    @DisplayName("replaceTracks never deletes rows — PLAYED history is kept")
    void replaceTracks_shouldNotDeleteAnything() {
        service.replaceTracks(PARTY, PLAYLIST, List.of("a"));

        verify(repository, never()).deleteByPartyCode(any());
        verify(repository, never()).deleteFetchedBefore(any());
    }

    @Test
    @DisplayName("cancelQueuedTracks flips QUEUED to CANCELLED and inserts nothing")
    void cancelQueuedTracks_shouldOnlyCancel() {
        service.cancelQueuedTracks(PARTY);

        verify(repository).updateStatus(PARTY, QUEUED, CANCELLED);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("purgeStaleTracks deletes tracks fetched more than 30 days ago")
    void purgeStaleTracks_shouldUseThirtyDayCutoff() {
        when(repository.deleteFetchedBefore(any())).thenReturn(4);
        LocalDateTime before = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);

        service.purgeStaleTracks();

        LocalDateTime after = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteFetchedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, after);
        assertThat(FallbackTrackEntity.MAX_AGE_DAYS).isEqualTo(30);
    }

    // ---- takeNextTrack ----

    @Test
    @DisplayName("without shuffle the queued track with the lowest playlist position is taken and marked PLAYED")
    void takeNextTrack_shouldTakeFirstInPlaylistOrder() {
        FallbackTrackEntity first = track(11);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(3L);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(first));
        when(repository.claimQueuedTrack(eq(11L), eq(QUEUED), eq(PLAYED), any())).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(first);
        verify(repository).claimQueuedTrack(eq(11L), eq(QUEUED), eq(PLAYED), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("with shuffle a random queued track is taken (random index within the queue size)")
    void takeNextTrack_shouldPickRandomTrack_whenShuffled() {
        nextRandom = 3;
        FallbackTrackEntity picked = track(14);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(5L);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED,
                PageRequest.of(3, 1, Sort.by("playlistPosition")))).thenReturn(List.of(picked));
        when(repository.claimQueuedTrack(eq(14L), any(), any(), any())).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, true)).contains(picked);
        assertThat(lastBound).isEqualTo(5);
    }

    @Test
    @DisplayName("losing the race for a track (someone else claimed it) retries with another one")
    void takeNextTrack_shouldRetry_whenTrackWasClaimedByAnotherCaller() {
        FallbackTrackEntity lost = track(1);
        FallbackTrackEntity won = track(2);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(2L, 1L);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(lost), List.of(won));
        when(repository.claimQueuedTrack(eq(1L), any(), any(), any())).thenReturn(0);
        when(repository.claimQueuedTrack(eq(2L), any(), any(), any())).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(won);
    }

    @Test
    @DisplayName("an empty page (the queue shrank between count and select) is retried")
    void takeNextTrack_shouldRetry_whenPageIsEmpty() {
        FallbackTrackEntity next = track(5);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(1L, 1L);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(), List.of(next));
        when(repository.claimQueuedTrack(eq(5L), any(), any(), any())).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(next);
    }

    @Test
    @DisplayName("it gives up after a bounded number of lost races instead of looping forever")
    void takeNextTrack_shouldGiveUp_afterTooManyLostRaces() {
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(5L);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(track(1)));
        when(repository.claimQueuedTrack(any(), any(), any(), any())).thenReturn(0);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, times(5)).claimQueuedTrack(any(), any(), any(), any());
    }

    @Test
    @DisplayName("when every track has been played the playlist loops: the newest import's played tracks are re-queued")
    void takeNextTrack_shouldLoopThePlaylist_whenQueueIsExhausted() {
        FallbackTrackEntity first = track(21);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L, 3L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(3);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(first));
        when(repository.claimQueuedTrack(eq(21L), any(), any(), any())).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(first);
    }

    @Test
    @DisplayName("nothing queued and nothing to re-queue (never imported, all cancelled, or superseded by a newer import) → empty")
    void takeNextTrack_shouldReturnEmpty_whenNothingToRequeue() {
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(0);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, never()).claimQueuedTrack(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the playlist is re-queued at most once per call (no endless loop if the re-queued tracks vanish)")
    void takeNextTrack_shouldRequeueOnlyOncePerCall() {
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(2);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, times(1)).requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED);
    }
}
