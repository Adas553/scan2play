package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.GuestTheme;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EventPlanService.Moment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Plan imprezy" (V77) on a real PostgreSQL: a save replaces the moments whole and keeps their order, two saves at once leave one of
 * the two plans (never a mix), a save made from an older version of the plan writes nothing, the hosts' link writes only while it is
 * the event's — a new one makes the old dead — and only so often, a template only starts an empty plan, tonight's plan is the day's
 * event started last, and the plan goes with its event.
 */
class EventPlanIT extends PostgresIntegrationTest {

    @Autowired EventPlanService plans;
    @Autowired WishService wishes;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private WishService.Event event() {
        String code = newPartyCode('E');
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("plan-" + code).active(true).build());
        return wishes.create(code, "Wesele Ani i Kuby", WishService.today().plusDays(30), GuestTheme.CLASSIC).orElseThrow();
    }

    private static Moment moment(String label) {
        return new Moment(label, null, null, null);
    }

    @Test
    void aSave_replacesTheMomentsWhole_inTheirOrder_andSaysWhoSaved() {
        WishService.Event event = event();
        plans.save(event.id(), List.of(moment("A"), moment("B"), moment("C")), "Akcent\nAkcent", null, EventPlanService.BY_DJ);
        plans.save(event.id(), List.of(new Moment("Tort", LocalTime.of(23, 0), "Ktoś - Coś", "DJ wybiera"), moment("Oczepiny")),
                "Akcent", "Wilki - Baśka", EventPlanService.BY_DJ);

        EventPlanService.Plan plan = plans.plan(event.id());

        assertThat(plan.moments()).containsExactly(new Moment("Tort", LocalTime.of(23, 0), "Ktoś - Coś", "DJ wybiera"),
                moment("Oczepiny"));
        assertThat(plan.blocked()).isEqualTo("Akcent");
        assertThat(plan.wanted()).isEqualTo("Wilki - Baśka");
        assertThat(plan.savedBy()).isEqualTo("dj");
        assertThat(plan.savedAt()).isNotNull();
    }

    @Test
    void twoSavesAtOnce_leaveOneOfThePlans_neverAMix() throws Exception {
        WishService.Event event = event();
        List<Moment> first = List.of(moment("1a"), moment("1b"), moment("1c"));
        List<Moment> second = List.of(moment("2a"), moment("2b"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Void>> saves = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                List<Moment> mine = i % 2 == 0 ? first : second;
                Callable<Void> save = () -> {
                    start.await();
                    plans.save(event.id(), mine, null, null, EventPlanService.BY_HOSTS);
                    return null;
                };
                saves.add(pool.submit(save));
            }
            start.countDown();
            for (Future<Void> save : saves) {
                save.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(plans.plan(event.id()).moments()).isIn(first, second);
    }

    @Test
    void theHostsLink_writesWhileItIsTheEvents_aNewOneMakesTheOldDead() {
        WishService.Event event = event();
        assertThat(plans.hostToken(event.partyCode(), event.id())).isEmpty();
        assertThat(plans.newHostLink(event.partyCode(), event.id())).isTrue();
        String old = plans.hostToken(event.partyCode(), event.id()).orElseThrow();
        assertThat(plans.byHostToken(old)).contains(new EventPlanService.HostEvent(event.id(), event.partyCode()));
        assertThat(plans.saveByHosts(old, List.of(moment("Pierwszy taniec")), null, "Akcent", null)).isEqualTo(EventPlanService.Saved.SAVED);
        assertThat(plans.plan(event.id()).savedBy()).isEqualTo("hosts");

        assertThat(plans.newHostLink(event.partyCode(), event.id())).isTrue();
        assertThat(plans.byHostToken(old)).isEmpty();
        assertThat(plans.saveByHosts(old, List.of(moment("nadpisane")), null, null, null)).isEqualTo(EventPlanService.Saved.GONE);
        assertThat(plans.plan(event.id()).moments()).containsExactly(moment("Pierwszy taniec"));

        assertThat(plans.removeHostLink(event.partyCode(), event.id())).isTrue();
        assertThat(plans.hostToken(event.partyCode(), event.id())).isEmpty();
        assertThat(plans.newHostLink("ZZZZZ", event.id())).as("another party's event").isFalse();
        assertThat(plans.byHostToken("x".repeat(33))).isEmpty();
    }

    @Test
    void aTemplate_startsOnlyAnEmptyPlan_keepingItsLists() {
        WishService.Event event = event();
        plans.save(event.id(), List.of(), "Akcent", null, EventPlanService.BY_HOSTS);

        assertThat(plans.startFrom(event.id(), List.of("Wejście pary", "Tort"), EventPlanService.BY_DJ)).isTrue();
        assertThat(plans.startFrom(event.id(), List.of("Inny"), EventPlanService.BY_DJ)).isFalse();

        EventPlanService.Plan plan = plans.plan(event.id());
        assertThat(plan.moments()).containsExactly(moment("Wejście pary"), moment("Tort"));
        assertThat(plan.blocked()).isEqualTo("Akcent");
    }

    @Test
    void tonight_isTheEventOfTheEvening_withMoments_untilSixInTheMorning() {
        WishService.Event event = event();   // dated 30 days ahead
        WishService.Event empty = wishes.create(event.partyCode(), "Bez planu", event.date(), GuestTheme.CLASSIC).orElseThrow();
        java.time.Instant evening = event.date().atTime(22, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();
        assertThat(plans.tonight(event.partyCode(), evening)).as("no moments yet: no plan tonight").isEmpty();
        assertThat(plans.tonightSongs(event.partyCode(), evening)).isNull();

        plans.save(event.id(), List.of(new Moment("Tort", LocalTime.of(23, 0), "Ktoś - Coś", null), moment("Oczepiny"),
                new Moment("Ostatni taniec", null, "Zespół - Na Koniec", null)), null, null, EventPlanService.BY_DJ);
        plans.save(empty.id(), List.of(), null, "Akcent", EventPlanService.BY_DJ);

        EventPlanService.Tonight tonight = plans.tonight(event.partyCode(), evening).orElseThrow();
        assertThat(tonight.eventId()).as("the event with moments, not the other of the day").isEqualTo(event.id());
        assertThat(tonight.name()).isEqualTo("Wesele Ani i Kuby");
        assertThat(tonight.evening()).isEqualTo(event.date());
        assertThat(tonight.moments()).extracting(Moment::label).containsExactly("Tort", "Oczepiny", "Ostatni taniec");
        assertThat(plans.tonightSongs(event.partyCode(), evening)).isEqualTo("Ktoś - Coś\nZespół - Na Koniec");

        java.time.Instant twoAtNight = evening.plusSeconds(4 * 3600);
        assertThat(plans.tonight(event.partyCode(), twoAtNight)).as("2:00 is still the evening before").isPresent();
        java.time.Instant morning = evening.plusSeconds(9 * 3600);
        assertThat(plans.tonight(event.partyCode(), morning)).as("7:00 is the next day's").isEmpty();
        assertThat(plans.tonightSongs(event.partyCode(), morning)).isNull();
        assertThat(plans.tonight("ZZZZZ", evening)).as("another party's").isEmpty();
    }

    @Test
    void thePlan_goesWithItsEvent() {
        WishService.Event event = event();
        plans.save(event.id(), List.of(moment("A")), null, null, EventPlanService.BY_DJ);

        assertThat(wishes.delete(event.partyCode(), event.id())).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_plan_item WHERE event_id = ?", Integer.class, event.id())).isZero();
        assertThat(plans.plan(event.id()).moments()).isEmpty();
    }

    @Test
    void clearing_takesTheMomentsTheListsAndTheLastSave_keepsTheHostsLink_andAnOlderPageSavesNothingOverIt() {
        WishService.Event event = event();
        java.time.Instant evening = event.date().atTime(20, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();
        plans.save(event.id(), List.of(new Moment("Tort", null, "Ktoś - Coś", "imiona")), "Akcent", "Wilki", EventPlanService.BY_HOSTS);
        plans.newHostLink(event.partyCode(), event.id());
        String token = plans.hostToken(event.partyCode(), event.id()).orElseThrow();
        String before = plans.plan(event.id()).version();
        assertThat(plans.tonight(event.partyCode(), evening)).isPresent();

        assertThat(plans.clear(event.id())).isTrue();

        EventPlanService.Plan plan = plans.plan(event.id());
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.savedAt()).isNull();
        assertThat(plan.savedBy()).isNull();
        assertThat(plans.tonight(event.partyCode(), evening.plusSeconds(1))).as("the panel's card gone at once").isEmpty();
        assertThat(plans.byHostToken(token)).as("the hosts' link stays").isPresent();
        assertThat(plans.saveByHosts(token, List.of(moment("Stara strona")), null, null, before))
                .as("a page opened before the clearing").isEqualTo(EventPlanService.Saved.CHANGED);
        assertThat(plans.clear(-1)).isFalse();
    }

    @Test
    void aSaveFromAnOlderVersion_writesNothing_andOneFromTheCurrentOneDoes() {
        WishService.Event event = event();
        String never = plans.plan(event.id()).version();
        assertThat(never).isEmpty();

        assertThat(plans.save(event.id(), List.of(moment("DJ")), null, null, EventPlanService.BY_DJ, never))
                .isEqualTo(EventPlanService.Saved.SAVED);
        String first = plans.plan(event.id()).version();
        assertThat(first).isNotEmpty();
        // the hosts' page was opened before the DJ's save
        plans.newHostLink(event.partyCode(), event.id());
        String token = plans.hostToken(event.partyCode(), event.id()).orElseThrow();
        assertThat(plans.saveByHosts(token, List.of(moment("Para")), null, null, never)).isEqualTo(EventPlanService.Saved.CHANGED);
        assertThat(plans.save(event.id(), List.of(moment("DJ, stara strona")), null, null, EventPlanService.BY_DJ, never))
                .isEqualTo(EventPlanService.Saved.CHANGED);
        assertThat(plans.plan(event.id()).moments()).extracting(Moment::label).as("nothing overwritten").containsExactly("DJ");

        assertThat(plans.saveByHosts(token, List.of(moment("Para")), null, null, first)).isEqualTo(EventPlanService.Saved.SAVED);
        assertThat(plans.plan(event.id()).moments()).extracting(Moment::label).containsExactly("Para");
        assertThat(plans.plan(event.id()).version()).isNotEqualTo(first);
    }

    @Test
    void theHostsLink_savesOnlySoOften() {
        WishService.Event event = event();
        plans.newHostLink(event.partyCode(), event.id());
        String token = plans.hostToken(event.partyCode(), event.id()).orElseThrow();
        for (int i = 0; i < EventPlanService.HOST_SAVES_PER_WINDOW; i++) {
            assertThat(plans.saveByHosts(token, List.of(moment("Zapis " + i)), null, null, null)).isEqualTo(EventPlanService.Saved.SAVED);
        }

        assertThat(plans.saveByHosts(token, List.of(moment("Za dużo")), null, null, null)).isEqualTo(EventPlanService.Saved.TOO_MANY);
        assertThat(plans.plan(event.id()).moments()).extracting(Moment::label).containsExactly("Zapis " + (EventPlanService.HOST_SAVES_PER_WINDOW - 1));
        assertThat(plans.save(event.id(), List.of(moment("DJ")), null, null, EventPlanService.BY_DJ, null))
                .as("the DJ's page is not limited").isEqualTo(EventPlanService.Saved.SAVED);
    }

    @Test
    void twoPlansOnOneDay_tonightsIsTheOneUnderWay_andTheListShowsWhichHaveAPlan() {
        WishService.Event christening = event();
        WishService.Event wedding = wishes.create(christening.partyCode(), "Wesele", christening.date(), GuestTheme.CLASSIC).orElseThrow();
        WishService.Event none = wishes.create(christening.partyCode(), "Bez planu", christening.date(), GuestTheme.CLASSIC).orElseThrow();
        plans.save(christening.id(), List.of(new Moment("Obiad", LocalTime.of(14, 0), "Ktoś - Chrzciny", null)), null, null, EventPlanService.BY_DJ);
        plans.save(wedding.id(), List.of(new Moment("Wejście pary", LocalTime.of(19, 0), "Zespół - Wesele", null), moment("Tort")),
                "Disco polo", null, EventPlanService.BY_DJ);
        java.time.ZonedDateTime day = christening.date().atStartOfDay(com.scan2play.util.Times.DISPLAY_ZONE);

        assertThat(plans.tonight(christening.partyCode(), day.withHour(15).toInstant()).orElseThrow().eventId()).isEqualTo(christening.id());
        assertThat(plans.tonightSongs(christening.partyCode(), day.withHour(15).plusMinutes(1).toInstant())).isEqualTo("Ktoś - Chrzciny");
        EventPlanService.Tonight evening = plans.tonight(christening.partyCode(), day.withHour(18).withMinute(30).toInstant()).orElseThrow();
        assertThat(evening.eventId()).isEqualTo(wedding.id());
        assertThat(evening.blocked()).isEqualTo("Disco polo");
        assertThat(plans.tonightSongs(christening.partyCode(), day.withHour(18).withMinute(31).toInstant())).isEqualTo("Zespół - Wesele");

        assertThat(plans.momentCounts(christening.partyCode())).containsOnly(java.util.Map.entry(christening.id(), 1),
                java.util.Map.entry(wedding.id(), 2)).doesNotContainKey(none.id());
    }

    @Test
    void aSave_isSeenTonightAtOnce_notAfterTheKeptHalfMinute() {
        WishService.Event event = event();
        java.time.Instant evening = event.date().atTime(20, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();
        plans.save(event.id(), List.of(new Moment("Tort", null, "Ktoś - Coś", null)), null, null, EventPlanService.BY_DJ);
        assertThat(plans.tonightSongs(event.partyCode(), evening)).isEqualTo("Ktoś - Coś");

        plans.save(event.id(), List.of(new Moment("Tort", null, "Ktoś - Inne", null)), null, null, EventPlanService.BY_DJ);

        assertThat(plans.tonightSongs(event.partyCode(), evening.plusSeconds(1))).isEqualTo("Ktoś - Inne");
    }
}
