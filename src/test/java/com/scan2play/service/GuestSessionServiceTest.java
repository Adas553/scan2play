package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * {@code cooldownMinutes}), checked and counted in one step before the evaluation.
 */
class GuestSessionServiceTest {

    private GuestSessionService service;
    private MockHttpSession session;
    private PartySettingsEntity settings;

    private static final String PARTY_CODE = "ABC12";

    @BeforeEach
    void setUp() {
        service = new GuestSessionService();
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

    private void givenEarlierRequests(Instant... times) {
        service.countEarlierRequests(session.getId(), PARTY_CODE, List.of(times));
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

        assertThat(third).isPresent();
        assertThat(third.get()).isPositive().isLessThanOrEqualTo(180);
        assertThat(recorded()).as("a refused request is not counted").hasSize(2);
    }

    @Test
    void tryAcquire_refuses_whenTheLimitIsReachedWithinTheCooldown() {
        givenEarlierRequests(Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().minus(30, ChronoUnit.SECONDS));

        Optional<Long> result = service.tryAcquire(session, PARTY_CODE, settings);

        assertThat(result).isPresent();
        assertThat(result.get()).isPositive();
    }

    @Test
    void tryAcquire_forgetsRequestsOlderThanTheCooldown() {
        givenEarlierRequests(Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now().minus(5, ChronoUnit.MINUTES));

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();

        assertThat(recorded()).hasSize(1);
    }

    /**
     * The wait runs from the guest's LAST request (the owner, 2026-10-01): two songs at 0:00 and 1:00, a third tried at 1:03 waits
     * until 4:00 — about 177 s — not until 3:00, when the first one would leave a sliding window (117 s, what the guest was told).
     */
    @Test
    void tryAcquire_theWaitRunsFromTheLastRequest() {
        givenEarlierRequests(Instant.now().minusSeconds(63), Instant.now().minusSeconds(3));

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).hasValueSatisfying(wait -> assertThat(wait).isBetween(175L, 180L));
    }

    /** …so 100 s after the last request the guest still waits, even when the first one is older than the cooldown. */
    @Test
    void tryAcquire_aRequestBeforeTheCooldownAfterTheLastOne_stillWaits() {
        givenEarlierRequests(Instant.now().minusSeconds(200), Instant.now().minusSeconds(100));

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).hasValueSatisfying(wait -> assertThat(wait).isBetween(78L, 80L));
    }

    /** Once the cooldown after the last request has passed, the whole limit is there again. */
    @Test
    void tryAcquire_afterTheCooldownFromTheLastRequest_theWholeLimitIsBack() {
        givenEarlierRequests(Instant.now().minusSeconds(400), Instant.now().minusSeconds(181));

        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).isEmpty();
        assertThat(service.tryAcquire(session, PARTY_CODE, settings)).as("the third of the new round").isPresent();
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
