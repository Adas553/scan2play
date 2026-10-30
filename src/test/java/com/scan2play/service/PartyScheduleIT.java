package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job that opens and closes the parties whose planned moment has come (V55), its one statement on a real PostgreSQL
 * ({@link PartySettingsCommandService#applyDueSchedule}): a due open opens, a due close closes, both due leave the party as the later
 * one did, a moment still to come stays — and the cached settings follow. Two runs at once (two instances) apply a moment once; a
 * row a DJ holds is skipped and taken at the next run. Each test uses its own moments, far from the others', and its own parties.
 */
class PartyScheduleIT extends PostgresIntegrationTest {

    @Autowired PartySettingsCommandService commands;
    @Autowired PartySettingsQueryService queries;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private String party(boolean active, Instant openAt, Instant closeAt) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("schedule-" + System.nanoTime())
                .active(active).nextOpenAt(openAt).nextCloseAt(closeAt).build()).getPartyCode();
    }

    private Map<String, Object> row(String party) {
        return jdbc.queryForMap("SELECT active, next_open_at, next_close_at FROM party_settings WHERE party_code = ?", party);
    }

    private static Timestamp at(Instant moment) {
        return Timestamp.from(moment);
    }

    /** The parties among {@code mine} the run changed, as "CODE:true". */
    private static List<String> mine(List<PartySettingsCommandService.ScheduleChange> changes, String... mine) {
        List<String> codes = List.of(mine);
        return changes.stream().filter(c -> codes.contains(c.partyCode())).map(c -> c.partyCode() + ":" + c.active()).sorted().toList();
    }

    @Test
    void theDueMoments_openAndClose_theOthersWait() {
        // far ahead: the app's own job (every minute, by the real clock) never finds these parties due
        Instant now = Instant.parse("2101-06-01T20:00:00Z");
        Instant past = now.minusSeconds(60);
        Instant later = now.plusSeconds(3600);
        String opens = party(false, past, later);            // opens now, closes later
        String closes = party(true, later, past);            // closes now, opens later
        String bothOpenLast = party(false, past, past.minusSeconds(60));   // closed then opened: open
        String bothCloseLast = party(true, past.minusSeconds(60), past);   // opened then closed: closed
        String waits = party(true, null, later);
        String exactlyNow = party(false, now, null);

        List<PartySettingsCommandService.ScheduleChange> changes = commands.applyDueSchedule(now, 500);

        assertThat(mine(changes, opens, closes, bothOpenLast, bothCloseLast, waits, exactlyNow)).containsExactlyInAnyOrder(
                opens + ":true", closes + ":false", bothOpenLast + ":true", bothCloseLast + ":false", exactlyNow + ":true");
        assertThat(row(opens)).containsEntry("active", true).containsEntry("next_open_at", null).containsEntry("next_close_at", at(later));
        assertThat(row(closes)).containsEntry("active", false).containsEntry("next_close_at", null).containsEntry("next_open_at", at(later));
        assertThat(row(bothOpenLast)).containsEntry("active", true).containsEntry("next_open_at", null).containsEntry("next_close_at", null);
        assertThat(row(bothCloseLast)).containsEntry("active", false).containsEntry("next_open_at", null).containsEntry("next_close_at", null);
        assertThat(row(waits)).containsEntry("active", true).containsEntry("next_close_at", at(later));

        assertThat(mine(commands.applyDueSchedule(now, 500), opens, closes, bothOpenLast, bothCloseLast, waits, exactlyNow))
                .as("a second run: nothing is due any more").isEmpty();
    }

    /** The party's settings are cached: the guests' page reads the new state at once, not the one cached before the run. */
    @Test
    void theCachedSettings_followTheRun() {
        Instant now = Instant.parse("2102-06-01T20:00:00Z");
        String party = party(false, now.minusSeconds(60), null);
        assertThat(queries.getSettings(party).isActive()).isFalse();   // cached closed

        commands.applyDueSchedule(now, 500);

        assertThat(queries.getSettings(party).isActive()).isTrue();
        assertThat(queries.getSettings(party).getNextOpenAt()).isNull();
    }

    /** At most {@code limit} a run; the rest at the next one. */
    @Test
    void aRun_isBounded() {
        Instant now = Instant.parse("2103-06-01T20:00:00Z");
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            codes.add(party(false, now.minusSeconds(60), null));
        }
        int first = mine(commands.applyDueSchedule(now, 3), codes.toArray(String[]::new)).size();
        int second = mine(commands.applyDueSchedule(now, 500), codes.toArray(String[]::new)).size();
        assertThat(first).isLessThanOrEqualTo(3);
        assertThat(first + second).isEqualTo(5);
    }

    /**
     * Two instances run the job at the same minute: each party changes once — the second run finds nothing due. One run holds the
     * rows (its transaction open) while the other starts; the other skips them, and once the first commits nothing is due any more.
     */
    @Test
    void twoRunsAtOnce_applyAMomentOnce() throws Exception {
        Instant now = Instant.parse("2104-06-01T20:00:00Z");
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(party(i % 2 == 0, i % 2 == 0 ? null : now.minusSeconds(60), i % 2 == 0 ? now.minusSeconds(60) : null));
        }
        String[] mineOnly = codes.toArray(String[]::new);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        CountDownLatch firstRan = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        try {
            Future<List<PartySettingsCommandService.ScheduleChange>> first = pool.submit(() ->
                    new TransactionTemplate(transactions).execute(status -> {
                        List<PartySettingsCommandService.ScheduleChange> changes = commands.applyDueSchedule(now, 500);
                        firstRan.countDown();
                        await(secondDone);   // the rows held until the other run is over
                        return changes;
                    }));
            assertThat(firstRan.await(30, TimeUnit.SECONDS)).isTrue();
            List<PartySettingsCommandService.ScheduleChange> second = commands.applyDueSchedule(now, 500);
            secondDone.countDown();
            assertThat(mine(second, mineOnly)).as("the held rows skipped, not waited for").isEmpty();
            assertThat(mine(first.get(30, TimeUnit.SECONDS), mineOnly)).hasSize(4);
        } finally {
            secondDone.countDown();
            pool.shutdownNow();
        }
        assertThat(mine(commands.applyDueSchedule(now, 500), mineOnly)).as("after both: nothing due").isEmpty();
        for (int i = 0; i < 4; i++) {
            assertThat(row(codes.get(i))).containsEntry("active", i % 2 == 1)
                    .containsEntry("next_open_at", null).containsEntry("next_close_at", null);
        }
    }

    /**
     * The DJ presses "Zakończ" just as the planned close is due: the run meanwhile skips the row the DJ holds (it does not wait for
     * it), and the next run finds nothing due — the DJ's write took the close away. The party is closed once.
     */
    @Test
    void aDjsWrite_andTheRun_takeTurns() throws Exception {
        Instant now = Instant.parse("2105-06-01T20:00:00Z");
        String party = party(true, null, now.minusSeconds(60));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            // the DJ ends the party while holding the row; the run skips it meanwhile
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> dj = pool.submit(() -> commands.updateSettings(party, s -> {
                s.setActive(false);
                s.setNextCloseAt(null);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(mine(commands.applyDueSchedule(now, 500), party)).as("held by the DJ: skipped").isEmpty();
            release.countDown();
            dj.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(mine(commands.applyDueSchedule(now, 500), party)).as("the DJ closed it: nothing due").isEmpty();
        assertThat(row(party)).containsEntry("active", false).containsEntry("next_close_at", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
