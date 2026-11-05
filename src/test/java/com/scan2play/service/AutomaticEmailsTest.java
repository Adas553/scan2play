package com.scan2play.service;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.Plan;
import com.scan2play.model.PlanFeature;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.EmailSender.Result;
import com.scan2play.util.Times;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The automatic e-mails (V64) without a database: the pages made of their templates in both languages, the text part, a send that
 * fails never failing its caller and its claim given back, and who gets the morning summary (Pro, the switch on, an address).
 * "Once" against a real PostgreSQL: {@code AutomaticEmailsIT}.
 */
class AutomaticEmailsTest {

    private static SpringTemplateEngine templates;

    @BeforeAll
    static void engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        templates = new SpringTemplateEngine();
        templates.setTemplateResolver(resolver);
    }

    private final List<Email> sent = new ArrayList<>();
    private Result answer = Result.SENT;
    private final EmailSender sender = email -> {
        sent.add(email);
        return answer;
    };
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final List<Runnable> later = new ArrayList<>();
    private final Mailer mailer = new Mailer(sender, templates(), jdbc, "https://www.scan2play.com.pl/", later::add);

    private static SpringTemplateEngine templates() {
        if (templates == null) {
            engine();
        }
        return templates;
    }

    private static PartySettingsEntity organiser(String language) {
        return PartySettingsEntity.builder().partyCode("ABC12").ownerId("owner").ownerEmail("ola@example.com").ownerName("Ola Kowalska")
                .emailLanguage(language).emailToken("0123456789abcdef0123456789abcdef").build();
    }

    // ---- the mailer ----

    @Test
    void theLanguage_isThePanels_PolishWithoutOne() {
        assertThat(Mailer.languageOf(Locale.forLanguageTag("pl-PL"))).isEqualTo("pl");
        assertThat(Mailer.languageOf(Locale.ENGLISH)).isEqualTo("en");
        assertThat(Mailer.languageOf(Locale.GERMAN)).as("the site's English pages").isEqualTo("en");
        assertThat(Mailer.localeOf(null).getLanguage()).isEqualTo("pl");
        assertThat(Mailer.localeOf("en")).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void aSendThatThrows_isAFailure_neverTheCallers() {
        Mailer throwing = new Mailer(email -> {
            throw new IllegalStateException("boom");
        }, templates(), jdbc, "https://www.scan2play.com.pl", Runnable::run);
        assertThat(throwing.send(new Email("a@example.com", "s", "<p>x</p>", "x", null))).isFalse();
        verify(jdbc).update(eq("UPDATE email_status SET last_failed_at = ?, last_failure = ? WHERE id = 1"), any(Object[].class));
    }

    @Test
    void theTextPart_keepsTheWordsAndTheAddresses() {
        String text = Mailer.plainText("<html><head><title>T</title></head><body><p>Cześć Ola,</p><p>Zobacz: <a href=\"https://x.pl/a?b=1&amp;c=2\">"
                + "instrukcja</a></p><ol><li>A - B</li><li>C - D</li></ol><p>Udanej imprezy!<br>Scan2Play</p></body></html>");
        assertThat(text).isEqualTo("Cześć Ola,\nZobacz: instrukcja (https://x.pl/a?b=1&c=2)\n• A - B\n• C - D\nUdanej imprezy!\nScan2Play\n");
    }

    // ---- the welcome ----

    @Test
    void theWelcome_inPolish_andInEnglish_isTheTextOfTheDoc() {
        WelcomeEmailService service = new WelcomeEmailService(mock(PartySettingsQueryService.class), mock(PartySettingsCommandService.class),
                mock(PartySettingsRepository.class), mailer);

        Email polish = service.email(organiser("pl"));
        assertThat(polish.to()).isEqualTo("ola@example.com");
        assertThat(polish.subject()).isEqualTo("Scan2Play — dzięki za założenie konta");
        assertThat(polish.html()).contains("Cześć Ola,", "dzięki za założenie konta w Scan2Play!", "Twoja odpowiedź trafi prosto do nas.",
                "href=\"https://www.scan2play.com.pl/jak-to-dziala\"", "nie wysyłamy reklam ani newslettera");
        assertThat(polish.html()).as("sent automatically: it does not say a person wrote it").doesNotContain("osobiście");
        // sent once: nothing to stop — its "Nie wysyłaj mi tego" switched the morning summary off, a thing the welcome never names
        assertThat(polish.html()).doesNotContain("Nie wysyłaj mi tego", "/email/stop/");
        assertThat(polish.stopUrl()).as("no List-Unsubscribe either").isNull();
        assertThat(polish.text()).contains("Cześć Ola,", "(https://www.scan2play.com.pl/jak-to-dziala)").doesNotContain("<");

        PartySettingsEntity noName = organiser("en");
        noName.setOwnerName(null);
        Email english = service.email(noName);
        assertThat(english.subject()).isEqualTo("Scan2Play — thanks for signing up");
        assertThat(english.html()).doesNotContain("personally").contains("Hi!", "Your reply comes straight to us.", "href=\"https://www.scan2play.com.pl/how-it-works\"", "no ads, no newsletter")
                .doesNotContain("Don't send me this", "/email/stop/");
        assertThat(english.stopUrl()).isNull();
    }

    @Test
    void theWelcome_isSentLater_onlyWhenItHasNotGone() {
        WelcomeEmailService service = new WelcomeEmailService(mock(PartySettingsQueryService.class), mock(PartySettingsCommandService.class),
                mock(PartySettingsRepository.class), mailer);
        PartySettingsEntity welcomed = organiser("pl").toBuilder().welcomeSentAt(Instant.now()).build();
        service.sendLater(welcomed);
        assertThat(later).isEmpty();
        service.sendLater(organiser("pl"));
        assertThat(later).as("on the e-mails' thread, not the page's").hasSize(1);
        assertThat(sent).isEmpty();
    }

    @Test
    void aWelcomeClaimedElsewhere_isNotSent_aFailedOneIsGivenBack() {
        PartySettingsQueryService queries = mock(PartySettingsQueryService.class);
        PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
        when(queries.getSettings("ABC12")).thenReturn(organiser("pl"));
        WelcomeEmailService service = new WelcomeEmailService(queries, commands, mock(PartySettingsRepository.class), mailer);

        when(commands.claimWelcome(eq("ABC12"), any())).thenReturn(false);
        assertThat(service.send("ABC12")).isFalse();
        assertThat(sent).isEmpty();

        when(commands.claimWelcome(eq("ABC12"), any())).thenReturn(true);
        answer = Result.KEY_REFUSED;
        assertThat(service.send("ABC12")).isFalse();
        verify(commands).releaseWelcome("ABC12");
        verify(jdbc).update(eq("UPDATE email_status SET last_failed_at = ?, last_failure = ? WHERE id = 1"), any(Object[].class));

        answer = Result.SENT;
        assertThat(service.send("ABC12")).isTrue();
        verify(commands).releaseWelcome("ABC12");   // still once
    }

    @Test
    void firstName_isTheFirstWord() {
        assertThat(WelcomeEmailService.firstName("  Ola  Kowalska ")).isEqualTo("Ola");
        assertThat(WelcomeEmailService.firstName(" ")).isNull();
        assertThat(WelcomeEmailService.firstName(null)).isNull();
    }

    // ---- the morning summary ----

    private static final LocalDate EVENING = LocalDate.parse("2026-10-31");
    private static final Instant MORNING = Instant.parse("2026-11-01T09:00:00Z");   // 10:00 Polish time

    private static SongRequestEntity request(long id, String song, int votes, boolean played) {
        Instant at = EVENING.atTime(21, 0).atZone(Times.DISPLAY_ZONE).toInstant().plusSeconds(id * 60);
        return SongRequestEntity.builder().id(id).partyCode("ABC12").songName(song).style("ANY").votes(votes).requestedAt(at)
                .decision(played ? DjService.DECISION_PLAYED : DjService.DECISION_ACCEPTED).playedAt(played ? at.plusSeconds(600) : null)
                .build();
    }

    private record Morning(MorningSummaryEmailService service, EveningSummaryRepository evenings, PlanService plans,
                           PartySettingsQueryService queries) {
    }

    private Morning morning(PartySettingsEntity settings, Plan plan) {
        EveningSummaryRepository evenings = mock(EveningSummaryRepository.class);
        EveningSummaryService summaries = mock(EveningSummaryService.class);
        PartySettingsQueryService queries = mock(PartySettingsQueryService.class);
        PlanService plans = mock(PlanService.class);
        when(queries.getSettings("ABC12")).thenReturn(settings);
        when(plans.allows(any(), eq(PlanFeature.SUMMARY))).thenReturn(plan.includes(PlanFeature.SUMMARY));
        when(evenings.busyParties(any(), any(), eq(MorningSummaryEmailService.MIN_REQUESTS), anyInt())).thenReturn(List.of("ABC12"));
        when(evenings.claimMorning(eq("ABC12"), eq(EVENING), any())).thenReturn(1);
        when(summaries.summarize("ABC12", EVENING)).thenReturn(EveningSummaryService.summarize(EVENING, List.of(
                request(1, "Dawid Podsiadło - Małomiasteczkowy", 9, true), request(2, "Daft Punk - One More Time", 4, true),
                request(3, "Queen - Don't Stop Me Now", 7, false), request(4, "Abba - Dancing Queen", 2, true),
                request(5, "Sanah - Szampan", 1, false))));
        when(summaries.evening("ABC12", EVENING)).thenReturn(Optional.of(EveningSummaryEntity.builder().title("Wesele Ani i Tomka").build()));
        return new Morning(new MorningSummaryEmailService(evenings, summaries, queries, plans, mailer), evenings, plans, queries);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void theMorningSummary_theTitleAndDay_threeNumbers_theTop3_andTheButton() {
        Morning morning = morning(organiser("pl"), Plan.PRO);

        assertThat(morning.service().send(MORNING)).isEqualTo(1);

        verify(morning.evenings()).busyParties(EVENING.atTime(6, 0).atZone(Times.DISPLAY_ZONE).toInstant(),
                EVENING.plusDays(1).atTime(6, 0).atZone(Times.DISPLAY_ZONE).toInstant(), 5, MorningSummaryEmailService.MAX_PARTIES);
        Email email = sent.getFirst();
        assertThat(email.subject()).isEqualTo("Wesele Ani i Tomka — podsumowanie wieczoru, 31.10");
        String html = email.html();
        assertThat(html).contains(">Wesele Ani i Tomka<", ">sobota, 31 października 2026<", ">5<", ">3<", ">prośby<", ">zagrane<",
                ">głosy gości<", "href=\"https://www.scan2play.com.pl/dj/summary?evening=2026-10-31&amp;party=ABC12\"", "Nie wysyłaj mi tego");
        assertThat(html.indexOf("Małomiasteczkowy")).isLessThan(html.indexOf("Don&#39;t Stop Me Now"));
        assertThat(html.indexOf("Don&#39;t Stop Me Now")).isLessThan(html.indexOf("One More Time"));
        assertThat(html).doesNotContain("Dancing Queen", "Szampan");
        assertThat(email.stopUrl()).endsWith("?lang=pl");
        verify(morning.evenings(), never()).releaseMorning(anyString(), any());
    }

    @Test
    void noMorningSummary_withoutPro_switchedOff_orWithoutAnAddress() {
        Morning free = morning(organiser("pl"), Plan.FREE);
        assertThat(free.service().send(MORNING)).isZero();
        verify(free.evenings(), never()).claimMorning(anyString(), any(), any());

        PartySettingsEntity off = organiser("pl");
        off.setMorningSummary(false);
        Morning switchedOff = morning(off, Plan.PRO);
        assertThat(switchedOff.service().send(MORNING)).isZero();
        verify(switchedOff.evenings(), never()).claimMorning(anyString(), any(), any());

        PartySettingsEntity noAddress = organiser("pl");
        noAddress.setOwnerEmail(null);
        Morning nobody = morning(noAddress, Plan.PRO);
        assertThat(nobody.service().send(MORNING)).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void aMorningSummaryClaimedElsewhere_isNotSent_aFailedOneIsGivenBack() {
        Morning taken = morning(organiser("en"), Plan.PRO);
        when(taken.evenings().claimMorning(eq("ABC12"), eq(EVENING), any())).thenReturn(0);
        assertThat(taken.service().send(MORNING)).isZero();
        assertThat(sent).isEmpty();

        Morning failing = morning(organiser("en"), Plan.PRO);
        answer = Result.FAILED;
        assertThat(failing.service().send(MORNING)).isZero();
        verify(failing.evenings()).releaseMorning("ABC12", EVENING);
        assertThat(sent.getFirst().html()).contains(">Saturday, 31 October 2026<", ">requests<", "Don't send me this");
    }
}
