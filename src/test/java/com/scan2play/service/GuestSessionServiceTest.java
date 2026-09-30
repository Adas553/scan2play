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
    private static final String SESSION_KEY = "requests_" + PARTY_CODE;

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

    @SuppressWarnings("unchecked")
    private List<Instant> recorded() {
        return (List<Instant>) session.getAttribute(SESSION_KEY);
    }

    private void givenEarlierRequests(Instant... times) {
        session.setAttribute(SESSION_KEY, new ArrayList<>(List.of(times)));
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

    @Test
    void tryAcquire_keepsASeparateCountPerParty() {
        service.tryAcquire(session, PARTY_CODE, settings);
        service.tryAcquire(session, PARTY_CODE, settings);

        assertThat(service.tryAcquire(session, "OTHER", settings)).isEmpty();
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
}
