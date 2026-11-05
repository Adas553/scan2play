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
