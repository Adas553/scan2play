package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GuestSessionService} rate limiting logic: the per-guest limit the DJ sets ({@code requestLimit} per
 * {@code cooldownMinutes}), checked and counted in one step before the evaluation. The time is a clock the test moves, so a wait
 * is checked to the second, not within a few seconds of the machine's own clock.
 */
class GuestSessionServiceTest {

    private TestClock clock;
    private GuestSessionService service;
    private MockHttpSession session;
    private PartySettingsEntity settings;

    private static final String PARTY_CODE = "ABC12";

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        service = new GuestSessionService(clock);
        session = new MockHttpSession();
        settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .requestLimit(2)
                .cooldownMinutes(3)
                .build();
    }

    private List<Instant> recorded() {
        return service.countedRequests(session.getId(), PARTY_CODE);
    }

    /** Earlier requests of the guest, that many seconds ago. */
    private void givenEarlierRequests(long... secondsAgo) {
        List<Instant> times = new ArrayList<>();
        for (long seconds : secondsAgo) {
            times.add(clock.instant().minusSeconds(seconds));
        }
        service.countEarlierRequests(session.getId(), PARTY_CODE, times);
    }

    @Test
    void tryAcquire_letsTheFirstRequestThrough_andCountsIt() {
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();

        assertThat(recorded()).hasSize(1);
    }

    @Test
    void tryAcquire_countsUpToTheLimit_thenRefusesWithTheWaitTime() {
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();

        Optional<Long> third = service.tryAcquire(session, PARTY_CODE, settings);

        assertThat(third).as("the whole cooldown from the second one").contains(180L);
        assertThat(recorded()).as("a refused request is not counted").hasSize(2);
    }

    @Test
    void tryAcquire_refuses_whenTheLimitIsReachedWithinTheCooldown() {
        givenEarlierRequests(60, 30);

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).contains(150L);
    }

    @Test
    void tryAcquire_forgetsRequestsOlderThanTheCooldown() {
        givenEarlierRequests(600, 300);

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();

        assertThat(recorded()).hasSize(1);
    }

    /**
     * The wait runs from the guest's LAST request (the owner, 2026-10-01): two songs at 0:00 and 1:00, a third tried at 1:03 waits
     * until 4:00 — 177 s — not until 3:00, when the first one would leave a sliding window (117 s, what the guest was told).
     */
    @Test
    void tryAcquire_theWaitRunsFromTheLastRequest() {
        givenEarlierRequests(63, 3);

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).contains(177L);
    }

    /** …so 100 s after the last request the guest still waits, even when the first one is older than the cooldown. */
    @Test
    void tryAcquire_aRequestBeforeTheCooldownAfterTheLastOne_stillWaits() {
        givenEarlierRequests(200, 100);

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).contains(80L);
    }

    /** Once the cooldown after the last request has passed, the whole limit is there again. */
    @Test
    void tryAcquire_afterTheCooldownFromTheLastRequest_theWholeLimitIsBack() {
        givenEarlierRequests(400, 181);

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).as("the third of the new round").isPresent();
    }

    /** The guest who waits the time they were told gets in: one second short they wait that second, then the limit is back. */
    @Test
    void tryAcquire_theToldWaitIsExactlyEnough() {
        service.tryAcquire(session, PARTY_CODE, settings);
        service.tryAcquire(session, PARTY_CODE, settings);
        long wait = service.tryAcquire(session, PARTY_CODE, settings).orElseThrow();

        clock.advance(Duration.ofSeconds(wait - 1));
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).contains(1L);

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(recorded()).as("a new round: only this request").containsExactly(clock.instant());
    }

    /** A request that came to nothing (a mood sent back to the form, the AI not answering) gives its place back. */
    @Test
    void giveBack_returnsTheNewestCountedRequest() {
        service.tryAcquire(session, PARTY_CODE, settings);
        service.tryAcquire(session, PARTY_CODE, settings);

        service.giveBack(session, PARTY_CODE);

        assertThat(recorded()).hasSize(1);
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
    }

    @Test
    void tryAcquire_keepsASeparateCountPerParty() {
        service.tryAcquire(session, PARTY_CODE, settings);
        service.tryAcquire(session, PARTY_CODE, settings);

        assertThat(service.tryAcquire(session, "OTHER", settings)).isEmpty();
    }

    @Test
    void theGuestsOwnRequests_areRemembered_perParty_andOnlyTheNewestOnes() {
        assertThat(service.myRequestIds(session, PARTY_CODE)).isEmpty();

        service.rememberRequest(session, PARTY_CODE, 7L);
        service.rememberRequest(session, PARTY_CODE, null); // nothing saved: nothing remembered
        service.rememberRequest(session, "OTHER", 8L);
        assertThat(service.myRequestIds(session, PARTY_CODE)).containsExactly(7L);

        for (long id = 100; id < 100 + GuestSessionService.MY_REQUESTS_KEPT; id++) {
            service.rememberRequest(session, PARTY_CODE, id);
        }
        assertThat(service.myRequestIds(session, PARTY_CODE)).hasSize(GuestSessionService.MY_REQUESTS_KEPT).doesNotContain(7L);
    }

    /**
     * The bug of review item 4.1: the limit was checked before and recorded after the 2–4 s evaluation, so requests sent in
     * parallel from one session all passed. Now only {@code requestLimit} of them do.
     */
    @Test
    void tryAcquire_letsOnlyTheLimitThrough_whenRequestsOfOneSessionComeInParallel() throws Exception {
        int requests = 16;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<Long>>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.tryAcquire(session, PARTY_CODE, settings);
                }));
            }
            start.countDown();

            int passed = 0;
            for (Future<Optional<Long>> result : results) {
                if (result.get().isEmpty()) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(2);
            assertThat(recorded()).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Review 2.3: with the sessions in the database (Spring Session JDBC) every request works on its own copy of the session, read
     * at its start and written back at its end. The count kept in the session let all 16 through; counted by the session's id, 2.
     */
    @Test
    void tryAcquire_letsOnlyTheLimitThrough_whenEachRequestHasItsOwnCopyOfTheSession() throws Exception {
        int requests = 16;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<Long>>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                MockHttpSession copy = new MockHttpSession(null, session.getId());
                results.add(pool.submit(() -> {
                    start.await();
                    return service.tryAcquire(copy, PARTY_CODE, settings);
                }));
            }
            start.countDown();

            int passed = 0;
            for (Future<Optional<Long>> result : results) {
                if (result.get().isEmpty()) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
