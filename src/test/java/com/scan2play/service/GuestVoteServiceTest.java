package com.scan2play.service;

import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A guest's 👍 on the list (the owner, 2026-10-08): one vote per song, as many songs as the guest likes, taken back with a second tap. */
class GuestVoteServiceTest {

    private static final String PARTY = "ABC12";

    private SongRequestRepository repository;
    private DjService djService;
    private GuestVoteService service;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        repository = mock(SongRequestRepository.class);
        djService = mock(DjService.class);
        service = new GuestVoteService(repository, djService);
        session = new MockHttpSession();
        when(repository.addGuestVote(anyLong(), anyString())).thenReturn(1);
        when(repository.removeGuestVote(anyLong(), anyString())).thenReturn(1);
    }

    @Test
    void aVote_countsOnce_andTheQueueIsReadAgain() {
        assertThat(service.vote(session, PARTY, 7L)).isEqualTo(GuestVoteService.Result.COUNTED);
        assertThat(service.vote(session, PARTY, 7L)).as("the second 👍 on the same song").isEqualTo(GuestVoteService.Result.COUNTED);

        verify(repository, times(1)).addGuestVote(7L, PARTY);
        verify(djService, times(1)).refreshQueue(PARTY);
        assertThat(service.myVotes(session, PARTY)).containsExactly(7L);
    }

    @Test
    void votesOnSeveralSongs_eachCount() {
        service.vote(session, PARTY, 7L);
        service.vote(session, PARTY, 8L);

        assertThat(service.myVotes(session, PARTY)).containsExactlyInAnyOrder(7L, 8L);
        assertThat(service.myVotes(session, "OTHER")).as("per party").isEmpty();
        assertThat(service.myVotes(new MockHttpSession(), PARTY)).as("per guest").isEmpty();
    }

    @Test
    void aVoteOnASongThatNoLongerWaits_isGone_andNotRemembered() {
        when(repository.addGuestVote(7L, PARTY)).thenReturn(0);

        assertThat(service.vote(session, PARTY, 7L)).isEqualTo(GuestVoteService.Result.GONE);
        assertThat(service.myVotes(session, PARTY)).isEmpty();
        verify(djService, never()).refreshQueue(PARTY);
    }

    @Test
    void takingTheVoteBack_removesIt_once() {
        service.vote(session, PARTY, 7L);

        assertThat(service.takeBack(session, PARTY, 7L)).isEqualTo(GuestVoteService.Result.TAKEN_BACK);
        assertThat(service.takeBack(session, PARTY, 7L)).as("nothing left to take back").isEqualTo(GuestVoteService.Result.TAKEN_BACK);

        verify(repository, times(1)).removeGuestVote(7L, PARTY);
        assertThat(service.myVotes(session, PARTY)).isEmpty();
    }

    @Test
    void noVoteToTakeBack_changesNothing() {
        service.takeBack(session, PARTY, 7L);

        verify(repository, never()).removeGuestVote(anyLong(), anyString());
    }

    /** After a deploy the memory is empty: the session still knows the guest's 👍, so it is neither counted again nor lost. */
    @Test
    void theSession_outlivesARestart() {
        service.vote(session, PARTY, 7L);
        GuestVoteService afterRestart = new GuestVoteService(repository, djService);

        assertThat(afterRestart.myVotes(session, PARTY)).containsExactly(7L);
        afterRestart.vote(session, PARTY, 7L);
        verify(repository, times(1)).addGuestVote(7L, PARTY);
        afterRestart.takeBack(session, PARTY, 7L);
        verify(repository).removeGuestVote(7L, PARTY);
    }

    /** Two quick taps (or two tabs of one guest) at the same moment: one vote. */
    @Test
    void twoTapsAtTheSameMoment_countOnce() throws Exception {
        AtomicInteger counted = new AtomicInteger();
        when(repository.addGuestVote(7L, PARTY)).thenAnswer(invocation -> {
            Thread.sleep(50);   // the database's time: the second tap arrives meanwhile
            counted.incrementAndGet();
            return 1;
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                start.await();
                return service.vote(session, PARTY, 7L);
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(counted).hasValue(1);
        assertThat(service.myVotes(session, PARTY)).isEqualTo(Set.of(7L));
    }

    /** The session keeps the newest votes only: a bounded attribute. */
    @Test
    void theSessionKeepsTheNewestVotes() {
        for (long id = 1; id <= GuestVoteService.MY_VOTES_KEPT + 5; id++) {
            service.vote(session, PARTY, id);
        }

        @SuppressWarnings("unchecked")
        List<Long> kept = (List<Long>) session.getAttribute("myVotes_" + PARTY);
        assertThat(kept).hasSize(GuestVoteService.MY_VOTES_KEPT).startsWith(6L).endsWith((long) GuestVoteService.MY_VOTES_KEPT + 5);
    }
}
