package com.scan2play.service;

import com.scan2play.entity.DjAccountEntity;
import com.scan2play.repository.DjAccountRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.PasswordAccountService.LoginOutcome;
import com.scan2play.service.PasswordAccountService.PasswordProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The password accounts (V72): what each form does, what each e-mail carries — and that nothing tells a stranger whether an address
 * has an account. The repository is a map in memory; the e-mails are rendered as "template | link" and sent at once.
 */
class PasswordAccountServiceTest {

    private static final String CLIENT = "203.0.113.7";
    private static final Locale PL = Locale.forLanguageTag("pl");

    private final Map<Long, DjAccountEntity> rows = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final DjAccountRepository repository = mock(DjAccountRepository.class);
    private final Mailer mailer = mock(Mailer.class);
    private final List<Email> sent = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-18T12:00:00Z");
    private boolean googleHasTheAddress;
    private PasswordAccountService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(call -> {
            DjAccountEntity account = call.getArgument(0);
            if (account.getId() == null) {
                account.setId(ids.incrementAndGet());
            }
            rows.put(account.getId(), account);
            return account;
        });
        when(repository.findByEmail(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(a -> a.getEmail().equals(call.getArgument(0))).findFirst());
        when(repository.findByVerifyTokenHash(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(a -> call.getArgument(0).equals(a.getVerifyTokenHash())).findFirst());
        when(repository.findByResetTokenHash(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(a -> call.getArgument(0).equals(a.getResetTokenHash())).findFirst());
        when(repository.googleAccountHas(anyString())).thenAnswer(call -> googleHasTheAddress);
        when(mailer.siteUrl()).thenReturn("https://www.scan2play.com.pl");
        when(mailer.render(anyString(), any(), anyMap())).thenAnswer(call -> call.getArgument(0) + " | " + ((Map<?, ?>) call.getArgument(2)).get("link"));
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(mailer).later(any());
        when(mailer.send(any())).thenAnswer(call -> sent.add(call.getArgument(0)));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        // the limits' windows follow the same clock: "two minutes later" is past the e-mails' minute
        service = new PasswordAccountService(repository, new LoginLimiter(() -> now.toEpochMilli() * 1_000_000L), mailer, clock);
    }

    /** The secret of the link of the last e-mail. */
    private String lastToken() {
        Matcher token = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(sent.getLast().html());
        assertThat(token.find()).as("a link with a secret in " + sent.getLast().html()).isTrue();
        return token.group(1);
    }

    private DjAccountEntity registered(String email, String password) {
        service.register(email, password, "DJ Ola", CLIENT, PL);
        return service.confirm(lastToken(), password, CLIENT).account();
    }

    @Test
    void aNewAccount_cannotLogIn_untilItsLinkAndItsPasswordConfirmIt() {
        service.register("ola@example.com", "dobre-haslo-1", "  DJ   Ola ", CLIENT, PL);

        DjAccountEntity account = rows.get(1L);
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("dobre-haslo-1");
        assertThat(account.getDisplayName()).isEqualTo("DJ Ola");
        assertThat(account.isVerified()).isFalse();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().to()).isEqualTo("ola@example.com");
        assertThat(sent.getFirst().subject()).contains("potwierdź");
        assertThat(sent.getFirst().html()).startsWith("account-confirm | https://www.scan2play.com.pl/register/confirm?token=")
                .endsWith("&lang=pl");
        String token = lastToken();
        assertThat(account.getVerifyTokenHash()).as("only the secret's hash is kept").isEqualTo(PasswordAccountService.hash(token))
                .isNotEqualTo(token);

        assertThat(service.logIn("ola@example.com", "dobre-haslo-1", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.NOT_CONFIRMED);
        assertThat(service.confirm(token, "zle-haslo-123", CLIENT).outcome()).as("the link alone confirms nothing")
                .isEqualTo(LoginOutcome.WRONG);
        assertThat(account.isVerified()).isFalse();

        assertThat(service.confirm(token, "dobre-haslo-1", CLIENT).outcome()).isEqualTo(LoginOutcome.OK);
        assertThat(account.isVerified()).isTrue();
        assertThat(service.toConfirm(token)).as("the link is used up").isEmpty();
        assertThat(service.logIn("ola@example.com", "dobre-haslo-1", CLIENT, PL).account().userId()).isEqualTo("local:1");
    }

    @Test
    void aWrongPassword_andAnUnknownAddress_readTheSame() {
        registered("ola@example.com", "dobre-haslo-1");

        assertThat(service.logIn("ola@example.com", "zle-haslo-123", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.WRONG);
        assertThat(service.logIn("nikt@example.com", "dobre-haslo-1", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.WRONG);
        assertThat(service.logIn("ola@example.com", "x".repeat(200), CLIENT, PL).outcome()).as("longer than bcrypt reads")
                .isEqualTo(LoginOutcome.WRONG);
    }

    @Test
    void tenWrongPasswords_lockTheAddress_evenForTheRightOne() {
        registered("ola@example.com", "dobre-haslo-1");
        for (int i = 0; i < LoginLimiter.FAILURES_PER_EMAIL; i++) {
            service.logIn("ola@example.com", "zle-haslo-" + i, "198.51.100." + i, PL);
        }
        assertThat(service.logIn("ola@example.com", "dobre-haslo-1", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.LOCKED);
    }

    @Test
    void registeringAConfirmedAddressAgain_changesNothing_andTellsItsOwner() {
        DjAccountEntity account = registered("ola@example.com", "dobre-haslo-1");
        String hash = account.getPasswordHash();
        now = now.plus(Duration.ofMinutes(2));   // past the e-mails' minute

        service.register("ola@example.com", "haslo-intruza", "Intruz", CLIENT, PL);

        assertThat(rows).hasSize(1);
        assertThat(account.getPasswordHash()).isEqualTo(hash);
        assertThat(account.getDisplayName()).isEqualTo("DJ Ola");
        assertThat(sent.getLast().subject()).contains("masz już konto");
        assertThat(sent.getLast().html()).startsWith("account-exists | https://www.scan2play.com.pl/password/reset?token=");
    }

    @Test
    void anAddressOfAGoogleAccount_getsNoPasswordAccount_butAnEMailSayingSo() {
        googleHasTheAddress = true;

        service.register("dj@gmail.com", "dobre-haslo-1", null, CLIENT, Locale.ENGLISH);

        assertThat(rows).isEmpty();
        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.subject()).contains("Google");
            assertThat(email.html()).isEqualTo("account-google | https://www.scan2play.com.pl/login?lang=en");
        });
    }

    @Test
    void anAccountNotConfirmed_takesTheNewPassword_andOnlyTheLatestLinkWithItConfirmsIt() {
        service.register("ola@example.com", "haslo-intruza", null, CLIENT, PL);
        String first = lastToken();
        now = now.plus(Duration.ofMinutes(2));
        service.register("ola@example.com", "dobre-haslo-1", "Ola", CLIENT, PL);
        String second = lastToken();

        assertThat(service.toConfirm(first)).as("the first link went with the first password").isEmpty();
        assertThat(service.confirm(second, "haslo-intruza", CLIENT).outcome()).isEqualTo(LoginOutcome.WRONG);
        assertThat(service.confirm(second, "dobre-haslo-1", CLIENT).outcome()).isEqualTo(LoginOutcome.OK);
    }

    @Test
    void theConfirmationLink_endsAfter48Hours() {
        service.register("ola@example.com", "dobre-haslo-1", null, CLIENT, PL);
        String token = lastToken();
        now = now.plus(Duration.ofHours(PasswordAccountService.VERIFY_HOURS)).plusSeconds(1);

        assertThat(service.toConfirm(token)).isEmpty();
        assertThat(service.confirm(token, "dobre-haslo-1", CLIENT).outcome()).isEqualTo(LoginOutcome.WRONG);
    }

    @Test
    void aNewPassword_byTheLinkOfTheEMail_once_withinTheHour() {
        DjAccountEntity account = registered("ola@example.com", "dobre-haslo-1");
        now = now.plus(Duration.ofMinutes(2));

        service.forgotPassword("ola@example.com", CLIENT, PL);
        assertThat(sent.getLast().html()).startsWith("account-reset | https://www.scan2play.com.pl/password/reset?token=");
        String token = lastToken();

        assertThat(service.resetPassword(token, "nowe-haslo-22")).contains(account);
        assertThat(service.resetPassword(token, "inne-haslo-33")).as("once").isEmpty();
        assertThat(service.logIn("ola@example.com", "dobre-haslo-1", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.WRONG);
        assertThat(service.logIn("ola@example.com", "nowe-haslo-22", CLIENT, PL).outcome()).isEqualTo(LoginOutcome.OK);

        now = now.plus(Duration.ofMinutes(2));
        service.forgotPassword("ola@example.com", CLIENT, PL);
        String late = lastToken();
        now = now.plus(Duration.ofHours(PasswordAccountService.RESET_HOURS)).plusSeconds(1);
        assertThat(service.resetPassword(late, "nowe-haslo-44")).as("after the hour").isEmpty();
    }

    @Test
    void aNewPassword_confirmsAnAddressNotConfirmedYet_theLinkCameToIt() {
        service.register("ola@example.com", "zapomniane-1", null, CLIENT, PL);
        now = now.plus(Duration.ofMinutes(2));
        service.forgotPassword("ola@example.com", CLIENT, PL);

        DjAccountEntity account = service.resetPassword(lastToken(), "nowe-haslo-22").orElseThrow();

        assertThat(account.isVerified()).isTrue();
        assertThat(account.getVerifyTokenHash()).isNull();
    }

    @Test
    void anUnknownAddress_getsNothing_andTheMailboxIsNotFlooded() {
        service.forgotPassword("nikt@example.com", CLIENT, PL);
        assertThat(sent).isEmpty();

        registered("ola@example.com", "dobre-haslo-1");
        int before = sent.size();
        for (int i = 0; i < 5; i++) {
            service.forgotPassword("ola@example.com", CLIENT, PL);
        }
        assertThat(sent).as("one a minute").hasSize(before);
        now = now.plus(Duration.ofMinutes(2));
        service.forgotPassword("ola@example.com", CLIENT, PL);
        assertThat(sent).hasSize(before + 1);
    }

    @Test
    void thePasswordsRules() {
        assertThat(PasswordAccountService.passwordProblem(null)).isEqualTo(PasswordProblem.SHORT);
        assertThat(PasswordAccountService.passwordProblem("1234567")).isEqualTo(PasswordProblem.SHORT);
        assertThat(PasswordAccountService.passwordProblem("12345678")).isNull();
        assertThat(PasswordAccountService.passwordProblem("x".repeat(64))).isNull();
        assertThat(PasswordAccountService.passwordProblem("x".repeat(65))).isEqualTo(PasswordProblem.LONG);
        assertThat(PasswordAccountService.passwordProblem("ż".repeat(40))).as("80 bytes: more than bcrypt reads")
                .isEqualTo(PasswordProblem.LONG);
    }

    @Test
    void theEMailIsRenderedInThePagesLanguage() {
        service.register("ola@example.com", "dobre-haslo-1", null, CLIENT, Locale.ENGLISH);

        ArgumentCaptor<Locale> locale = ArgumentCaptor.forClass(Locale.class);
        org.mockito.Mockito.verify(mailer).render(eq("account-confirm"), locale.capture(), anyMap());
        assertThat(locale.getValue()).isEqualTo(Locale.ENGLISH);
        assertThat(sent.getLast().subject()).isEqualTo("Scan2Play — confirm your e-mail address");
        assertThat(Optional.ofNullable(sent.getLast().stopUrl())).isEmpty();
    }
}
