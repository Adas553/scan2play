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
 * What a guest sees of the requests: one list, the most votes first, the newest first among equals (the DJ plays them in the order
 * the DJ likes), five shown and the rest folded, and whether the guest's own song waits.
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

    /** Waiting songs with these votes, ids 10.. in the order given — oldest first, as the dashboard's queue. */
    private void givenVotes(int... votes) {
        List<SongRequestEntity> waiting = new java.util.ArrayList<>();
        for (int i = 0; i < votes.length; i++) {
            waiting.add(SongRequestEntity.builder().id(10L + i).partyCode(PARTY).songName("Song " + (10 + i)).votes(votes[i]).build());
        }
        when(djService.getDashboardQueue(PARTY)).thenReturn(waiting);
    }

    /**
     * One list (the owner, 2026-10-08): the most votes first, the newest first among equal votes — a fresh request in sight to be
     * voted for —, the first five shown, the rest folded.
     */
    @Test
    void oneList_theMostVotesFirst_theNewestFirstAmongEquals_fiveShown() {
        givenVotes(1, 3, 1, 2, 1, 3, 1);   // ids 10..16, oldest first

        GuestQueue queue = service.view(PARTY, Set.of(), Set.of(11L));

        assertThat(queue.shown()).extracting(SongRequestEntity::getId).as("3 votes (the newer first), 2, then the newest of one vote")
                .containsExactly(15L, 11L, 13L, 16L, 14L);
        assertThat(queue.more()).extracting(SongRequestEntity::getId).containsExactly(12L, 10L);
        assertThat(queue.myVotes()).containsExactly(11L);
    }

    /** The screen for a TV (Next 7 c): the ten most wanted of the waiting songs, in the order of the guests' list. */
    @Test
    void theTvScreen_showsTheTenMostWanted_inTheGuestsOrder() {
        givenVotes(0, 5, 1, 1, 9, 0, 2, 3, 0, 4, 7, 6);   // ids 10..21, oldest first

        assertThat(service.mostWanted(PARTY)).extracting(SongRequestEntity::getId)
                .containsExactly(14L, 20L, 21L, 11L, 19L, 17L, 16L, 13L, 12L, 18L);
    }

    @Test
    void theTvScreen_ofAnEmptyQueue_isEmpty() {
        givenQueue();
        assertThat(service.mostWanted(PARTY)).isEmpty();
    }

    @Test
    void fiveOrFewerWaiting_areAllShown_nothingFolded() {
        givenQueue(10, 11, 12, 13, 14);

        GuestQueue queue = service.view(PARTY, Set.of());

        assertThat(queue.shown()).extracting(SongRequestEntity::getId).as("the newest first: no votes yet")
                .containsExactly(14L, 13L, 12L, 11L, 10L);
        assertThat(queue.more()).isEmpty();
        assertThat(queue.mySong()).isNull();
    }

    @Test
    void theGuestsSong_isFoundInTheWholeQueue_notOnlyInTheFiveShown() {
        givenQueue(10, 11, 12, 13, 14, 15, 16);

        GuestQueue queue = service.view(PARTY, Set.of(10L, 99L));

        assertThat(queue.mySong()).isEqualTo("Song 10");
        assertThat(queue.myWaiting()).isEqualTo(1);
    }

    @Test
    void ofSeveralSongsOfTheGuest_noneIsNamed_theyAreCounted() {
        givenQueue(10, 11, 12);

        GuestQueue queue = service.view(PARTY, Set.of(12L, 11L));

        assertThat(queue.mySong()).as("naming one of them read as the AI's mix-up").isNull();
        assertThat(queue.myWaiting()).isEqualTo(2);
    }

    @Test
    void anEmptyQueue_showsNothing() {
        givenQueue();

        GuestQueue queue = service.view(PARTY, Set.of(1L));

        assertThat(queue.shown()).isEmpty();
        assertThat(queue.more()).isEmpty();
        assertThat(queue.mySong()).isNull();
    }

    /**
     * The review of 2026-10-21, S1: a request the AI could not judge (V39) is a guest's own words. The guests' list shows it only to
     * the guest who sent it; the TV screen of the hall never.
     */
    @Test
    void anUncheckedRequest_isOnlyOnItsOwnGuestsList_andNeverOnTheTv() {
        SongRequestEntity checked = SongRequestEntity.builder().id(10L).partyCode(PARTY).songName("Wilki - Baśka").votes(1).build();
        SongRequestEntity unchecked = SongRequestEntity.builder().id(11L).partyCode(PARTY).songName("whatever the guest typed")
                .votes(3).checked(false).build();
        when(djService.getDashboardQueue(PARTY)).thenReturn(List.of(checked, unchecked));

        GuestQueue another = service.view(PARTY, Set.of(), Set.of());
        assertThat(another.shown()).extracting(SongRequestEntity::getId).containsExactly(10L);
        assertThat(another.more()).isEmpty();
        assertThat(another.song(11L)).isEmpty();

        GuestQueue theSender = service.view(PARTY, Set.of(11L), Set.of());
        assertThat(theSender.shown()).extracting(SongRequestEntity::getId).containsExactly(11L, 10L);
        assertThat(theSender.mySong()).isEqualTo("whatever the guest typed");

        assertThat(service.mostWanted(PARTY)).extracting(SongRequestEntity::getId).containsExactly(10L);
    }
}
