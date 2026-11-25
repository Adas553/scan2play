package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PanelNews;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.EmailSender.Result;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The news e-mail (V80) without a database: its page in both languages with "Nie wysyłaj mi nowości" and {@code List-Unsubscribe}, a
 * send only to whom the claim lets it, a failed send's claim given back, one send at a time, and the owner's try. "Once" and "only
 * with the consent" against a real PostgreSQL: {@code NewsEmailsIT}.
 */
class NewsEmailServiceTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private final List<Email> sent = new ArrayList<>();
    private Result answer = Result.SENT;
    private final List<Runnable> later = new ArrayList<>();
    private final Mailer mailer = new Mailer(email -> {
        sent.add(email);
        return answer;
    }, templates(), mock(JdbcTemplate.class), "https://www.scan2play.com.pl/", later::add);
    private final PartySettingsQueryService queries = mock(PartySettingsQueryService.class);
    private final PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
    private final PartySettingsRepository repository = mock(PartySettingsRepository.class);
    private final NewsEmailService service = new NewsEmailService(queries, commands, repository, mailer);

    private static SpringTemplateEngine templates() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static PartySettingsEntity organiser(String code, String language) {
        return PartySettingsEntity.builder().partyCode(code).ownerId("o-" + code).ownerEmail(code.toLowerCase() + "@example.com")
                .ownerName("Ola Kowalska").emailLanguage(language).emailToken(TOKEN).build();
    }

    @Test
    void thePolishEmail_saysTheThreeThings_andHowToStopIt() {
        Email email = service.email(organiser("ABC12", "pl"), "abc12@example.com");

        String stop = "https://www.scan2play.com.pl/email/stop/" + TOKEN + "?lang=pl&what=news";
        assertThat(email.to()).isEqualTo("abc12@example.com");
        assertThat(email.subject()).isEqualTo("Scan2Play — nowości: Zagrane samo, Pytanie do gości, Plan imprezy");
        assertThat(email.stopUrl()).as("the mail program's own \"Unsubscribe\" too").isEqualTo(stop);
        assertThat(email.html()).contains("Cześć Ola,", "🎧 Zagrane samo.", "🗳 Pytanie do gości.", "📋 Plan imprezy.",
                "href=\"https://www.scan2play.com.pl/jak-to-dziala#deck\"", "href=\"https://www.scan2play.com.pl/jak-to-dziala#guidePolls\"",
                "href=\"https://www.scan2play.com.pl/jak-to-dziala#guidePlan\"", "href=\"https://www.scan2play.com.pl/dj/dashboard\"",
                "„Nowości mailem”", "href=\"" + stop.replace("&", "&amp;") + "\"", ">Nie wysyłaj mi nowości<");
        assertThat(email.text()).contains("Nie wysyłaj mi nowości (" + stop + ")").doesNotContain("<");
    }

    @Test
    void theEnglishEmail_isInTheirPanelsLanguage() {
        Email email = service.email(organiser("ABC12", "en"), "abc12@example.com");

        assertThat(email.subject()).startsWith("Scan2Play — new:");
        assertThat(email.stopUrl()).endsWith("?lang=en&what=news");
        assertThat(email.html()).contains("Hi Ola,", "🎧 Played by itself.", "href=\"https://www.scan2play.com.pl/how-it-works#guidePlan\"",
                "Stop the e-mails of what");
    }

    @Test
    void theSend_goesToWhomTheClaimLets_andGivesAFailedOneBack() {
        when(repository.newsDue(PanelNews.NUMBER, NewsEmailService.MAX_PER_SEND)).thenReturn(List.of("AAA11", "BBB22", "CCC33"));
        when(queries.getSettings("AAA11")).thenReturn(organiser("AAA11", "pl"));
        when(queries.getSettings("BBB22")).thenReturn(organiser("BBB22", "pl"));
        when(commands.claimNews("AAA11", PanelNews.NUMBER)).thenReturn(true);
        when(commands.claimNews("BBB22", PanelNews.NUMBER)).thenReturn(true);
        when(commands.claimNews("CCC33", PanelNews.NUMBER)).thenReturn(false);   // another send had it, or the consent is gone

        assertThat(service.sendDue()).isEqualTo(2);
        assertThat(sent).extracting(Email::to).containsExactly("aaa11@example.com", "bbb22@example.com");
        verify(commands, never()).releaseNews(anyString(), anyInt());

        sent.clear();
        answer = Result.FAILED;
        when(repository.newsDue(PanelNews.NUMBER, NewsEmailService.MAX_PER_SEND)).thenReturn(List.of("AAA11"));
        assertThat(service.sendDue()).isZero();
        verify(commands).releaseNews("AAA11", PanelNews.NUMBER);
    }

    @Test
    void oneSendAtATime_onTheEmailsThread() {
        assertThat(service.sendLater()).isTrue();
        assertThat(service.isSending()).isTrue();
        assertThat(service.sendLater()).as("a second click while the first is on its way").isFalse();
        assertThat(later).hasSize(1);

        when(repository.newsDue(anyInt(), anyInt())).thenReturn(List.of());
        later.getFirst().run();
        assertThat(service.isSending()).isFalse();
        assertThat(service.sendLater()).isTrue();
    }

    @Test
    void theOwnersTry_goesToTheirAddress_withoutAName() {
        assertThat(service.sendTry("  me@example.com ", "en")).isTrue();
        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("me@example.com");
            assertThat(email.html()).contains("Hi!");
        });
        assertThat(service.sendTry("", "pl")).as("no ADMIN_EMAIL").isFalse();
        assertThat(service.previewText("pl")).contains("Cześć!", "Zagrane samo").doesNotContain("<");
    }
}
