package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** The dashboard reads the queue through a 3 s cache — a song that played leaves it at once. */
    @Test
    void markSongAsPlayed_evictsTheCachedDashboardQueueOfTheParty() {
        SongRequestEntity song = SongRequestEntity.builder()
                .id(1L).partyCode(PARTY_CODE).songName("Test Song").decision(DECISION_ACCEPTED).build();
        Cache dashboardQueue = mock(Cache.class);
        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));
        when(cacheManager.getCache("dashboardQueue")).thenReturn(dashboardQueue);

        djService.markSongAsPlayed(1L, PARTY_CODE);

        verify(dashboardQueue).evict(PARTY_CODE);
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

    // ---- the moment a request was played (V6): the history and "previous track" order by it ----

    @Test
    void markSongAsPlayed_shouldRecordWhenTheSongWasPlayed() {
        SongRequestEntity song = SongRequestEntity.builder().id(1L).partyCode(PARTY_CODE).songName("Test Song")
                .decision(DECISION_ACCEPTED).build();
        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));
        Instant before = Instant.now();

        djService.markSongAsPlayed(1L, PARTY_CODE);

        assertThat(song.getPlayedAt()).isBetween(before, Instant.now());
    }

    @Test
    void markSongAsPlayed_shouldKeepTheFirstPlayTime_whenConfirmedAgain() {
        // a second "Mark as played" (another window, a double tap) must not move the first play time
        Instant first = java.time.LocalDateTime.of(2026, 9, 29, 20, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();
        SongRequestEntity song = SongRequestEntity.builder().id(1L).partyCode(PARTY_CODE).songName("Test Song")
                .decision(DECISION_PLAYED).playedAt(first).build();
        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));

        djService.markSongAsPlayed(1L, PARTY_CODE);

        assertThat(song.getPlayedAt()).isEqualTo(first);
    }

    @Test
    void markSongAsPlayed_shouldNotSetAPlayTime_whenThePartyDoesNotMatch() {
        SongRequestEntity song = SongRequestEntity.builder().id(1L).partyCode("OTHER").songName("Test Song")
                .decision(DECISION_ACCEPTED).build();
        when(songRequestRepository.findById(1L)).thenReturn(Optional.of(song));

        djService.markSongAsPlayed(1L, PARTY_CODE);

        assertThat(song.getPlayedAt()).isNull();
    }

    @Test
    void markPlayed_shouldSetTheDecisionAndTheMoment_andKeepAMomentThatIsAlreadySet() {
        Instant now = java.time.LocalDateTime.of(2026, 9, 29, 21, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();
        SongRequestEntity fresh = SongRequestEntity.builder().decision(DECISION_ACCEPTED).build();
        SongRequestEntity replayed = SongRequestEntity.builder().decision(DECISION_PLAYED)
                .playedAt(now.minus(1, ChronoUnit.HOURS)).build();

        DjService.markPlayed(fresh, now);
        DjService.markPlayed(replayed, now);

        assertThat(fresh.getDecision()).isEqualTo(DECISION_PLAYED);
        assertThat(fresh.getPlayedAt()).isEqualTo(now);
        assertThat(replayed.getPlayedAt()).isEqualTo(now.minus(1, ChronoUnit.HOURS));
    }

    // ---- dismissSong: the DJ skips a waiting request (a requests-only party) ----

    @Test
    void dismissSong_takesAWaitingRequestOutOfTheQueue_asRejected_withTheDjsNote() {
        SongRequestEntity song = SongRequestEntity.builder().id(5L).partyCode(PARTY_CODE).songName("Unknown Song")
                .decision(DECISION_ACCEPTED).djComment("The AI liked it").build();
        when(songRequestRepository.findById(5L)).thenReturn(Optional.of(song));
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        djService.dismissSong(5L, PARTY_CODE);

        assertThat(song.getDecision()).isEqualTo(DECISION_REJECTED);
        assertThat(song.getDjComment()).isEqualTo(DjService.DJ_DISMISS_COMMENT);
        assertThat(song.getPlayedAt()).isNull();
        verify(songRequestRepository).save(song);
        verify(queue).evict(PARTY_CODE);
    }

    @Test
    void clearQueue_rejectsTheOwnPartysWaitingRequests_withTheDjsNote_andRefreshesTheQueue() {
        when(songRequestRepository.rejectWaiting(PARTY_CODE, DjService.DJ_CLEAR_COMMENT)).thenReturn(3);
        Cache queue = mock(Cache.class);
        when(cacheManager.getCache("dashboardQueue")).thenReturn(queue);

        assertThat(djService.clearQueue(PARTY_CODE)).isEqualTo(3);

        verify(songRequestRepository).rejectWaiting(PARTY_CODE, DjService.DJ_CLEAR_COMMENT);
        verify(queue).evict(PARTY_CODE);
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
}
