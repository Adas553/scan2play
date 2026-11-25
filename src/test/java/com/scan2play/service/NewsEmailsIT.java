package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PanelNews;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.ITemplateEngine;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Nowości mailem" (V80) on a real PostgreSQL: the consent kept by its own statement with its time — a save of the settings never puts
 * an old value back —, the news now due to whoever says yes, and the news e-mail's "once": two sends at once send it once, a failed
 * send is given back and sent by the next, nobody without the consent gets it.
 */
class NewsEmailsIT extends PostgresIntegrationTest {

    @Autowired PartySettingsQueryService queries;
    @Autowired PartySettingsCommandService commands;
    @Autowired PartySettingsRepository parties;
    @Autowired ITemplateEngine templates;
    @Autowired JdbcTemplate jdbc;

    private final AutomaticEmailsIT.FakeSender sender = new AutomaticEmailsIT.FakeSender();

    private NewsEmailService service() {
        return new NewsEmailService(queries, commands, parties,
                new Mailer(sender, templates, jdbc, "https://www.scan2play.com.pl", Runnable::run));
    }

    private String organiser(String address) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("news-" + System.nanoTime())
                .ownerEmail(address).ownerName("Ola Kowalska").emailLanguage("pl").build()).getPartyCode();
    }

    /** An organiser who said yes before the news now came (its number not had yet). */
    private String waitingForTheNews(String address) {
        String code = organiser(address);
        jdbc.update("UPDATE party_settings SET news_emails = true, news_emails_at = now(), news_sent = ? WHERE party_code = ?",
                PanelNews.NUMBER - 1, code);
        return code;
    }

    private int newsSent(String code) {
        return jdbc.queryForObject("SELECT news_sent FROM party_settings WHERE party_code = ?", Integer.class, code);
    }

    private long sentTo(String address) {
        return sender.sent.stream().filter(email -> email.to().equals(address)).count();
    }

    @Test
    void aNewAccount_saysNoUntilItSaysYes() {
        String code = organiser("new-" + System.nanoTime() + "@example.com");

        PartySettingsEntity settings = queries.getSettings(code);
        assertThat(settings.isNewsEmails()).isFalse();
        assertThat(settings.getNewsEmailsAt()).isNull();
        assertThat(settings.getNewsSeen()).isZero();
        assertThat(parties.newsDue(PanelNews.NUMBER + 1, 10_000)).doesNotContain(code);
    }

    @Test
    void theConsent_isKeptWithItsTime_andTheNewsNowIsDueToThem() {
        String code = organiser("yes-" + System.nanoTime() + "@example.com");
        assertThat(queries.getSettings(code).isNewsEmails()).isFalse();   // cached
        assertThat(parties.newsDue(PanelNews.NUMBER, 10_000)).doesNotContain(code);

        commands.setNewsEmails(code, true);

        PartySettingsEntity settings = queries.getSettings(code);
        assertThat(settings.isNewsEmails()).as("the cache follows").isTrue();
        assertThat(settings.getNewsEmailsAt()).isNotNull();
        assertThat(newsSent(code)).isZero();
        assertThat(parties.newsDue(PanelNews.NUMBER, 10_000)).as("the news now is theirs by the next send").contains(code);

        // a save of the other settings (read before or after) never puts the old consent back
        commands.updateSettings(code, s -> s.setDjName("DJ Ola"));
        assertThat(queries.getSettings(code).isNewsEmails()).isTrue();

        Timestamp yes = jdbc.queryForObject("SELECT news_emails_at FROM party_settings WHERE party_code = ?", Timestamp.class, code);
        commands.setNewsEmails(code, false);
        assertThat(queries.getSettings(code).isNewsEmails()).isFalse();
        assertThat(jdbc.queryForObject("SELECT news_emails_at FROM party_settings WHERE party_code = ?", Timestamp.class, code))
                .as("the time it was taken back").isAfterOrEqualTo(yes);
        assertThat(parties.newsDue(PanelNews.NUMBER, 10_000)).as("taken back: nothing due").doesNotContain(code);
    }

    @Test
    void twoSendsAtOnce_sendTheNewsOnce_andNotToWhoSaidNo() throws Exception {
        String address = "twice-" + System.nanoTime() + "@example.com";
        String code = waitingForTheNews(address);
        String no = "no-" + System.nanoTime() + "@example.com";
        organiser(no);
        sender.answer = email -> {
            sleep(300);
            return Result.SENT;
        };
        NewsEmailService service = service();

        List<Boolean> sent = twoAtOnce(() -> service.send(code));

        assertThat(sent).containsExactlyInAnyOrder(true, false);
        assertThat(sentTo(address)).isEqualTo(1);
        assertThat(newsSent(code)).isEqualTo(PanelNews.NUMBER);
        service.sendDue();
        assertThat(sentTo(address)).as("had it: never again").isEqualTo(1);
        assertThat(sentTo(no)).as("no consent: nothing").isZero();
    }

    @Test
    void aFailedSend_isGivenBack_andTheNextSendSendsIt() {
        String address = "failed-" + System.nanoTime() + "@example.com";
        String code = waitingForTheNews(address);
        NewsEmailService service = service();
        sender.answer = email -> Result.FAILED;

        assertThat(service.send(code)).isFalse();
        assertThat(newsSent(code)).isEqualTo(PanelNews.NUMBER - 1);

        sender.answer = email -> Result.SENT;
        service.sendDue();
        assertThat(sentTo(address)).isEqualTo(1);
        assertThat(newsSent(code)).isEqualTo(PanelNews.NUMBER);
        assertThat(service.counts().had()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void consentTakenBackBeforeTheSend_sendsNothing() {
        String address = "gone-" + System.nanoTime() + "@example.com";
        String code = waitingForTheNews(address);
        commands.setNewsEmails(code, false);

        assertThat(service().send(code)).isFalse();
        assertThat(sentTo(address)).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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
}
