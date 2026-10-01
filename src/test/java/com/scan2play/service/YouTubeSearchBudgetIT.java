package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The count of the YouTube searches in the database (V10): it survives a restart, and concurrent requests never take more than the
 * budget. Each test is on a day of its own, far from today, so it never meets the count of the application's own fuse.
 */
class YouTubeSearchBudgetIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void aRestartDoesNotGiveTheDaysBudgetBack() {
        Clock day = aDayOfItsOwn();
        YouTubeSearchBudget before = budget(3, day);
        assertThat(before.tryAcquire()).isTrue();
        assertThat(before.tryAcquire()).isTrue();

        YouTubeSearchBudget afterRestart = budget(3, day);

        assertThat(afterRestart.isSpent()).isFalse();
        assertThat(afterRestart.tryAcquire()).isTrue();
        assertThat(afterRestart.tryAcquire()).isFalse();
        assertThat(afterRestart.isSpent()).isTrue();
        assertThat(budget(3, day).isSpent()).as("a third start").isTrue();
    }

    @Test
    void quotaExceededSurvivesARestartToo() {
        Clock day = aDayOfItsOwn();
        budget(80, day).markQuotaExceeded();

        YouTubeSearchBudget afterRestart = budget(80, day);

        assertThat(afterRestart.isSpent()).isTrue();
        assertThat(afterRestart.tryAcquire()).isFalse();
    }

    @Test
    void concurrentRequestsTakeExactlyTheBudget() throws Exception {
        Clock day = aDayOfItsOwn();
        List<YouTubeSearchBudget> instances = List.of(budget(5, day), budget(5, day), budget(5, day));   // three "instances"
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                YouTubeSearchBudget instance = instances.get(i % instances.size());
                tries.add(instance::tryAcquire);
            }
            int granted = 0;
            for (Future<Boolean> result : pool.invokeAll(tries)) {
                granted += result.get() ? 1 : 0;
            }
            assertThat(granted).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private YouTubeSearchBudget budget(int dailyBudget, Clock clock) {
        return new YouTubeSearchBudget(dailyBudget, clock, new YouTubeSearchBudget.DatabaseCounter(jdbc));
    }

    /** Noon UTC of a random day in the 2090s: a Google day no other test (nor the application) counts on. */
    private static Clock aDayOfItsOwn() {
        Instant noon = Instant.parse("2090-01-01T12:00:00Z").plusSeconds(86_400L * ThreadLocalRandom.current().nextInt(3650));
        return Clock.fixed(noon, ZoneOffset.UTC);
    }
}
