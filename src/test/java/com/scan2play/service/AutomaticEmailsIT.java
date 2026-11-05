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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automatic e-mails' "once" on a real PostgreSQL (V64): the welcome's claim ({@code welcome_sent_at}) and the morning summary's
 * ({@code evening_summary.morning_sent_at}) — two logins at once send one welcome, the nightly job run twice (and at once) sends it
 * once, a failed send is given back and sent by the next run; the morning job run twice (and at once) sends an evening once, only
 * for an evening of at least 5 guests' requests. The services are the real ones on the real repositories, with a fake sender (no
 * Brevo) and an executor that runs at once — the application's own beans stay as they are, so the context is the shared one.
 */
class AutomaticEmailsIT extends PostgresIntegrationTest {

    @Autowired PartySettingsQueryService queries;
    @Autowired PartySettingsCommandService commands;
    @Autowired PartySettingsRepository parties;
    @Autowired EveningSummaryRepository evenings;
    @Autowired EveningSummaryService summaries;
    @Autowired SongRequestRepository requests;
    @Autowired PlanService plans;
    @Autowired ITemplateEngine templates;
    @Autowired JdbcTemplate jdbc;

    /** The e-mails "sent", and a send that waits until both racers are in it (or fails) — what the test asks of it. */
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

    private Mailer mailer() {
        return new Mailer(sender, templates, jdbc, "https://www.scan2play.com.pl", Runnable::run);
    }

    private WelcomeEmailService welcome() {
        return new WelcomeEmailService(queries, commands, parties, mailer());
    }

    private MorningSummaryEmailService morning() {
        return new MorningSummaryEmailService(evenings, summaries, queries, plans, mailer());
    }

