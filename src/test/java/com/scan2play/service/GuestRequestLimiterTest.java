package com.scan2play.service;

import com.scan2play.service.GuestRequestLimiter.Refusal;
import com.scan2play.service.GuestRequestLimiter.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server-side limits of guest requests (review item 4.1): per client address and party, and per party, counted before
 * the evaluation whatever the session cookie says.
 */
class GuestRequestLimiterTest {

    private static final String PARTY = "ABC12";
    private static final String IP = "203.0.113.7";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T20:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
    }

    /** 3 requests per 10 minutes per address and party, 5 per day per party. */
    private GuestRequestLimiter limiter() {
        return new GuestRequestLimiter(3, 10, 5, "", clock);
    }

    @Test
    void oneAddress_isRefusedPastItsLimit_withTheSecondsLeftInTheWindow() {
        GuestRequestLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(IP, PARTY)).isEmpty();
        }
        clock.advance(Duration.ofMinutes(4));

        Optional<Refusal> refusal = limiter.tryAcquire(IP, PARTY);

        assertThat(refusal).contains(new Refusal(Scope.CLIENT, 360));
    }

    @Test
    void oneAddress_mayAskAgain_onceItsWindowHasEnded() {
        GuestRequestLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(IP, PARTY);
        }
        clock.advance(Duration.ofMinutes(10));

        assertThat(limiter.tryAcquire(IP, PARTY)).isEmpty();
    }

    @Test
    void theAddressLimit_isPerParty_andPerAddress() {
        GuestRequestLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(IP, PARTY);
        }

        assertThat(limiter.tryAcquire(IP, "OTHER")).isEmpty();
        assertThat(limiter.tryAcquire("198.51.100.1", PARTY)).isEmpty();
    }

    @Test
    void theParty_isRefusedPastItsDailyLimit_whateverTheAddress() {
        GuestRequestLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("198.51.100." + i, PARTY)).isEmpty();
        }

        assertThat(limiter.tryAcquire("198.51.100.99", PARTY)).map(Refusal::scope).contains(Scope.PARTY);
        assertThat(limiter.tryAcquire("198.51.100.99", "OTHER")).as("another party is not affected").isEmpty();

        clock.advance(Duration.ofHours(24));
        assertThat(limiter.tryAcquire("198.51.100.99", PARTY)).isEmpty();
    }

    @Test
    void aRequestRefusedByItsAddress_doesNotCountAgainstTheParty() {
        GuestRequestLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(IP, PARTY); // 3 pass, 7 refused by the address limit
        }

        // the party has used 3 of its 5
        assertThat(limiter.tryAcquire("198.51.100.1", PARTY)).isEmpty();
        assertThat(limiter.tryAcquire("198.51.100.2", PARTY)).isEmpty();
        assertThat(limiter.tryAcquire("198.51.100.3", PARTY)).map(Refusal::scope).contains(Scope.PARTY);
    }

    @Test
    void theDashboardReadsThePartysUse_withoutTakingAnything() {
        GuestRequestLimiter limiter = limiter();
        assertThat(limiter.partyRequestsUsed(PARTY)).isZero();
        assertThat(limiter.isPartyLimitReached(PARTY)).isFalse();

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("198.51.100." + i, PARTY);
        }
        limiter.tryAcquire("198.51.100.99", PARTY); // refused: the count stays at the limit
        assertThat(limiter.partyRequestsUsed(PARTY)).isEqualTo(5);
        assertThat(limiter.isPartyLimitReached(PARTY)).isTrue();
        assertThat(limiter.partyRequestsUsed(PARTY)).as("reading takes nothing").isEqualTo(5);

        clock.advance(Duration.ofHours(24));
        assertThat(limiter.partyRequestsUsed(PARTY)).isZero();
        assertThat(limiter.isPartyLimitReached(PARTY)).isFalse();
    }

    @Test
    void theBusiestNetwork_isTheAddressWithTheMostRequestsInAnOpenWindow_atThisParty() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(3, 10, 0, "", clock);
        assertThat(limiter.busiestClientRequestsUsed(PARTY)).isZero();

        limiter.tryAcquire("198.51.100.1", PARTY);
        limiter.tryAcquire(IP, PARTY);
        limiter.tryAcquire(IP, PARTY);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("198.51.100.2", "OTHER"); // another party does not count
        }
        assertThat(limiter.busiestClientRequestsUsed(PARTY)).isEqualTo(2);

        limiter.tryAcquire(IP, PARTY);
        limiter.tryAcquire(IP, PARTY); // refused: shown as the limit, not more
        assertThat(limiter.busiestClientRequestsUsed(PARTY)).isEqualTo(3);

        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.busiestClientRequestsUsed(PARTY)).as("the windows have ended").isZero();
    }

    @Test
    void aLimitOfZero_isOff() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(0, 10, 0, "", clock);

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire(IP, PARTY)).isEmpty();
        }
    }

    @Test
    void requestsInParallel_passOnlyUpToTheLimit() throws Exception {
        GuestRequestLimiter limiter = new GuestRequestLimiter(3, 10, 0, "", clock);
        int requests = 32;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<Refusal>>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return limiter.tryAcquire(IP, PARTY);
                }));
            }
            start.countDown();

            int passed = 0;
            for (Future<Optional<Refusal>> result : results) {
                if (result.get().isEmpty()) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clientIp_isTheRemoteAddress_whenNoHeaderIsConfigured() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(IP);
        request.addHeader("CF-Connecting-IP", "198.51.100.1");

        assertThat(limiter().clientIp(request)).isEqualTo(IP);
    }

    @Test
    void clientIp_isTheConfiguredHeader_whenPresent_andTheRemoteAddressOtherwise() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(3, 10, 5, "CF-Connecting-IP", clock);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(limiter.clientIp(request)).isEqualTo("10.0.0.1");

        request.addHeader("CF-Connecting-IP", " 198.51.100.1 ");
        assertThat(limiter.clientIp(request)).isEqualTo("198.51.100.1");
    }
}
