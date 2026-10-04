package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.GuestQueueService.GuestQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a guest sees of the requests: the ones sent lately (the newest first — the DJ plays them in the order the DJ likes), the
 * most wanted ones, and whether the guest's own song waits.
 */
class GuestQueueServiceTest {

    private static final String PARTY = "ABC12";

    private DjService djService;
    private GuestQueueService service;

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        service = new GuestQueueService(djService);
    }

    /** Accepted songs with the ids given, oldest first — the order of the dashboard's queue. */
    private void givenQueue(long... ids) {
        when(djService.getDashboardQueue(PARTY)).thenReturn(LongStream.of(ids)
                .mapToObj(id -> SongRequestEntity.builder().id(id).partyCode(PARTY).songName("Song " + id).build())
                .toList());
    }

    @Test
    void theMostWanted_areTheWaitingSongsOfMoreThanOneGuest_theMostVotesFirst_atMostThree() {
        int[] votes = {1, 3, 2, 5, 1, 3};   // ids 10..15, oldest first
        List<SongRequestEntity> waiting = new java.util.ArrayList<>();
        for (int i = 0; i < votes.length; i++) {
            waiting.add(SongRequestEntity.builder().id(10L + i).partyCode(PARTY).songName("Song " + (10 + i)).votes(votes[i]).build());
        }
        when(djService.getDashboardQueue(PARTY)).thenReturn(waiting);

        GuestQueue queue = service.view(PARTY, Set.of());

        assertThat(queue.mostWanted()).extracting(SongRequestEntity::getId).as("5 votes, then the two with 3, the longer waiting first")
                .containsExactly(13L, 11L, 15L);
    }

    @Test
    void theRequestsSentLately_areTheNewestFive_theNewestFirst() {
        givenQueue(10, 11, 12, 13, 14, 15, 16);

        GuestQueue queue = service.view(PARTY, Set.of());

        assertThat(queue.recent()).extracting(SongRequestEntity::getId).containsExactly(16L, 15L, 14L, 13L, 12L);
        assertThat(queue.mySong()).isNull();
    }

    @Test
    void theGuestsSong_isFoundInTheWholeQueue_notOnlyInTheFiveShown() {
        givenQueue(10, 11, 12, 13, 14, 15, 16);

        assertThat(service.view(PARTY, Set.of(10L, 99L)).mySong()).isEqualTo("Song 10");
    }

    @Test
    void ofSeveralSongsOfTheGuest_theOneThatWaitsLongestIsNamed() {
        givenQueue(10, 11, 12);

        assertThat(service.view(PARTY, Set.of(12L, 11L)).mySong()).isEqualTo("Song 11");
    }

    @Test
    void noSongWithMoreThanOneVote_noMostWanted() {
        givenQueue(10, 11);

        assertThat(service.view(PARTY, Set.of()).mostWanted()).isEmpty();
    }

    @Test
    void anEmptyQueue_showsNothing() {
        givenQueue();

        GuestQueue queue = service.view(PARTY, Set.of(1L));

        assertThat(queue.recent()).isEmpty();
        assertThat(queue.mySong()).isNull();
    }
}
