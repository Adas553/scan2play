package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.service.SiteCounter.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site's counter (V40) on a real PostgreSQL: counts made at the same moment all land ({@code INSERT … ON CONFLICT … + 1}), the
 * day is Polish time, and the owner's page reads a bounded window. Each test counts on days of its own, long past, so the other
 * tests of the shared database (which count today) do not meet it.
 */
class SiteCounterIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private SiteCounter at(String instant) {
        return new SiteCounter(jdbc, Clock.fixed(Instant.parse(instant), com.scan2play.util.Times.DISPLAY_ZONE));
    }

    private int countOn(String day, Event event) {
        List<Integer> count = jdbc.queryForList("SELECT count FROM site_counter WHERE day = ?::date AND event = ?", Integer.class,
                day, event.name());
        return count.isEmpty() ? 0 : count.getFirst();
    }

    @Test
    void countsMadeAtTheSameMoment_allLand() throws Exception {
        SiteCounter counter = at("2001-01-10T12:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> counting = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                counting.add(pool.submit(() -> counter.count(Event.LANDING_PL)));
            }
            for (Future<?> one : counting) {
                one.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(countOn("2001-01-10", Event.LANDING_PL)).isEqualTo(200);
        assertThat(countOn("2001-01-10", Event.START)).isZero();
    }

    @Test
    void theDay_isPolishTime() {
        at("2001-02-10T23:30:00Z").count(Event.START);   // 00:30 on the 11th in Poland
        at("2001-02-10T22:30:00Z").count(Event.START);   // 23:30 on the 10th
        assertThat(countOn("2001-02-11", Event.START)).isEqualTo(1);
        assertThat(countOn("2001-02-10", Event.START)).isEqualTo(1);
    }

    @Test
    void theOwnersPage_readsTheLastDays_newestFirst_withZeros() {
        at("2001-03-01T10:00:00Z").count(Event.LOGIN);        // out of a 3-day window seen from the 10th
        at("2001-03-08T10:00:00Z").count(Event.NEW_DJ);
        at("2001-03-10T10:00:00Z").count(Event.GUEST_REQUEST);
        at("2001-03-10T11:00:00Z").count(Event.GUEST_REQUEST);

        List<SiteCounter.Day> days = new SiteCounter(jdbc, Clock.fixed(Instant.parse("2001-03-10T12:00:00Z"), ZoneOffset.UTC))
                .lastDays(3);

        assertThat(days).extracting(SiteCounter.Day::day).containsExactly(LocalDate.parse("2001-03-10"), LocalDate.parse("2001-03-08"));
        assertThat(days.getFirst().counts()).containsEntry(Event.GUEST_REQUEST, 2).containsEntry(Event.LOGIN, 0).hasSize(Event.values().length);
        assertThat(days.get(1).counts()).containsEntry(Event.NEW_DJ, 1);
    }
}
