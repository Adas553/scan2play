package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily fuse of the YouTube search limit (review item 4.1): a budget per Google day (midnight Pacific time) and a trip on
 * {@code quotaExceeded}.
 */
class YouTubeSearchBudgetTest {

    /** A clock the test moves by hand; 2026-09-30 18:00 UTC is 11:00 in Los Angeles (PDT, UTC-7). */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T18:00:00Z");

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

    private final TestClock clock = new TestClock();

    @Test
    void allowsTheBudget_thenRefusesUntilMidnightPacificTime() {
        YouTubeSearchBudget budget = new YouTubeSearchBudget(3, clock);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();

        clock.advance(Duration.ofHours(12)); // 06:00 UTC on Oct 1, still 23:00 of Sep 30 in Los Angeles
        assertThat(budget.tryAcquire()).isFalse();

        clock.advance(Duration.ofHours(1)); // midnight in Los Angeles
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void quotaExceeded_stopsTheSearches_untilTheNextPacificDay() {
        YouTubeSearchBudget budget = new YouTubeSearchBudget(80, clock);
        assertThat(budget.tryAcquire()).isTrue();

        budget.markQuotaExceeded();

        assertThat(budget.tryAcquire()).isFalse();
        clock.advance(Duration.ofHours(13));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void isSpent_readsWithoutTakingASearch_andFollowsTheDay() {
        YouTubeSearchBudget budget = new YouTubeSearchBudget(1, clock);
        assertThat(budget.isSpent()).isFalse();
        assertThat(budget.isSpent()).isFalse();

        assertThat(budget.tryAcquire()).as("the reads took nothing").isTrue();
        assertThat(budget.isSpent()).isTrue();

        clock.advance(Duration.ofHours(13));
        assertThat(budget.isSpent()).isFalse();
    }

    @Test
    void aBudgetOfZero_isOff_butQuotaExceededStillTrips() {
        YouTubeSearchBudget budget = new YouTubeSearchBudget(0, clock);
        for (int i = 0; i < 500; i++) {
            assertThat(budget.tryAcquire()).isTrue();
        }

        budget.markQuotaExceeded();

        assertThat(budget.tryAcquire()).isFalse();
    }
}