    /** A new organiser's party, with an address and no welcome yet (as a party made after V64). */
    private String newOrganiser(String address) {
        String code = parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("mail-" + System.nanoTime())
                .ownerEmail(address).ownerName("Ola Kowalska").emailLanguage("pl").build()).getPartyCode();
        assertThat(jdbc.queryForObject("SELECT welcome_sent_at FROM party_settings WHERE party_code = ?", Timestamp.class, code))
                .as("a party made after V64 has had no welcome").isNull();
        return code;
    }

    private Timestamp welcomeSentAt(String code) {
        return jdbc.queryForObject("SELECT welcome_sent_at FROM party_settings WHERE party_code = ?", Timestamp.class, code);
    }

    private long sentTo(String address) {
        return sender.sent.stream().filter(email -> email.to().equals(address)).count();
    }

    /** Runs {@code task} on two threads let go at the same moment; their answers. */
    private static <T> List<T> twoAtOnce(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> racer = () -> {
                start.await();
                return task.call();
            };
            Future<T> first = pool.submit(racer);
            Future<T> second = pool.submit(racer);
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoLoginsAtOnce_sendOneWelcome() throws Exception {
        String address = "two-logins-" + System.nanoTime() + "@example.com";
        String code = newOrganiser(address);
        // the send is slow, as Brevo's: the second login comes while the first still sends
        sender.answer = email -> {
            sleep(300);
            return Result.SENT;
        };
        WelcomeEmailService service = welcome();

        List<Boolean> sent = twoAtOnce(() -> service.send(code));

        assertThat(sent).containsExactlyInAnyOrder(true, false);
        assertThat(sentTo(address)).isEqualTo(1);
        assertThat(welcomeSentAt(code)).isNotNull();
        assertThat(queries.getSettings(code).getWelcomeSentAt()).as("the cache follows the claim").isNotNull();
        // the next login, and the nightly job, send nothing more
        assertThat(service.send(code)).isFalse();
        service.sendDue();
        assertThat(sentTo(address)).isEqualTo(1);
    }

    @Test
    void aFailedWelcome_isGivenBack_andTheNightlyJobRunTwiceSendsItOnce() throws Exception {
        String address = "failed-" + System.nanoTime() + "@example.com";
        String code = newOrganiser(address);
        WelcomeEmailService service = welcome();
        sender.answer = email -> Result.FAILED;

        assertThat(service.send(code)).isFalse();
        assertThat(welcomeSentAt(code)).as("given back for the night").isNull();

        sender.answer = email -> {
            sleep(200);
            return Result.SENT;
        };
        twoAtOnce(() -> {
            service.sendDue();
            return true;
        });
        service.sendDue();

        assertThat(sentTo(address)).isEqualTo(1);
        assertThat(welcomeSentAt(code)).isNotNull();
        Email email = sender.sent.stream().filter(e -> e.to().equals(address)).findFirst().orElseThrow();
        assertThat(email.subject()).isEqualTo("Scan2Play — dzięki za założenie konta");
        assertThat(email.html()).contains("Cześć Ola,", "/email/stop/" + queries.getSettings(code).getEmailToken() + "?lang=pl");
        assertThat(jdbc.queryForObject("SELECT last_sent_at FROM email_status WHERE id = 1", Timestamp.class)).isNotNull();
    }

    @Test
    void anAccountOfBefore_andOneWithoutAnAddress_getNoWelcome() {
        String code = parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("no-mail-" + System.nanoTime())
                .build()).getPartyCode();
        sender.answer = email -> Result.SENT;

        assertThat(welcome().send(code)).as("no address").isFalse();
        assertThat(welcomeSentAt(code)).isNull();
        assertThat(sender.sent).isEmpty();
    }

    // ---- the morning summary ----

    /** An evening far ahead: no other test, and not the app's own 10:00 run, has requests on it. */
    private static final LocalDate EVENING = LocalDate.parse("2101-06-04");
    private static final Instant MORNING_AFTER = EVENING.plusDays(1).atTime(10, 0).atZone(Times.DISPLAY_ZONE).toInstant();

    private void guestsAsk(String code, int count, LocalDate evening, boolean byDj) {
        Instant at = evening.atTime(21, 0).atZone(Times.DISPLAY_ZONE).toInstant();
        for (int i = 0; i < count; i++) {
            requests.save(SongRequestEntity.builder().partyCode(code).songName("Artist " + i + " - Song " + i).style("ANY")
                    .decision(DjService.DECISION_ACCEPTED).requestedAt(at.plusSeconds(60L * i)).addedByDj(byDj).build());
        }
    }

    private Timestamp morningSentAt(String code, LocalDate evening) {
        List<Timestamp> rows = jdbc.queryForList("SELECT morning_sent_at FROM evening_summary WHERE party_code = ? AND evening = ?",
                Timestamp.class, code, evening);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Test
    void theMorningJobRunTwice_andAtOnce_sendsAnEveningOnce_onlyABusyOne() throws Exception {
        String busyAddress = "busy-" + System.nanoTime() + "@example.com";
        String busy = newOrganiser(busyAddress);
        guestsAsk(busy, 5, EVENING, false);
        String quietAddress = "quiet-" + System.nanoTime() + "@example.com";
        String quiet = newOrganiser(quietAddress);
        guestsAsk(quiet, 4, EVENING, false);
        guestsAsk(quiet, 3, EVENING, true);   // the DJ's own additions are not the guests'
        String switchedOffAddress = "off-" + System.nanoTime() + "@example.com";
        String switchedOff = newOrganiser(switchedOffAddress);
        guestsAsk(switchedOff, 6, EVENING, false);
        commands.updateSettings(switchedOff, s -> s.setMorningSummary(false));
        sender.answer = email -> {
            sleep(200);
            return Result.SENT;
        };
        MorningSummaryEmailService service = morning();

        twoAtOnce(() -> service.send(MORNING_AFTER));
        service.send(MORNING_AFTER.plusSeconds(2 * 3600));   // the noon run

        assertThat(sentTo(busyAddress)).isEqualTo(1);
        assertThat(sentTo(quietAddress)).as("4 guests' requests").isZero();
        assertThat(sentTo(switchedOffAddress)).as("switched off").isZero();
        assertThat(morningSentAt(busy, EVENING)).isNotNull();
        Email email = sender.sent.stream().filter(e -> e.to().equals(busyAddress)).findFirst().orElseThrow();
        assertThat(email.html()).contains("/dj/summary?evening=2101-06-04&amp;party=" + busy, "sobota, 4 czerwca 2101");
    }

    @Test
    void aFailedMorningSummary_isGivenBack_andTheNoonRunSendsIt() {
        LocalDate evening = EVENING.plusDays(7);
        Instant morningAfter = evening.plusDays(1).atTime(10, 0).atZone(Times.DISPLAY_ZONE).toInstant();
        String address = "noon-" + System.nanoTime() + "@example.com";
        String code = newOrganiser(address);
        guestsAsk(code, 5, evening, false);
        commands.updateSettings(code, s -> s.setEmailLanguage("en"));
        summaries.saveTitle(code, evening, "Anna and Tom's wedding");
        MorningSummaryEmailService service = morning();

        sender.answer = email -> Result.FAILED;
        assertThat(service.send(morningAfter)).isZero();
        assertThat(morningSentAt(code, evening)).as("given back").isNull();
        assertThat(jdbc.queryForObject("SELECT title FROM evening_summary WHERE party_code = ? AND evening = ?", String.class, code,
                evening)).as("the DJ's title kept").isEqualTo("Anna and Tom's wedding");

        sender.answer = email -> Result.SENT;
        assertThat(service.send(morningAfter.plusSeconds(2 * 3600))).isEqualTo(1);
        Email email = sender.sent.stream().filter(e -> e.to().equals(address)).findFirst().orElseThrow();
        assertThat(email.subject()).startsWith("Anna and Tom's wedding — the evening's summary");
        assertThat(email.html()).contains("Don't send me this", "Anna and Tom&#39;s wedding");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
