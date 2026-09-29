package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.MoveDirection;
import com.scan2play.model.PlaylistTrack;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.scan2play.model.FallbackTrackStatus.CANCELLED;
import static com.scan2play.model.FallbackTrackStatus.PLAYED;
import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static com.scan2play.service.FallbackTrackCommandService.ROTATION;
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
    /** The "next track" query: lowest play order first, ties by playlist position. */
    private static final PageRequest FIRST = PageRequest.of(0, 1, Sort.by("playOrder", "playlistPosition"));
    private static final String QUEUED_NAME = "QUEUED";

    @Mock
    private FallbackTrackRepository repository;

    private FallbackTrackCommandService service;

    @BeforeEach
    void setUp() {
        service = new FallbackTrackCommandService(repository);
    }

    private static FallbackTrackEntity track(long id) {
        return FallbackTrackEntity.builder().id(id).partyCode(PARTY).playlistId(PLAYLIST).videoId("video" + id)
                .status(QUEUED).build();
    }

    /** A queued track with a play order, as the queue looks right after a renumbering (0, 1, 2, ...). */
    private static FallbackTrackEntity queued(long id, int playOrder) {
        FallbackTrackEntity t = track(id);
        t.setPlayOrder(playOrder);
        return t;
    }

    // ---- replaceTracks / cancelQueuedTracks / purge ----

    @Test
    @DisplayName("replaceTracks cancels the still-queued tracks first, then inserts the new ones as QUEUED")
    void replaceTracks_shouldSoftInvalidateThenInsert() {
        List<PlaylistTrack> tracks = List.of(new PlaylistTrack("a", "Song A"), new PlaylistTrack("b", null),
                new PlaylistTrack("c", "Song C"));

        int inserted = service.replaceTracks(PARTY, PLAYLIST, tracks, false);

        assertThat(inserted).isEqualTo(3);

        InOrder order = inOrder(repository);
        order.verify(repository).updateStatus(PARTY, QUEUED, CANCELLED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FallbackTrackEntity>> captor = ArgumentCaptor.forClass(List.class);
        order.verify(repository).saveAll(captor.capture());

        List<FallbackTrackEntity> saved = captor.getValue();
        assertThat(saved).extracting(FallbackTrackEntity::getVideoId).containsExactly("a", "b", "c");
        assertThat(saved).extracting(FallbackTrackEntity::getTitle).containsExactly("Song A", null, "Song C");
        assertThat(saved).extracting(FallbackTrackEntity::getPlaylistPosition).containsExactly(0, 1, 2);
        // playlist order unless shuffled
        assertThat(saved).extracting(FallbackTrackEntity::getPlayOrder).containsExactly(0, 1, 2);
        assertThat(saved).allSatisfy(t -> {
            assertThat(t.getPartyCode()).isEqualTo(PARTY);
            assertThat(t.getPlaylistId()).isEqualTo(PLAYLIST);
            assertThat(t.getStatus()).isEqualTo(QUEUED);
            assertThat(t.getPlayedAt()).isNull();
        });
        // one import = one fetch timestamp (basis of the 30-day retention, and how a batch is identified)
        assertThat(saved).extracting(FallbackTrackEntity::getFetchedAt).doesNotContainNull().containsOnly(saved.get(0).getFetchedAt());
        verify(repository, never()).shuffle(any(), any(), any());
    }

    @Test
    @DisplayName("with shuffle on, the freshly inserted tracks are shuffled (after they have been saved)")
    void replaceTracks_shouldShuffleTheNewTracks_whenShuffleIsOn() {
        service.replaceTracks(PARTY, PLAYLIST, List.of(new PlaylistTrack("a", null), new PlaylistTrack("b", null)), true);

        InOrder order = inOrder(repository);
        order.verify(repository).saveAll(any());
        order.verify(repository).shuffle(PARTY, PLAYLIST, QUEUED_NAME);
    }

    @Test
    @DisplayName("replaceTracks never deletes rows — PLAYED history is kept")
    void replaceTracks_shouldNotDeleteAnything() {
        service.replaceTracks(PARTY, PLAYLIST, List.of(new PlaylistTrack("a", null)), true);

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

    // ---- applyShuffleSetting (the DJ flips the shuffle switch) ----

    @Test
    @DisplayName("shuffle switched on: the tracks still queued get a fresh random order")
    void applyShuffleSetting_shouldReshuffle_whenSwitchedOn() {
        service.applyShuffleSetting(PARTY, PLAYLIST, true);

        verify(repository).shuffle(PARTY, PLAYLIST, QUEUED_NAME);
        verify(repository, never()).orderByPlaylistPosition(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("shuffle switched off: playlist order continues AFTER the track handed out last (no jump back to the start)")
    void applyShuffleSetting_shouldContinueAfterTheLastPlayedTrack_whenSwitchedOff() {
        FallbackTrackEntity lastPlayed = track(9);
        lastPlayed.setPlaylistPosition(7);
        lastPlayed.setStatus(PLAYED);
        when(repository.findFirstByPartyCodeAndPlaylistIdAndStatusOrderByPlayedAtDesc(PARTY, PLAYLIST, PLAYED))
                .thenReturn(Optional.of(lastPlayed));

        service.applyShuffleSetting(PARTY, PLAYLIST, false);

        verify(repository).orderByPlaylistPosition(PARTY, PLAYLIST, QUEUED, 7, ROTATION);
        verify(repository, never()).shuffle(any(), any(), any());
    }

    @Test
    @DisplayName("shuffle switched off with nothing played yet: plain playlist order from the first track")
    void applyShuffleSetting_shouldUsePlainPlaylistOrder_whenNothingPlayedYet() {
        when(repository.findFirstByPartyCodeAndPlaylistIdAndStatusOrderByPlayedAtDesc(PARTY, PLAYLIST, PLAYED))
                .thenReturn(Optional.empty());

        service.applyShuffleSetting(PARTY, PLAYLIST, false);

        verify(repository).orderByPlaylistPosition(PARTY, PLAYLIST, QUEUED, -1, ROTATION);
    }

    // ---- takeNextTrack ----

    @Test
    @DisplayName("the queued track with the lowest play order is taken and marked PLAYED")
    void takeNextTrack_shouldTakeTheFirstInPlayOrder() {
        FallbackTrackEntity first = track(11);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(first));
        when(repository.claimQueuedTrack(eq(11L), eq(QUEUED), eq(PLAYED), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(2L);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, true)).contains(first);

        verify(repository).claimQueuedTrack(eq(11L), eq(QUEUED), eq(PLAYED), any(LocalDateTime.class));
        // more tracks are queued, so the round goes on — nothing is re-queued or re-ordered
        verify(repository, never()).requeuePlayedTracks(any(), any(), any(), any());
        verify(repository, never()).shuffle(any(), any(), any());
    }

    @Test
    @DisplayName("losing the race for a track (someone else claimed it) retries with the next one")
    void takeNextTrack_shouldRetry_whenTrackWasClaimedByAnotherCaller() {
        FallbackTrackEntity lost = track(1);
        FallbackTrackEntity won = track(2);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(lost), List.of(won));
        when(repository.claimQueuedTrack(eq(1L), any(), any(), any())).thenReturn(0);
        when(repository.claimQueuedTrack(eq(2L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(1L);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(won);
    }

    @Test
    @DisplayName("it gives up after a bounded number of lost races instead of looping forever")
    void takeNextTrack_shouldGiveUp_afterTooManyLostRaces() {
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(track(1)));
        when(repository.claimQueuedTrack(any(), any(), any(), any())).thenReturn(0);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, times(FallbackTrackCommandService.MAX_TAKE_ATTEMPTS)).claimQueuedTrack(any(), any(), any(), any());
    }

    // ---- the playlist loops: a new round starts as soon as the last track is handed out ----

    @Test
    @DisplayName("handing out the last queued track starts the next round at once, in playlist order (shuffle off)")
    void takeNextTrack_shouldStartNextRoundInPlaylistOrder_whenTheLastTrackIsTaken() {
        FallbackTrackEntity last = track(21);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(last));
        when(repository.claimQueuedTrack(eq(21L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(3);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(last);

        InOrder order = inOrder(repository);
        order.verify(repository).claimQueuedTrack(eq(21L), any(), any(), any());
        order.verify(repository).requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED);
        order.verify(repository).orderByPlaylistPosition(PARTY, PLAYLIST, QUEUED, -1, ROTATION);
        verify(repository, never()).shuffle(any(), any(), any());
    }

    @Test
    @DisplayName("with shuffle on the next round is freshly shuffled — and never opens with the track that is still playing")
    void takeNextTrack_shouldNotOpenTheNextRoundWithTheTrackThatIsPlaying() {
        FallbackTrackEntity last = track(31);
        // 1st call: the track to hand out; 2nd call: the head of the freshly shuffled round — it is the same track
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(last), List.of(last));
        when(repository.claimQueuedTrack(eq(31L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(4);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, true)).contains(last);

        InOrder order = inOrder(repository);
        order.verify(repository).requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED);
        order.verify(repository).shuffle(PARTY, PLAYLIST, QUEUED_NAME);
        order.verify(repository).moveToEnd(31L, QUEUED);
    }

    @Test
    @DisplayName("if the shuffled round starts with another track, nothing is moved")
    void takeNextTrack_shouldLeaveTheShuffledRoundAlone_whenItDoesNotOpenWithTheCurrentTrack() {
        FallbackTrackEntity last = track(31);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(last), List.of(track(32)));
        when(repository.claimQueuedTrack(eq(31L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(4);

        service.takeNextTrack(PARTY, PLAYLIST, true);

        verify(repository, never()).moveToEnd(any(), any());
    }

    @Test
    @DisplayName("a one-track playlist simply repeats: the round restarts and there is nothing to keep out of first place")
    void takeNextTrack_shouldRepeatASingleTrackPlaylist() {
        FallbackTrackEntity only = track(41);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(only));
        when(repository.claimQueuedTrack(eq(41L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(0L);
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(1);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, true)).contains(only);

        verify(repository, times(1)).findByPartyCodeAndPlaylistIdAndStatus(any(), any(), any(), any());
        verify(repository, never()).moveToEnd(any(), any());
    }

    @Test
    @DisplayName("a queue that is empty for another reason is refilled from the played tracks, then served")
    void takeNextTrack_shouldStartANewRound_whenTheQueueIsEmpty() {
        FallbackTrackEntity first = track(51);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST))
                .thenReturn(List.of(), List.of(first));
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(3);
        when(repository.claimQueuedTrack(eq(51L), any(), any(), any())).thenReturn(1);
        when(repository.countByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED)).thenReturn(2L);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).contains(first);

        verify(repository).orderByPlaylistPosition(PARTY, PLAYLIST, QUEUED, -1, ROTATION);
    }

    @Test
    @DisplayName("nothing queued and nothing to re-queue (never imported, all cancelled, or superseded by a newer import) → empty")
    void takeNextTrack_shouldReturnEmpty_whenNothingToRequeue() {
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of());
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(0);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, never()).claimQueuedTrack(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the playlist is re-queued at most once per call (no endless loop if the re-queued tracks vanish)")
    void takeNextTrack_shouldRequeueOnlyOncePerCall() {
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of());
        when(repository.requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED)).thenReturn(2);

        assertThat(service.takeNextTrack(PARTY, PLAYLIST, false)).isEmpty();
        verify(repository, times(1)).requeuePlayedTracks(PARTY, PLAYLIST, PLAYED, QUEUED);
    }

    // ---- moveTrack (the DJ reorders the queue) ----

    private void givenQueue(FallbackTrackEntity... queue) {
        // after renumbering, the whole queue is loaded in play order
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(eq(PARTY), eq(PLAYLIST), eq(QUEUED), any(Pageable.class)))
                .thenReturn(List.of(queue));
    }

    private void givenTrackIsQueued(FallbackTrackEntity track) {
        when(repository.findByIdAndPartyCode(track.getId(), PARTY)).thenReturn(Optional.of(track));
    }

    @Test
    @DisplayName("UP swaps the play orders of the track and the one before it - after renumbering, so no two are equal")
    void moveTrack_up_shouldSwapWithThePreviousTrack() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2);
        givenTrackIsQueued(b);
        givenQueue(a, b, c);

        assertThat(service.moveTrack(PARTY, PLAYLIST, 2L, MoveDirection.UP)).isTrue();

        InOrder order = inOrder(repository);
        order.verify(repository).renumberQueued(PARTY, PLAYLIST, QUEUED_NAME);
        order.verify(repository).setPlayOrderByHand(2L, 0, QUEUED);
        order.verify(repository).setPlayOrderByHand(1L, 1, QUEUED);
        verify(repository, never()).setPlayOrderByHand(eq(3L), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("DOWN swaps the play orders of the track and the one after it")
    void moveTrack_down_shouldSwapWithTheNextTrack() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2);
        givenTrackIsQueued(b);
        givenQueue(a, b, c);

        assertThat(service.moveTrack(PARTY, PLAYLIST, 2L, MoveDirection.DOWN)).isTrue();

        verify(repository).setPlayOrderByHand(2L, 2, QUEUED);
        verify(repository).setPlayOrderByHand(3L, 1, QUEUED);
    }

    @Test
    @DisplayName("UP on the first and DOWN on the last track change nothing (and flag nothing) but are not an error")
    void moveTrack_shouldDoNothing_atTheEndsOfTheQueue() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1);
        givenTrackIsQueued(a);
        givenTrackIsQueued(b);
        givenQueue(a, b);

        assertThat(service.moveTrack(PARTY, PLAYLIST, 1L, MoveDirection.UP)).isTrue();
        assertThat(service.moveTrack(PARTY, PLAYLIST, 2L, MoveDirection.DOWN)).isTrue();

        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("TOP puts the track in front of all the others and flags it as moved by hand")
    void moveTrack_top_shouldMoveTheTrackToTheFront() {
        FallbackTrackEntity third = queued(3, 2);
        givenTrackIsQueued(third);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(queued(1, 0)));
        when(repository.moveToFront(3L, QUEUED)).thenReturn(1);

        assertThat(service.moveTrack(PARTY, PLAYLIST, 3L, MoveDirection.TOP)).isTrue();

        verify(repository).moveToFront(3L, QUEUED);
        verify(repository, never()).renumberQueued(any(), any(), any());
    }

    @Test
    @DisplayName("TOP on the track that is already next changes nothing - it is not flagged as moved")
    void moveTrack_top_shouldDoNothing_whenTheTrackIsAlreadyNext() {
        FallbackTrackEntity first = queued(1, 0);
        givenTrackIsQueued(first);
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, FIRST)).thenReturn(List.of(first));

        assertThat(service.moveTrack(PARTY, PLAYLIST, 1L, MoveDirection.TOP)).isTrue();

        verify(repository, never()).moveToFront(any(), any());
    }

    @Test
    @DisplayName("a track that does not exist, or belongs to another party, cannot be moved - nothing is written")
    void moveTrack_shouldRefuseUnknownOrForeignTracks() {
        when(repository.findByIdAndPartyCode(99L, PARTY)).thenReturn(Optional.empty()); // what another party's id looks like

        for (MoveDirection direction : MoveDirection.values()) {
            assertThat(service.moveTrack(PARTY, PLAYLIST, 99L, direction)).isFalse();
        }

        verify(repository, never()).moveToFront(any(), any());
        verify(repository, never()).renumberQueued(any(), any(), any());
        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a track the player has already taken (PLAYED) or one of an old playlist cannot be moved")
    void moveTrack_shouldRefuseTracksThatAreNotQueuedInTheCurrentPlaylist() {
        FallbackTrackEntity played = queued(1, 0);
        played.setStatus(PLAYED);
        FallbackTrackEntity oldPlaylist = queued(2, 0);
        oldPlaylist.setPlaylistId("PLold");
        givenTrackIsQueued(played);
        givenTrackIsQueued(oldPlaylist);

        assertThat(service.moveTrack(PARTY, PLAYLIST, 1L, MoveDirection.UP)).isFalse();
        assertThat(service.moveTrack(PARTY, PLAYLIST, 2L, MoveDirection.TOP)).isFalse();

        verify(repository, never()).moveToFront(any(), any());
        verify(repository, never()).renumberQueued(any(), any(), any());
    }

    @Test
    @DisplayName("if the player takes the track while it is being moved, the move is refused instead of touching other tracks")
    void moveTrack_shouldRefuse_whenThePlayerTookTheTrackInTheMeantime() {
        FallbackTrackEntity taken = queued(2, 1);
        givenTrackIsQueued(taken);
        givenQueue(queued(1, 0), queued(3, 1)); // the reloaded queue no longer contains track 2

        assertThat(service.moveTrack(PARTY, PLAYLIST, 2L, MoveDirection.UP)).isFalse();

        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // ---- the per-party queue lock (statements that update many rows must not run side by side) ----

    private static final long LOCK_KEY = FallbackTrackCommandService.queueLockKey(PARTY);

    @Test
    @DisplayName("every operation that changes the queue takes the party's lock before it touches any row")
    void everyQueueChangeTakesThePartyLockFirst() {
        FallbackTrackEntity first = queued(1, 0);
        when(repository.findByIdAndPartyCode(1L, PARTY)).thenReturn(Optional.of(first));
        when(repository.findByPartyCodeAndPlaylistIdAndStatus(eq(PARTY), eq(PLAYLIST), eq(QUEUED), any(Pageable.class)))
                .thenReturn(List.of(first));

        service.replaceTracks(PARTY, PLAYLIST, List.of(new PlaylistTrack("a", null)), false);
        service.cancelQueuedTracks(PARTY);
        service.applyShuffleSetting(PARTY, PLAYLIST, true);
        service.moveTrack(PARTY, PLAYLIST, 1L, MoveDirection.UP);
        service.takeNextTrack(PARTY, PLAYLIST, false);

        InOrder order = inOrder(repository);
        order.verify(repository).lockQueue(LOCK_KEY);
        order.verify(repository).updateStatus(PARTY, QUEUED, CANCELLED);            // replaceTracks
        order.verify(repository).lockQueue(LOCK_KEY);
        order.verify(repository).updateStatus(PARTY, QUEUED, CANCELLED);            // cancelQueuedTracks
        order.verify(repository).lockQueue(LOCK_KEY);
        order.verify(repository).shuffle(PARTY, PLAYLIST, QUEUED_NAME);             // applyShuffleSetting
        order.verify(repository).lockQueue(LOCK_KEY);
        order.verify(repository).findByIdAndPartyCode(1L, PARTY);                   // moveTrack
        order.verify(repository).lockQueue(LOCK_KEY);
        order.verify(repository).findByPartyCodeAndPlaylistIdAndStatus(any(), any(), any(), any()); // takeNextTrack
        verify(repository, times(5)).lockQueue(LOCK_KEY);
    }

    @Test
    @DisplayName("each party has its own lock key, so one DJ never waits for another")
    void eachPartyHasItsOwnLockKey() {
        assertThat(FallbackTrackCommandService.queueLockKey("ABC12")).isNotEqualTo(FallbackTrackCommandService.queueLockKey("XYZ99"));
        assertThat(FallbackTrackCommandService.queueLockKey("ABC12")).isEqualTo(FallbackTrackCommandService.queueLockKey("ABC12"));
    }

    // ---- placeTrack (the DJ drags a track to a new place) ----

    private void givenAnyTrackIsQueued(long... ids) {
        for (long id : ids) {
            givenTrackIsQueued(queued(id, 0));
        }
    }

    @Test
    @DisplayName("dragged down: the tracks it passes move up by one, the track takes the place in front of the target")
    void placeTrack_shouldMoveATrackDown() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2), d = queued(4, 3);
        givenAnyTrackIsQueued(1, 4);
        givenQueue(a, b, c, d);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 1L, 4L)).isTrue();   // a in front of d: b, c, a, d

        InOrder order = inOrder(repository);
        order.verify(repository).renumberQueued(PARTY, PLAYLIST, QUEUED_NAME);
        order.verify(repository).shiftPlayOrder(PARTY, PLAYLIST, QUEUED, 1, 2, -1);
        order.verify(repository).setPlayOrderByHand(1L, 2, QUEUED);
    }

    @Test
    @DisplayName("dragged up: the tracks it passes move down by one, the track takes the place in front of the target")
    void placeTrack_shouldMoveATrackUp() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2), d = queued(4, 3);
        givenAnyTrackIsQueued(4, 2);
        givenQueue(a, b, c, d);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 4L, 2L)).isTrue();   // d in front of b: a, d, b, c

        InOrder order = inOrder(repository);
        order.verify(repository).renumberQueued(PARTY, PLAYLIST, QUEUED_NAME);
        order.verify(repository).shiftPlayOrder(PARTY, PLAYLIST, QUEUED, 1, 2, 1);
        order.verify(repository).setPlayOrderByHand(4L, 1, QUEUED);
    }

    @Test
    @DisplayName("dropped after the last row (no target): the track goes to the very end")
    void placeTrack_shouldMoveATrackToTheEnd() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2);
        givenAnyTrackIsQueued(1);
        givenQueue(a, b, c);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 1L, null)).isTrue();   // b, c, a

        verify(repository).shiftPlayOrder(PARTY, PLAYLIST, QUEUED, 1, 2, -1);
        verify(repository).setPlayOrderByHand(1L, 2, QUEUED);
    }

    @Test
    @DisplayName("dropped at the very front: the track goes in front of the first one")
    void placeTrack_shouldMoveATrackToTheFront() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2);
        givenAnyTrackIsQueued(3, 1);
        givenQueue(a, b, c);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 3L, 1L)).isTrue();   // c, a, b

        verify(repository).shiftPlayOrder(PARTY, PLAYLIST, QUEUED, 0, 1, 1);
        verify(repository).setPlayOrderByHand(3L, 0, QUEUED);
    }

    @Test
    @DisplayName("dropped where it already is (in front of its own successor, or at the end while last): nothing changes, nothing is flagged")
    void placeTrack_shouldDoNothing_whenTheTrackStaysWhereItIs() {
        FallbackTrackEntity a = queued(1, 0), b = queued(2, 1), c = queued(3, 2);
        givenAnyTrackIsQueued(2, 3);
        givenQueue(a, b, c);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 2L, 3L)).isTrue();    // b in front of c: already so
        assertThat(service.placeTrack(PARTY, PLAYLIST, 3L, null)).isTrue();  // c at the end: already so

        verify(repository, never()).shiftPlayOrder(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("dropped on itself: nothing is touched, not even the queue")
    void placeTrack_shouldDoNothing_whenDroppedOnItself() {
        givenAnyTrackIsQueued(2);

        assertThat(service.placeTrack(PARTY, PLAYLIST, 2L, 2L)).isTrue();

        verify(repository, never()).renumberQueued(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown track, another party's track, a taken one or one of an old playlist cannot be dragged - nothing is written")
    void placeTrack_shouldRefuseTracksTheDjCannotReach() {
        FallbackTrackEntity played = queued(1, 0);
        played.setStatus(PLAYED);
        FallbackTrackEntity oldPlaylist = queued(2, 0);
        oldPlaylist.setPlaylistId("PLold");
        givenTrackIsQueued(played);
        givenTrackIsQueued(oldPlaylist);
        givenAnyTrackIsQueued(5);
        when(repository.findByIdAndPartyCode(99L, PARTY)).thenReturn(Optional.empty());

        assertThat(service.placeTrack(PARTY, PLAYLIST, 99L, null)).isFalse();
        assertThat(service.placeTrack(PARTY, PLAYLIST, 1L, null)).isFalse();
        assertThat(service.placeTrack(PARTY, PLAYLIST, 2L, null)).isFalse();
        // the target must be reachable too: unknown, taken or from an old playlist
        assertThat(service.placeTrack(PARTY, PLAYLIST, 5L, 99L)).isFalse();
        assertThat(service.placeTrack(PARTY, PLAYLIST, 5L, 1L)).isFalse();
        assertThat(service.placeTrack(PARTY, PLAYLIST, 5L, 2L)).isFalse();

        verify(repository, never()).renumberQueued(any(), any(), any());
        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("if the player takes one of the tracks while the drop is being handled, the drop is refused and nothing else is touched")
    void placeTrack_shouldRefuse_whenThePlayerTookATrackInTheMeantime() {
        givenAnyTrackIsQueued(2, 3);
        givenQueue(queued(1, 0), queued(2, 1));   // the reloaded queue no longer contains track 3

        assertThat(service.placeTrack(PARTY, PLAYLIST, 2L, 3L)).isFalse();

        verify(repository, never()).setPlayOrderByHand(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a drop takes the party's queue lock before it reads or writes anything")
    void placeTrack_shouldTakeThePartyLockFirst() {
        givenAnyTrackIsQueued(1);

        service.placeTrack(PARTY, PLAYLIST, 1L, 1L);

        InOrder order = inOrder(repository);
        order.verify(repository).lockQueue(FallbackTrackCommandService.queueLockKey(PARTY));
        order.verify(repository).findByIdAndPartyCode(1L, PARTY);
    }
}
