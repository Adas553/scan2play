package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The Free plan's AI checks an evening ({@code guest.limit.free-ai-per-party}). */
class FreeAiLimitTest {

    /** A clock to move: Warsaw is UTC+1 in November, so 05:00 UTC is 6:00 there — the evening's start. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T19:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static PartySettingsEntity party(String owner) {
        PartySettingsEntity party = new PartySettingsEntity();
        party.setPartyCode("ABCDE");
        party.setOwnerId(owner);
        return party;
    }

    @Test
    @DisplayName("a Free organiser: the limit's checks go to the AI, the next ones not; the use says how many asked and were checked")
    void free_isLimited() {
        FreeAiLimit limit = new FreeAiLimit(3, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");

        assertThat(new boolean[]{limit.tryAcquire(party), limit.tryAcquire(party), limit.tryAcquire(party), limit.tryAcquire(party),
                limit.tryAcquire(party)}).containsExactly(true, true, true, false, false);
        FreeAiLimit.Use use = limit.today(party);
        assertThat(new int[]{use.asked(), use.checked(), use.unchecked(), use.limit()}).containsExactly(5, 3, 2, 3);
        assertThat(use.reached()).isTrue();
    }

    @Test
    @DisplayName("past midnight it is the same evening; from 6:00 Polish time a new one, and the last one is yesterday's")
    void theEvening_endsAtSixInTheMorning() {
        FreeAiLimit limit = new FreeAiLimit(1, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");
        assertThat(limit.tryAcquire(party)).isTrue();

        now.set(Instant.parse("2026-11-08T01:30:00Z"));   // 02:30 in Warsaw: still Saturday's evening
        assertThat(limit.tryAcquire(party)).isFalse();

        now.set(Instant.parse("2026-11-08T05:00:00Z"));   // 06:00 in Warsaw: Sunday's
        assertThat(limit.today(party).asked()).isZero();
        assertThat(limit.yesterday(party).asked()).isEqualTo(2);
        assertThat(limit.yesterday(party).reached()).isTrue();
        assertThat(limit.tryAcquire(party)).isTrue();
    }

    @Test
    @DisplayName("no limit with Pro, while the plans are not enforced, or switched off (0)")
    void noLimit_withPro_plansOff_orZero() {
        PartySettingsEntity party = party("dj");
        for (FreeAiLimit limit : new FreeAiLimit[]{new FreeAiLimit(1, Plans.enforced("dj"), clock),
                new FreeAiLimit(1, Plans.off(), clock), new FreeAiLimit(0, Plans.enforced(), clock), FreeAiLimit.off()}) {
            assertThat(limit.tryAcquire(party)).isTrue();
            assertThat(limit.tryAcquire(party)).isTrue();
            assertThat(limit.today(party).limit()).isZero();
            assertThat(limit.today(party).reached()).isFalse();
        }
    }

    @Test
    @DisplayName("at exactly the limit: the last one judged, the limit reached, nothing unchecked yet")
    void atTheLimit_reached_nothingUnchecked() {
        FreeAiLimit limit = new FreeAiLimit(2, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");
        assertThat(limit.tryAcquire(party)).isTrue();
        assertThat(limit.today(party).reached()).isFalse();
        assertThat(limit.tryAcquire(party)).isTrue();
        assertThat(limit.today(party).reached()).isTrue();
        assertThat(limit.today(party).unchecked()).isZero();
    }

    @Test
    @DisplayName("a call that failed is given back: the Free plan's checks are the AI's answers; never below zero")
    void aFailedCall_isGivenBack() {
        FreeAiLimit limit = new FreeAiLimit(1, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");
        assertThat(limit.tryAcquire(party)).isTrue();
        limit.giveBack(party);
        limit.giveBack(party);
        assertThat(limit.today(party).asked()).isZero();
        assertThat(limit.tryAcquire(party)).as("the check is there again").isTrue();
        assertThat(limit.tryAcquire(party)).isFalse();
    }

    @Test
    @DisplayName("yesterday's count is kept through the next evening (the organiser's note), and gone the evening after")
    void yesterday_isKeptThroughTheNextEvening() {
        FreeAiLimit limit = new FreeAiLimit(1, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");
        now.set(Instant.parse("2026-11-07T05:00:00Z"));   // 6:00 in Warsaw: the evening's first request, as early as it can be
        limit.tryAcquire(party);
        limit.tryAcquire(party);
        now.set(Instant.parse("2026-11-09T04:59:00Z"));   // 5:59 two days on: still the next evening
        assertThat(limit.yesterday(party).asked()).isEqualTo(2);
        now.set(Instant.parse("2026-11-09T05:00:00Z"));
        assertThat(limit.yesterday(party).asked()).as("a new evening: the day before yesterday is gone").isZero();
    }

    @Test
    @DisplayName("concurrent requests: exactly the limit's number goes to the AI")
    void concurrentRequests_exactlyTheLimit() throws InterruptedException {
        FreeAiLimit limit = new FreeAiLimit(150, Plans.enforced(), clock);
        PartySettingsEntity party = party("dj");
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            pool.submit(() -> {
                start.await();
                if (limit.tryAcquire(party)) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(150);
        assertThat(limit.today(party).asked()).isEqualTo(400);
    }

    @Test
    @DisplayName("each party counts its own evening")
    void eachParty_countsItsOwn() {
        FreeAiLimit limit = new FreeAiLimit(1, Plans.enforced(), clock);
        PartySettingsEntity one = party("dj");
        PartySettingsEntity two = party("dj");
        two.setPartyCode("FGHIJ");
        assertThat(limit.tryAcquire(one)).isTrue();
        assertThat(limit.tryAcquire(two)).isTrue();
        assertThat(limit.tryAcquire(one)).isFalse();
    }
}
