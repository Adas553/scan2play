package com.scan2play.service;

import com.scan2play.service.AdminReportService.Health;
import com.scan2play.service.AdminReportService.NewOrganiser;
import com.scan2play.service.AdminReportService.PartyLine;
import com.scan2play.service.SiteCounter.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The owner's daily report's text: the traffic beside the week, the new DJs, the evening's parties, the health. */
class AdminReportServiceTest {

    private static final LocalDate YESTERDAY = LocalDate.parse("2026-11-07");

    private static SiteCounter.Day day(String date, int landingPl, int start) {
        Map<Event, Integer> counts = new EnumMap<>(Event.class);
        for (Event event : Event.values()) {
            counts.put(event, 0);
        }
        counts.put(Event.LANDING_PL, landingPl);
        counts.put(Event.START, start);
        return new SiteCounter.Day(LocalDate.parse(date), counts);
    }

    private static Health healthy() {
        return new Health(false, true, new Mailer.Status(Instant.parse("2026-11-08T08:00:00Z"), null, null),
                new LogTally.Tally(Instant.parse("2026-11-07T08:10:00Z"), 0, 0, List.of()));
    }

    @Test
    void theTraffic_yesterdayBesideTheWeeksAverage_aMissingDayCountsAsZero() {
        // today's row is not yesterday's nor the week's; 2026-10-31 is outside the 7 days (11-01 … 11-07)
        List<SiteCounter.Day> days = List.of(day("2026-11-08", 99, 99), day("2026-11-07", 40, 4), day("2026-11-05", 30, 3),
                day("2026-10-31", 500, 50));

        StringBuilder text = new StringBuilder();
        AdminReportService.renderTraffic(text, YESTERDAY, days);

        assertThat(text.toString()).contains("RUCH (ludzie, bez robotów) — wczoraj · średnio 7 dni")
                .contains("Strona główna PL      40 ·   10,0")
                .contains("„Zacznij”              4 ·    1,0")
                .contains("„Zacznij” / wejścia  10 % ·   10 %");
        assertThat(AdminReportService.percent(1, 0)).isEqualTo("—");
    }

    @Test
    void theWholeReport_saysEachPart_andNothingWhenThereIsNothing() {
        String empty = AdminReportService.render(YESTERDAY, List.of(), List.of(), List.of(), false, healthy(), true);

        assertThat(empty).startsWith("Scan2Play — raport za sobota 7.11.2026")
                .contains("NOWI DJ-E — ostatnie 7 dni (0)\n(nikt nowy)", "IMPREZY WCZORAJ — wieczór 7.11, 6:00–6:00 (0)\n(żadnej)",
                        "AI (Gemini): odpowiada", "E-maile: klucz ustawiony\n", "Log od 07.11.2026 09:10:00: ERROR 0 · WARN 0")
                .doesNotContain("OSTATNI BŁĄD", "Razem");

        String full = AdminReportService.render(YESTERDAY, List.of(),
                List.of(new NewOrganiser(Instant.parse("2026-11-06T13:02:00Z"), "AB12", "jan@example.com", "Jan K.", "PRO", 12)),
                List.of(new PartyLine("AB12", "jan@example.com", "PRO", "Wesele Ani", 48, 31, 5, 3, 4, 2, 6),
                        new PartyLine("CD34", null, "FREE", null, 2, 0, 0, 0, 0, 0, 0)), true,
                new Health(true, true, new Mailer.Status(Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-06T08:00:00Z"),
                        "the key was refused"), new LogTally.Tally(Instant.parse("2026-11-07T08:10:00Z"), 3, 1,
                        List.of(new LogTally.Line("ERROR AiHealthMonitor: AI check: 3 of 5", 3)))), true);

        assertThat(full).contains("06.11 14:02  AB12  PRO  próśb gości: 12\n  jan@example.com · Jan K.\n")
                .contains("IMPREZY WCZORAJ — wieczór 7.11, 6:00–6:00 (2+)")
                .contains("AB12  PRO  jan@example.com · „Wesele Ani”\n  prośby 48 · zagrane 31 · AI odrzuciło 5 · przed AI 3 · kiosk 4"
                        + " · napiwki 2 · dodane przez DJ-a 6\n")
                .contains("CD34  FREE  (bez adresu)\n  prośby 2 · zagrane 0 · AI odrzuciło 0 · przed AI 0\n")
                .contains("Razem: prośby 50 · zagrane 31 (pokazane pierwsze 50)")
                .contains("AI (Gemini): NIE ODPOWIADA", "OSTATNI BŁĄD 06.11.2026 09:00:00: the key was refused")
                .contains("ERROR 3 · WARN 1\n    3× ERROR AiHealthMonitor: AI check: 3 of 5");

        // the e-mail: the parties by their code, no address, name or evening's title (the privacy policy gives Brevo those only to
        // write to that organiser)
        StringBuilder email = new StringBuilder();
        AdminReportService.renderOrganisers(email, List.of(new NewOrganiser(Instant.parse("2026-11-06T13:02:00Z"), "AB12",
                "jan@example.com", "Jan K.", "PRO", 12)), false);
        AdminReportService.renderParties(email, YESTERDAY, List.of(new PartyLine("AB12", "jan@example.com", "PRO", "Wesele Ani", 48, 31,
                5, 3, 4, 2, 6)), false, false);
        assertThat(email.toString()).contains("06.11 14:02  AB12  PRO  próśb gości: 12\n", "AB12  PRO\n  prośby 48")
                .doesNotContain("jan@example.com", "Jan K.", "Wesele Ani");
    }

    @Test
    void aReportThatCannotBeBuilt_givesTheDayAndTheLogsCountsBack() {
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.update(org.mockito.ArgumentMatchers.startsWith("UPDATE email_status SET admin_report_day = ?"), any(), any()))
                .thenReturn(1);
        SiteCounter counter = mock(SiteCounter.class);
        when(counter.lastDays(org.mockito.ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("the database is away"));
        Mailer mailer = mock(Mailer.class);
        when(mailer.isOn()).thenReturn(true);
        LogTally tally = new LogTally();
        tally.count(new ch.qos.logback.classic.spi.LoggingEvent("x", new ch.qos.logback.classic.LoggerContext().getLogger("x.Y"),
                ch.qos.logback.classic.Level.ERROR, "boom", null, null));
        AdminReportService service = new AdminReportService(counter, null, null, null, null, mailer, null, tally, jdbc, "a@b.pl",
                java.time.Clock.systemUTC());

        assertThat(service.sendOnce(YESTERDAY.plusDays(1))).isFalse();

        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("UPDATE email_status SET admin_report_day = NULL"),
                org.mockito.ArgumentMatchers.eq(java.sql.Date.valueOf(YESTERDAY.plusDays(1))));
        verify(mailer, never()).send(any());
        assertThat(tally.peek().errors()).as("the night's error waits for the 11:10 run").isEqualTo(1);
    }

    @Test
    void withoutAnAddressOrAKey_nothingIsSent() {
        Mailer mailer = mock(Mailer.class);
        when(mailer.isOn()).thenReturn(true);
        AdminReportService noAddress = new AdminReportService(null, null, null, null, null, mailer, null, new LogTally(), null, " ", null);

        assertThat(noAddress.canSend()).isFalse();
        assertThat(noAddress.sendNow()).isFalse();
        noAddress.sendDaily();
        verify(mailer, never()).send(any());

        when(mailer.isOn()).thenReturn(false);
        assertThat(new AdminReportService(null, null, null, null, null, mailer, null, new LogTally(), null, "a@b.pl", null).canSend())
                .isFalse();
    }
}
