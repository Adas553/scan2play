package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Życzenia przed imprezą" (V68) on a real PostgreSQL: a wish counts once under guests writing at once — the song's row and the
 * event's count —, the event's ceiling holds under them too, the same song typed two ways is one row, a closed or past event takes
 * nothing, a party has at most five open events, the events go with the party and follow its new code, and the purge deletes them a
 * week after their day.
 */
class WishServiceIT extends PostgresIntegrationTest {

    @Autowired WishService wishes;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private String party() {
        String code = "W" + ThreadLocalRandom.current().nextInt(1000, 10000);
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("wish-" + code).active(true).build());
        return code;
    }

    private WishService.Event event(String party) {
        return wishes.create(party, "Wesele Ani i Kuby", WishService.today().plusDays(30)).orElseThrow();
    }

    @Test
    void aWish_isCounted_andTheSameSongTypedTwoWays_isOneRow() {
        WishService.Event event = event(party());
        assertThat(wishes.add(event.token(), "Wilki – Baśka")).isEqualTo(WishService.Added.ADDED);
        assertThat(wishes.add(event.token(), "wilki - baska (Radio Edit)")).isEqualTo(WishService.Added.ADDED);
        assertThat(wishes.add(event.token(), "Daft Punk - One More Time")).isEqualTo(WishService.Added.ADDED);
        assertThat(wishes.add(event.token(), "coś od zenka")).isEqualTo(WishService.Added.ADDED);
        assertThat(wishes.add(event.token(), "   ")).isEqualTo(WishService.Added.EMPTY);

        assertThat(wishes.wishes(event.id())).containsExactly(
                new WishService.Wish("Wilki - Baśka", 2, true),
                new WishService.Wish("Daft Punk - One More Time", 1, true),
                new WishService.Wish("coś od zenka", 1, false));
        assertThat(wishes.songs(event.id())).isEqualTo(3);
        assertThat(wishes.event(event.partyCode(), event.id()).orElseThrow().wishCount()).as("every wish, the empty one not").isEqualTo(4);
    }

    /** Fifty guests writing the same song at the same moment: fifty, in one row, and the event's count fifty too. */
    @Test
    void guestsWritingAtOnce_areEachCounted() throws Exception {
        WishService.Event event = event(party());
        List<WishService.Added> answers = atOnce(50, () -> wishes.add(event.token(), "ABBA - Dancing Queen"));
        assertThat(answers).containsOnly(WishService.Added.ADDED);
        assertThat(wishes.wishes(event.id())).containsExactly(new WishService.Wish("ABBA - Dancing Queen", 50, true));
        assertThat(wishes.event(event.partyCode(), event.id()).orElseThrow().wishCount()).isEqualTo(50);
    }

    /** One place left and ten guests at once: one gets it, the others hear the event is full — never 2 001. */
    @Test
    void theEventsCeiling_holdsUnderGuestsWritingAtOnce() throws Exception {
        WishService.Event event = event(party());
        jdbc.update("UPDATE wish_event SET wish_count = ? WHERE id = ?", WishService.MAX_WISHES - 1, event.id());
        List<WishService.Added> answers = atOnce(10, () -> wishes.add(event.token(), "Queen - Don't Stop Me Now"));
        assertThat(answers).filteredOn(answer -> answer == WishService.Added.ADDED).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer == WishService.Added.FULL).hasSize(9);
        assertThat(wishes.event(event.partyCode(), event.id()).orElseThrow().wishCount()).isEqualTo(WishService.MAX_WISHES);
    }

    @Test
    void aClosedOrPastEvent_takesNothing_andReopenedTakesAgain() {
        String party = party();
        WishService.Event event = event(party);
        assertThat(wishes.close(party, event.id())).isTrue();
        assertThat(wishes.add(event.token(), "Wilki - Baśka")).isEqualTo(WishService.Added.CLOSED);
        assertThat(wishes.reopen(party, event.id())).isEqualTo(WishService.Reopened.REOPENED);
        assertThat(wishes.add(event.token(), "Wilki - Baśka")).isEqualTo(WishService.Added.ADDED);

        jdbc.update("UPDATE wish_event SET event_date = ? WHERE id = ?", java.sql.Date.valueOf(WishService.today().minusDays(1)), event.id());
        assertThat(wishes.add(event.token(), "Wilki - Baśka")).as("the day has passed").isEqualTo(WishService.Added.CLOSED);
        assertThat(wishes.add("no-such-token", "Wilki - Baśka")).isEqualTo(WishService.Added.CLOSED);
        assertThat(wishes.wishes(event.id())).containsExactly(new WishService.Wish("Wilki - Baśka", 1, true));
    }

    @Test
    void aParty_hasAtMostFiveOpenEvents_aClosedOneMakesRoom_andAnotherPartyCannotTouchThem() {
        String party = party();
        List<WishService.Event> made = new ArrayList<>();
        for (int i = 0; i < WishService.MAX_OPEN_EVENTS; i++) {
            made.add(wishes.create(party, "Impreza " + i, WishService.today().plusDays(i)).orElseThrow());
        }
        assertThat(wishes.create(party, "Szósta", WishService.today())).isEmpty();
        wishes.close(party, made.get(0).id());
        assertThat(wishes.create(party, "Szósta", WishService.today())).isPresent();
        assertThat(wishes.eventsOf(party)).hasSize(WishService.MAX_OPEN_EVENTS + 1);

        String other = party();
        assertThat(wishes.event(other, made.get(1).id())).isEmpty();
        assertThat(wishes.close(other, made.get(1).id())).isFalse();
        assertThat(wishes.reopen(other, made.get(0).id())).isEqualTo(WishService.Reopened.NOT_FOUND);
        assertThat(wishes.newLink(other, made.get(1).id())).isFalse();
        assertThat(wishes.delete(other, made.get(1).id())).isFalse();
        assertThat(wishes.byToken(made.get(1).token())).isPresent();
    }

    @Test
    void reopening_keepsTheLimitOfOpenEvents() {
        String party = party();
        List<WishService.Event> made = new ArrayList<>();
        for (int i = 0; i < WishService.MAX_OPEN_EVENTS; i++) {
            made.add(wishes.create(party, "Impreza " + i, WishService.today().plusDays(i)).orElseThrow());
        }
        wishes.close(party, made.get(0).id());
        WishService.Event sixth = wishes.create(party, "Szósta", WishService.today()).orElseThrow();

        assertThat(wishes.reopen(party, made.get(0).id())).isEqualTo(WishService.Reopened.TOO_MANY);
        assertThat(wishes.openEvents(party)).isEqualTo(WishService.MAX_OPEN_EVENTS);
        assertThat(wishes.event(party, made.get(0).id()).orElseThrow().closed()).as("still closed").isTrue();

        wishes.close(party, sixth.id());
        assertThat(wishes.reopen(party, made.get(0).id())).isEqualTo(WishService.Reopened.REOPENED);
        assertThat(wishes.reopen(party, made.get(1).id())).as("open already: nothing to count").isEqualTo(WishService.Reopened.REOPENED);
        assertThat(wishes.openEvents(party)).isEqualTo(WishService.MAX_OPEN_EVENTS);
    }

    @Test
    void aParty_keepsAtMostTwentyEvents_closedOrNot_andListsEachOfThem() {
        String party = party();
        List<WishService.Event> made = new ArrayList<>();
        for (int i = 0; i < WishService.MAX_EVENTS; i++) {
            WishService.Event one = wishes.create(party, "Impreza " + i, WishService.today().plusDays(i)).orElseThrow();
            wishes.close(party, one.id());
            made.add(one);
        }
        assertThat(wishes.create(party, "Jedna za dużo", WishService.today())).isEmpty();
        assertThat(wishes.events(party)).isEqualTo(WishService.MAX_EVENTS);
        assertThat(wishes.eventsOf(party)).hasSize(WishService.MAX_EVENTS);

        wishes.delete(party, made.get(0).id());
        assertThat(wishes.create(party, "Jedna za dużo", WishService.today())).isPresent();
    }

    @Test
    void aNewLink_makesTheOldOneDead_andKeepsTheWishes() {
        String party = party();
        WishService.Event event = event(party);
        wishes.add(event.token(), "Wilki - Baśka");
        assertThat(wishes.newLink(party, event.id())).isTrue();
        assertThat(wishes.byToken(event.token())).isEmpty();
        assertThat(wishes.add(event.token(), "Wilki - Baśka")).isEqualTo(WishService.Added.CLOSED);
        String token = wishes.event(party, event.id()).orElseThrow().token();
        assertThat(token).isNotEqualTo(event.token()).hasSize(22);
        assertThat(wishes.add(token, "Wilki - Baśka")).isEqualTo(WishService.Added.ADDED);
        assertThat(wishes.wishes(event.id())).containsExactly(new WishService.Wish("Wilki - Baśka", 2, true));
    }

    /** The events follow "Zmień kod imprezy" (V49: ON UPDATE CASCADE) and go with the party, their wishes with them. */
    @Test
    void theEvents_followTheNewCode_andGoWithTheParty() {
        String party = party();
        WishService.Event event = event(party);
        wishes.add(event.token(), "Wilki - Baśka");
        String renamed = "X" + party.substring(1);
        jdbc.update("UPDATE party_settings SET party_code = ? WHERE party_code = ?", renamed, party);
        assertThat(wishes.event(renamed, event.id())).isPresent();

        jdbc.update("DELETE FROM party_settings WHERE party_code = ?", renamed);
        assertThat(wishes.byToken(event.token())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_wish WHERE event_id = ?", Integer.class, event.id())).isZero();
    }

    /** The purge: a week after the event's day, or two years after it was made, the event goes and its wishes with it. */
    @Test
    void thePurge_deletesTheEventsAWeekAfterTheirDay() {
        String party = party();
        WishService.Event kept = event(party);
        WishService.Event past = wishes.create(party, "Po imprezie", WishService.today()).orElseThrow();
        WishService.Event old = wishes.create(party, "Dawno", WishService.today().plusDays(10)).orElseThrow();
        wishes.add(past.token(), "Wilki - Baśka");
        LocalDate today = WishService.today();
        jdbc.update("UPDATE wish_event SET event_date = ? WHERE id = ?", java.sql.Date.valueOf(today.minusDays(WishService.KEPT_DAYS_AFTER + 1)), past.id());
        jdbc.update("UPDATE wish_event SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(WishService.MAX_DAYS_AHEAD + 1, ChronoUnit.DAYS)), old.id());

        wishes.purge();

        assertThat(wishes.eventsOf(party)).extracting(WishService.Event::id).containsExactly(kept.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_wish WHERE event_id = ?", Integer.class, past.id())).isZero();
    }

    /** The DJ's page: the events still ahead first, the nearest first; the past ones after them. */
    @Test
    void theEvents_areListedTheNearestFirst_thePastOnesLast() {
        String party = party();
        WishService.Event later = wishes.create(party, "Później", WishService.today().plusDays(20)).orElseThrow();
        WishService.Event sooner = wishes.create(party, "Wcześniej", WishService.today().plusDays(2)).orElseThrow();
        WishService.Event past = wishes.create(party, "Było", WishService.today()).orElseThrow();
        jdbc.update("UPDATE wish_event SET event_date = ? WHERE id = ?", java.sql.Date.valueOf(WishService.today().minusDays(3)), past.id());
        assertThat(wishes.eventsOf(party)).extracting(WishService.Event::id).containsExactly(sooner.id(), later.id(), past.id());
        assertThat(wishes.openEvents(party)).isEqualTo(2);
    }

    private static <T> List<T> atOnce(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
