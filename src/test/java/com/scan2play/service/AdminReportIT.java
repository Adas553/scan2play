package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.EmailSender.Result;
import com.scan2play.util.Times;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.ITemplateEngine;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's daily report on a real PostgreSQL (V65): its queries — the evening's parties, the new organisers' requests
 * ({@code guestRequestsSince}) — and its "once a day" claim ({@code email_status.admin_report_day}): two runs at once send one report,
 * a failed send is given back. The service is the real one on the real repositories, with a fake sender (no Brevo).
 */
class AdminReportIT extends PostgresIntegrationTest {

    @Autowired SiteCounter siteCounter;
    @Autowired PartySettingsRepository parties;
    @Autowired EveningSummaryRepository evenings;
    @Autowired EveningSummaryService summaries;
    @Autowired SongRequestRepository requests;
    @Autowired PlanService plans;
    @Autowired ITemplateEngine templates;
    @Autowired JdbcTemplate jdbc;

    /** An evening far ahead: no other test has requests on it. */
    private static final LocalDate EVENING = LocalDate.parse("2102-03-04");
    private static final Instant MORNING_AFTER = EVENING.plusDays(1).atTime(9, 10).atZone(Times.DISPLAY_ZONE).toInstant();

    static final class FakeSender implements EmailSender {
        final List<Email> sent = new CopyOnWriteArrayList<>();
        volatile Function<Email, Result> answer = email -> Result.SENT;

        @Override
        public Result send(Email email) {
            Result result = answer.apply(email);
            if (result == Result.SENT) {
                sent.add(email);
            }
            return result;
        }
    }

    private final FakeSender sender = new FakeSender();

    private AdminReportService service(Instant now) {
        Mailer mailer = new Mailer(sender, templates, jdbc, "https://www.scan2play.com.pl", Runnable::run);
        return new AdminReportService(siteCounter, parties, evenings, summaries, plans, mailer, new AiHealthMonitor(), new LogTally(),
                jdbc, "owner@example.com", Clock.fixed(now, ZoneOffset.UTC));
    }

    private String organiser(String address) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("report-" + System.nanoTime())
                .ownerEmail(address).ownerName("Ola Kowalska").emailLanguage("pl").build()).getPartyCode();
    }

    private void guestsAsk(String code, int count, Instant at, boolean byDj) {
        for (int i = 0; i < count; i++) {
            requests.save(SongRequestEntity.builder().partyCode(code).songName("Artist " + i + " - Song " + i).style("ANY")
                    .decision(DjService.DECISION_ACCEPTED).requestedAt(at.plusSeconds(60L * i)).addedByDj(byDj).build());
        }
    }

    @Test
    void theEveningsParties_areListedWithTheirGuestsRequests() {
        String code = organiser("evening-" + System.nanoTime() + "@example.com");
        Instant nine = EVENING.atTime(21, 0).atZone(Times.DISPLAY_ZONE).toInstant();
        guestsAsk(code, 3, nine, false);
        guestsAsk(code, 2, nine.plusSeconds(3600), true);
        // the next evening's request (after 6:00) is not this evening's
        guestsAsk(code, 1, EVENING.plusDays(1).atTime(7, 0).atZone(Times.DISPLAY_ZONE).toInstant(), false);

        String text = service(MORNING_AFTER).build(MORNING_AFTER, new LogTally().peek()).text();

        assertThat(text).contains("IMPREZY WCZORAJ — wieczór 4.03, 6:00–6:00 (1)");
        assertThat(text.lines().dropWhile(line -> !line.startsWith(code)).skip(1).findFirst().orElseThrow())
                .startsWith("  prośby 3 · zagrane 0 · AI odrzuciło 0 · przed AI 0").endsWith("dodane przez DJ-a 2");
    }

    @Test
    void aNewOrganiser_isListedWithTheGuestsRequestsSinceTheirAccount() {
        String address = "new-" + System.nanoTime() + "@example.com";
        String code = organiser(address);
        String quiet = organiser("quiet-" + System.nanoTime() + "@example.com");
        Instant now = Instant.now();
        guestsAsk(code, 4, now.minusSeconds(3600), false);
        guestsAsk(code, 1, now.minusSeconds(1800), true);

        String text = service(now).build(now, new LogTally().peek()).text();

        assertThat(text.lines().filter(line -> line.contains("  " + code + "  ")).findFirst().orElseThrow()).endsWith("próśb gości: 4");
        assertThat(text.lines().filter(line -> line.contains("  " + quiet + "  ")).findFirst().orElseThrow()).endsWith("próśb gości: 0");
        assertThat(text).contains("  " + address + " · Ola Kowalska");
        assertThat(service(now).build(now, new LogTally().peek()).emailText()).doesNotContain(address, "Ola Kowalska")
                .endsWith("Adresy i imiona DJ-ów: https://www.scan2play.com.pl/dj/stats/report\n");
    }

    @Test
    void twoRunsAtOnce_sendOneReport_andAFailedOneIsGivenBack() throws Exception {
        LocalDate day = LocalDate.parse("2103-01-10");
        AdminReportService service = service(day.atTime(9, 10).atZone(Times.DISPLAY_ZONE).toInstant());
        sender.answer = email -> {
            sleep(300);
            return Result.SENT;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> {
                start.await();
                return service.sendOnce(day);
            });
            Future<Boolean> second = pool.submit(() -> {
                start.await();
                return service.sendOnce(day);
            });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.getFirst().to()).isEqualTo("owner@example.com");
        assertThat(sender.sent.getFirst().subject()).startsWith("Scan2Play — raport za 9.01:");
        assertThat(sender.sent.getFirst().text()).contains("RUCH", "NOWI DJ-E", "IMPREZY WCZORAJ", "ZDROWIE");
        assertThat(service.sendOnce(day)).as("the 11:10 run of the same day").isFalse();

        // the next day's first send fails: given back, the 11:10 run sends it
        LocalDate next = day.plusDays(1);
        sender.answer = email -> Result.FAILED;
        assertThat(service.sendOnce(next)).isFalse();
        assertThat(jdbc.queryForObject("SELECT admin_report_day FROM email_status WHERE id = 1", LocalDate.class)).isNull();
        sender.answer = email -> Result.SENT;
        assertThat(service.sendOnce(next)).isTrue();
        assertThat(jdbc.queryForObject("SELECT admin_report_day FROM email_status WHERE id = 1", LocalDate.class)).isEqualTo(next);
        assertThat(sender.sent).hasSize(2);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
