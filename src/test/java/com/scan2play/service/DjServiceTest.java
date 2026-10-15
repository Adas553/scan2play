package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import java.time.Instant;
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
    private CacheManager cacheManager;

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

        assertThat(result).isEqualTo("0-0-0");
    }

    // ---- markSongAsPlayed: one conditional UPDATE (review 2026-10-14 W5) ----

    @Test
    void markSongAsPlayed_playsTheRequestOfThePartyNow() {
        Instant before = Instant.now();
        when(songRequestRepository.markPlayed(eq(1L), eq(PARTY_CODE), any(Instant.class))).thenReturn(1);

        djService.markSongAsPlayed(1L, PARTY_CODE);

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(songRequestRepository).markPlayed(eq(1L), eq(PARTY_CODE), now.capture());
        assertThat(now.getValue()).isBetween(before, Instant.now());
        verify(songRequestRepository, never()).save(any());
    }

    /** The dashboard reads the queue through a 3 s cache — a song that played leaves it at once. */
    @Test
    void markSongAsPlayed_evictsTheCachedDashboardQueueOfTheParty() {
        Cache dashboardQueue = mock(Cache.class);
        when(songRequestRepository.markPlayed(eq(1L), eq(PARTY_CODE), any(Instant.class))).thenReturn(1);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(dashboardQueue);

        djService.markSongAsPlayed(1L, PARTY_CODE);

        verify(dashboardQueue).evict(PARTY_CODE);
    }

    /** Another party's request, one that is gone, or one no longer waiting (skipped, cleared, rejected): nothing changed. */
    @Test
    void markSongAsPlayed_changesNothingElse_whenTheUpdateFindsNoWaitingRequestOfTheParty() {
        when(songRequestRepository.markPlayed(eq(1L), eq(PARTY_CODE), any(Instant.class))).thenReturn(0);

        djService.markSongAsPlayed(1L, PARTY_CODE);

        verify(cacheManager, never()).getCache(any());
        verify(songRequestRepository, never()).save(any());
    }

    // ---- dismissSong: the DJ skips a waiting request (a requests-only party) ----

    @Test
    void dismissSong_takesAWaitingRequestOutOfTheQueue_asRejected_keepingTheAisComment() {
        SongRequestEntity song = SongRequestEntity.builder().id(5L).partyCode(PARTY_CODE).songName("Unknown Song")
                .decision(DECISION_ACCEPTED).djComment("The AI liked it").build();
        when(songRequestRepository.findById(5L)).thenReturn(Optional.of(song));
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        djService.dismissSong(5L, PARTY_CODE);

        assertThat(song.getDecision()).isEqualTo(DECISION_REJECTED);
        assertThat(song.getDjComment()).as("a restore gives it back with it").isEqualTo("The AI liked it");
        assertThat(song.getSkippedAt()).as("when it was skipped: the song stays out of the queue for a while from then")
                .isCloseTo(java.time.Instant.now(), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(song.getPlayedAt()).isNull();
        verify(songRequestRepository).save(song);
        verify(queue).evict(PARTY_CODE);
    }

    // ---- restoreSong ("Cofnij", "↩ Przywróć") ----

    @Test
    void restoreSong_putsTheRequestBackInTheQueue_andForgetsTheSkip() {
        SongRequestEntity song = SongRequestEntity.builder().id(5L).partyCode(PARTY_CODE).songName("Wilki - Baśka")
                .decision(DECISION_REJECTED).djComment("Klasyk wesel!").skippedAt(java.time.Instant.now()).votes(3).build();
        when(songRequestRepository.findById(5L)).thenReturn(Optional.of(song));
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        assertThat(djService.restoreSong(5L, PARTY_CODE)).isTrue();

        assertThat(song.getDecision()).isEqualTo(DECISION_ACCEPTED);
        assertThat(song.getSkippedAt()).isNull();
        assertThat(song.getDjComment()).as("the AI's comment, as before the skip").isEqualTo("Klasyk wesel!");
        assertThat(song.getVotes()).as("the guests' votes stay").isEqualTo(3);
        verify(songRequestRepository).lockRequests(SongRequestCommandService.lockKey(PARTY_CODE));
        verify(songRequestRepository).save(song);
        verify(queue).evict(PARTY_CODE);
    }

    /** A "Zagrane" by mistake (the owner, 2026-10-10): back in the queue, no longer played — the AI's duplicate rule reads the played. */
    @Test
    void restoreSong_putsAPlayedRequestBackInTheQueue_andItIsNoLongerPlayed() {
        SongRequestEntity song = SongRequestEntity.builder().id(5L).partyCode(PARTY_CODE).songName("Wilki - Baśka")
                .decision(DECISION_PLAYED).playedAt(java.time.Instant.now()).djComment("Klasyk wesel!").votes(3).tips(2).build();
        when(songRequestRepository.findById(5L)).thenReturn(Optional.of(song));
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        assertThat(djService.restoreSong(5L, PARTY_CODE)).isTrue();

        assertThat(song.getDecision()).isEqualTo(DECISION_ACCEPTED);
        assertThat(song.getPlayedAt()).isNull();
        assertThat(List.of(song.getDjComment(), song.getVotes(), song.getTips())).as("the comment, the votes and the tips stay")
                .containsExactly("Klasyk wesel!", 3, 2);
        verify(songRequestRepository).lockRequests(SongRequestCommandService.lockKey(PARTY_CODE));
        verify(songRequestRepository).save(song);
        verify(queue).evict(PARTY_CODE);
    }

    @Test
    void restoreSong_leavesAnotherPartysSong_aRejectionByTheAi_andASongThatWaitsAlready() {
        SongRequestEntity foreign = SongRequestEntity.builder().id(6L).partyCode("OTHER").songName("A")
                .decision(DECISION_REJECTED).skippedAt(java.time.Instant.now()).build();
        SongRequestEntity byTheAi = SongRequestEntity.builder().id(7L).partyCode(PARTY_CODE).songName("B")
                .decision(DECISION_REJECTED).djComment("Not tonight").build();
        SongRequestEntity skipped = SongRequestEntity.builder().id(8L).partyCode(PARTY_CODE).songName("Wilki - Baśka")
                .decision(DECISION_REJECTED).skippedAt(java.time.Instant.now()).build();
        when(songRequestRepository.findById(6L)).thenReturn(Optional.of(foreign));
        when(songRequestRepository.findById(7L)).thenReturn(Optional.of(byTheAi));
        when(songRequestRepository.findById(8L)).thenReturn(Optional.of(skipped));
        when(songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(PARTY_CODE, List.of(DECISION_ACCEPTED)))
                .thenReturn(List.of(SongRequestEntity.builder().id(9L).songName("wilki baśka").decision(DECISION_ACCEPTED).build()));

        assertThat(djService.restoreSong(6L, PARTY_CODE)).isFalse();
        assertThat(djService.restoreSong(7L, PARTY_CODE)).isFalse();
        assertThat(djService.restoreSong(8L, PARTY_CODE)).isFalse();
        assertThat(djService.restoreSong(99L, PARTY_CODE)).as("gone").isFalse();

        assertThat(List.of(foreign, byTheAi, skipped)).extracting(SongRequestEntity::getDecision).containsOnly(DECISION_REJECTED);
        verify(songRequestRepository, never()).save(any());
    }

    @Test
    void clearQueue_rejectsTheOwnPartysWaitingRequests_markedClearedNow_andRefreshesTheQueue() {
        java.time.Instant before = java.time.Instant.now();
        when(songRequestRepository.rejectWaiting(eq(PARTY_CODE), any())).thenReturn(3);
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        assertThat(djService.clearQueue(PARTY_CODE)).isEqualTo(3);

        verify(songRequestRepository).rejectWaiting(eq(PARTY_CODE),
                argThat(clearedAt -> !clearedAt.isBefore(before) && !clearedAt.isAfter(java.time.Instant.now())));
        verify(queue).evict(PARTY_CODE);
    }

    @Test
    void clearHistory_deletesTheOwnPartysHistory_underTheLock_keepingTheSkipsThatStillKeepTheirSongOut() {
        when(songRequestRepository.deleteHistory(eq(PARTY_CODE), any())).thenReturn(12);
        Instant before = Instant.now();

        assertThat(djService.clearHistory(PARTY_CODE)).isEqualTo(12);

        ArgumentCaptor<Instant> skippedAfter = ArgumentCaptor.forClass(Instant.class);
        InOrder order = inOrder(songRequestRepository);
        order.verify(songRequestRepository).lockRequests(SongRequestCommandService.lockKey(PARTY_CODE));
        order.verify(songRequestRepository).deleteHistory(eq(PARTY_CODE), skippedAfter.capture());
        assertThat(skippedAfter.getValue()).as("a skip of the last 2 hours stays")
                .isBetween(before.minus(SongRequestCommandService.SKIP_REMEMBERED), Instant.now().minus(SongRequestCommandService.SKIP_REMEMBERED));
        verifyNoInteractions(cacheManager);   // the queue is not touched
    }

    @Test
    void dismissSong_leavesAnotherPartysSong_andASongThatPlayed_asTheyAre() {
        SongRequestEntity foreign = SongRequestEntity.builder().id(6L).partyCode("OTHER").decision(DECISION_ACCEPTED).build();
        SongRequestEntity played = SongRequestEntity.builder().id(8L).partyCode(PARTY_CODE).decision(DECISION_PLAYED).build();
        when(songRequestRepository.findById(6L)).thenReturn(Optional.of(foreign));
        when(songRequestRepository.findById(8L)).thenReturn(Optional.of(played));

        djService.dismissSong(6L, PARTY_CODE);
        djService.dismissSong(8L, PARTY_CODE);

        assertThat(foreign.getDecision()).isEqualTo(DECISION_ACCEPTED);
        assertThat(played.getDecision()).isEqualTo(DECISION_PLAYED);
        verify(songRequestRepository, never()).save(any());
    }

    // ---- countTip (V28) ----

    @Test
    void countTip_countsOrTakesBack_onTheDjsOwnSong_andRefreshesTheQueue() {
        org.springframework.cache.Cache cache = mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache(DjService.QUEUE_CACHE)).thenReturn(cache);
        when(songRequestRepository.addTip(5L, PARTY_CODE)).thenReturn(1);
        when(songRequestRepository.removeTip(5L, PARTY_CODE)).thenReturn(1);

        assertThat(djService.countTip(5L, PARTY_CODE, true)).isTrue();
        assertThat(djService.countTip(5L, PARTY_CODE, false)).isTrue();

        verify(cache, times(2)).evict(PARTY_CODE);
    }

    @Test
    void countTip_onAnotherPartysSong_changesNothing() {
        when(songRequestRepository.addTip(5L, PARTY_CODE)).thenReturn(0);

        assertThat(djService.countTip(5L, PARTY_CODE, true)).isFalse();

        verify(cacheManager, never()).getCache(any());
    }
}
