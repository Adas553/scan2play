package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guests' limits kept across a restart (V71) on a real PostgreSQL: what one instance counted is what the next one starts
 * from, a day started again ("Odblokuj prośby gości") is gone from the table too, and no address is ever written.
 */
class GuestLimitStoreIT extends PostgresIntegrationTest {

    private static final String PARTY = "LIM71";
    private static final String IP = "203.0.113.71";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final TestClock clock = new TestClock();

    private GuestRequestLimiter limiter() {
        return new GuestRequestLimiter(60, 10, 300, "", clock);
    }

    private FreeAiLimit freeAi() {
        return new FreeAiLimit(150, Plans.enforced(), clock);
    }

    private static PartySettingsEntity party() {
        PartySettingsEntity party = new PartySettingsEntity();
        party.setPartyCode(PARTY);
        party.setOwnerId("free-dj-71");
        return party;
    }

    @Test
    void theNextInstance_goesOnFromTheCountsOfTheLast() {
        GuestRequestLimiter before = limiter();
        FreeAiLimit freeBefore = freeAi();
        for (int i = 0; i < 7; i++) {
            before.tryAcquire(IP, PARTY);
            freeBefore.tryAcquire(party());
        }
        new GuestLimitStore(before, freeBefore, jdbc, transactions).save();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_limit_count WHERE key LIKE ? OR key LIKE ?", Integer.class,
                "%203.0.113%", "%" + IP + "%")).as("no address in the table").isZero();

        clock.advance(Duration.ofHours(2));   // the restart, later the same evening
        GuestRequestLimiter after = limiter();
        FreeAiLimit freeAfter = freeAi();
        GuestLimitStore store = new GuestLimitStore(after, freeAfter, jdbc, transactions);
        store.atStart();

        assertThat(after.partyRequestsUsed(PARTY)).as("the party's day goes on").isEqualTo(7);
        assertThat(freeAfter.today(party()).asked()).as("the Free plan's checks of the evening too").isEqualTo(7);
        assertThat(after.busiestClientRequestsUsed(PARTY)).as("the address windows start again: never stored").isZero();

        // the day's window is the one that started before the restart, not a new one
        clock.advance(Duration.ofHours(22).plusMinutes(1));
        assertThat(after.partyRequestsUsed(PARTY)).as("24 hours after its first request the day has ended").isZero();

        // "Odblokuj prośby gości": the day gone from memory is gone from the table with the next write
        after.tryAcquire(IP, PARTY);
        store.save();
        assertThat(rowsOfTheParty()).isEqualTo(1);
        after.resetParty(PARTY);
        store.save();
        assertThat(rowsOfTheParty()).isZero();
    }

    private int rowsOfTheParty() {
        return jdbc.queryForObject("SELECT count(*) FROM guest_limit_count WHERE kind = 'PARTY_DAY' AND key = ?", Integer.class,
                PARTY);
    }
}
