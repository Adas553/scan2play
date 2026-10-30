package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.util.PartySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The party that opens and closes by itself (V55) at a moment the test's clock gives — no waiting: the plan checked against the party
 * as its row has it, the manual "Zakończ" / "Wznów" against the plan (the owner's rule of 2026-10-30), the job asking for what is due
 * now, and the words the panel and the guests read, in Polish time across the change of the clocks.
 */
class PartyScheduleServiceTest {

    private static final String PARTY = "ABC12";
    private static final Locale PL = Locale.forLanguageTag("pl");
    private static final Instant TODAY_20 = Instant.parse("2026-10-30T19:00:00Z");
    private static final Instant SATURDAY_3 = Instant.parse("2026-10-31T02:00:00Z");
    private static final Instant SATURDAY_20 = Instant.parse("2026-10-31T19:00:00Z");

    private PartySettingsCommandService commands;
    private PartySettingsEntity row;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        commands = mock(PartySettingsCommandService.class);
        row = PartySettingsEntity.builder().partyCode(PARTY).active(true).build();
        // as the real service: the change applied to the party's row (read under its lock)
        when(commands.updateSettings(eq(PARTY), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(row);
            return row;
        });
    }

    private PartyScheduleService at(Instant now) {
        return PartySchedules.of(commands, Clock.fixed(now, ZoneOffset.UTC));
    }

    private PartyScheduleService fridayNoon() {
        return PartySchedules.of(commands, PartySchedules.FRIDAY_NOON);
    }

    @Test
    void aPlan_isSaved_whenItWouldDoSomething() {
        row.setActive(false);
        fridayNoon().plan(PARTY, new PartySchedule.Plan(TODAY_20, SATURDAY_3));
        assertThat(row.getNextOpenAt()).isEqualTo(TODAY_20);
        assertThat(row.getNextCloseAt()).isEqualTo(SATURDAY_3);

        fridayNoon().plan(PARTY, PartySchedule.Plan.NONE);
        assertThat(row.getNextOpenAt()).isNull();
        assertThat(row.getNextCloseAt()).isNull();
    }

    /** A moment past by the clock, or one that would change nothing for the party as its row is: refused, and nothing changes. */
    @Test
    void aPlanThatCannotBe_isRefused_andNothingChanges() {
        row.setNextCloseAt(SATURDAY_3);
        PartyScheduleService saturdayMorning = at(Instant.parse("2026-10-31T08:00:00Z"));
        assertThatThrownBy(() -> saturdayMorning.plan(PARTY, new PartySchedule.Plan(null, SATURDAY_3)))
                .isInstanceOf(PartySchedule.Refused.class).hasMessage("PAST");
        assertThatThrownBy(() -> fridayNoon().plan(PARTY, new PartySchedule.Plan(TODAY_20, null)))
                .isInstanceOf(PartySchedule.Refused.class).hasMessage("OPEN_WHILE_OPEN");
        assertThat(row.getNextCloseAt()).isEqualTo(SATURDAY_3);
        assertThat(row.getNextOpenAt()).isNull();
    }

    /** The DJ closes at 1:00 an open party that closes at 3:00 and opens again at 20:00: the 3:00 is gone, the 20:00 stays. */
    @Test
    void theDjClosesEarly_theCloseIsGone_theOpenStays() {
        row.setNextCloseAt(SATURDAY_3);
        row.setNextOpenAt(SATURDAY_20);

        at(Instant.parse("2026-10-31T00:00:00Z")).setActive(PARTY, false);

        assertThat(row.isActive()).isFalse();
        assertThat(row.getNextCloseAt()).isNull();
        assertThat(row.getNextOpenAt()).isEqualTo(SATURDAY_20);
    }

    /** The DJ opens at 19:00 a closed party that opens at 20:00 and closes at 3:00: the 20:00 is gone, the 3:00 stays. */
    @Test
    void theDjOpensEarly_theOpenIsGone_theCloseStays() {
        row.setActive(false);
        row.setNextOpenAt(TODAY_20);
        row.setNextCloseAt(SATURDAY_3);

        at(Instant.parse("2026-10-30T18:00:00Z")).setActive(PARTY, true);

        assertThat(row.isActive()).isTrue();
        assertThat(row.getNextOpenAt()).isNull();
        assertThat(row.getNextCloseAt()).isEqualTo(SATURDAY_3);
    }

    /** "✕" takes one moment away; the other stays — unless it would then change nothing. */
    @Test
    void aMomentCancelled_leavesTheOther_unlessItWouldChangeNothing() {
        row.setActive(true);
        row.setNextCloseAt(SATURDAY_3);
        row.setNextOpenAt(SATURDAY_20);
        fridayNoon().cancel(PARTY, true);
        assertThat(row.getNextOpenAt()).isNull();
        assertThat(row.getNextCloseAt()).isEqualTo(SATURDAY_3);

        row.setActive(false);
        row.setNextOpenAt(TODAY_20);
        row.setNextCloseAt(SATURDAY_3);
        fridayNoon().cancel(PARTY, true);
        assertThat(row.getNextOpenAt()).as("no open: a close of a closed party changes nothing").isNull();
        assertThat(row.getNextCloseAt()).isNull();
    }

    /** The job asks for what is due at the clock's moment — the moment it is, not one it read before. */
    @Test
    void theJob_appliesWhatIsDueNow() {
        when(commands.applyDueSchedule(any(), eq(PartyScheduleService.DUE_BATCH)))
                .thenReturn(List.of(new PartySettingsCommandService.ScheduleChange(PARTY, true)));
        at(TODAY_20).applyDue();
        verify(commands).applyDueSchedule(TODAY_20, PartyScheduleService.DUE_BATCH);
    }

    /** The guest: "Prośby od 20:00" today, the day of the week up to six days ahead (Saturday in the genitive), then the date. */
    @Test
    void theGuestReadsWhenRequestsOpen() {
        PartyScheduleService s = fridayNoon();
        assertThat(s.guestOpens(opens(TODAY_20), PL)).isEqualTo("Prośby od 20:00");
        assertThat(s.guestOpens(opens(SATURDAY_20), PL)).isEqualTo("Prośby od soboty, 20:00");
        assertThat(s.guestOpens(opens(SATURDAY_3), PL)).isEqualTo("Prośby od soboty, 3:00");
        assertThat(s.guestOpens(opens(Instant.parse("2026-11-04T19:00:00Z")), PL)).isEqualTo("Prośby od środy, 20:00");
        assertThat(s.guestOpens(opens(Instant.parse("2026-11-06T19:00:00Z")), PL)).as("a week ahead: the date")
                .isEqualTo("Prośby od 06.11, 20:00");
        assertThat(s.guestOpens(opens(SATURDAY_20), Locale.ENGLISH)).isEqualTo("Requests from Saturday, 20:00");
        assertThat(s.guestOpens(opens(TODAY_20), Locale.ENGLISH)).isEqualTo("Requests from 20:00");
        assertThat(s.guestOpens(PartySettingsEntity.builder().build(), PL)).isNull();
        assertThat(at(TODAY_20).guestOpens(opens(TODAY_20), PL)).as("due, the job a minute away: no time that has passed").isNull();
        assertThat(at(TODAY_20.plusSeconds(30)).guestOpens(opens(TODAY_20), PL)).isNull();
    }

    /** The panel: today, tomorrow, the day of the week, the date — and the form's fields, in Polish time. */
    @Test
    void thePanelReadsThePlan() {
        PartySettingsEntity party = PartySettingsEntity.builder().active(false).nextOpenAt(TODAY_20).nextCloseAt(SATURDAY_3).build();
        PartyScheduleService.View view = fridayNoon().view(party, PL);
        assertThat(view.openText()).isEqualTo("Otworzy się dziś o 20:00");
        assertThat(view.closeText()).isEqualTo("Zamknie się jutro o 3:00");
        assertThat(view.openFirst()).isTrue();
        assertThat(List.of(view.openDate(), view.openTime(), view.closeDate(), view.closeTime()))
                .containsExactly("2026-10-30", "20:00", "2026-10-31", "03:00");
        assertThat(List.of(view.today(), view.tomorrow())).containsExactly("2026-10-30", "2026-10-31");
        assertThat(List.of(view.openKey(), view.closeKey())).containsExactly("open:1793386800", "close:1793412000");
        assertThat(PartyScheduleService.headerValue(party)).isEqualTo("open:1793386800,close:1793412000");
        assertThat(view.guestOpensText()).as("what the guests read meanwhile: the panel's banner says it").isEqualTo("Prośby od 20:00");

        party.setNextOpenAt(Instant.parse("2026-11-01T19:00:00Z"));
        assertThat(fridayNoon().view(party, PL).openText()).isEqualTo("Otworzy się w niedzielę o 20:00");
        assertThat(fridayNoon().view(party, PL).openFirst()).isFalse();
        party.setNextOpenAt(Instant.parse("2026-11-10T19:00:00Z"));
        assertThat(fridayNoon().view(party, Locale.ENGLISH).openText()).isEqualTo("Opens on 10.11 at 20:00");
        assertThat(PartyScheduleService.headerValue(PartySettingsEntity.builder().nextCloseAt(SATURDAY_3).build())).isEqualTo("close:1793412000");
        assertThat(PartyScheduleService.headerValue(PartySettingsEntity.builder().build())).isEmpty();
        assertThat(fridayNoon().view(PartySettingsEntity.builder().build(), PL).any()).isFalse();
    }

    /**
     * Over the night the clocks go back (2026-10-25, 3:00 summer time is 2:00 winter time): on Saturday evening, summer time, a close at
     * Sunday 3:00 winter time is "jutro o 3:00", and its field says 03:00 — not 4:00 or 2:00; an open on Sunday 20:00 says 20:00.
     */
    @Test
    void acrossTheChangeOfTheClocks_theHoursAreTheWallClocks() {
        PartyScheduleService saturdayEvening = at(Instant.parse("2026-10-24T19:00:00Z"));   // 21:00 summer time
        Instant sunday3 = PartySchedule.moment("2026-10-25", "03:00");
        Instant sunday20 = PartySchedule.moment("2026-10-25", "20:00");
        assertThat(sunday3).isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
        assertThat(sunday20).isEqualTo(Instant.parse("2026-10-25T19:00:00Z"));

        PartySettingsEntity party = PartySettingsEntity.builder().active(true).nextCloseAt(sunday3).nextOpenAt(sunday20).build();
        PartyScheduleService.View view = saturdayEvening.view(party, PL);
        assertThat(view.closeText()).isEqualTo("Zamknie się jutro o 3:00");
        assertThat(view.openText()).isEqualTo("Otworzy się jutro o 20:00");
        assertThat(view.closeTime()).isEqualTo("03:00");
        assertThat(saturdayEvening.guestOpens(party, PL)).isEqualTo("Prośby od niedzieli, 20:00");

        // and in spring (2027-03-28: 2:00 winter time is 3:00 summer time) — an open at Sunday 20:00 is 18:00 UTC
        Instant spring20 = PartySchedule.moment("2027-03-28", "20:00");
        assertThat(spring20).isEqualTo(Instant.parse("2027-03-28T18:00:00Z"));
        assertThat(at(Instant.parse("2027-03-27T20:00:00Z")).guestOpens(opens(spring20), PL)).isEqualTo("Prośby od niedzieli, 20:00");
    }

    private static PartySettingsEntity opens(Instant at) {
        return PartySettingsEntity.builder().active(false).nextOpenAt(at).build();
    }
}
