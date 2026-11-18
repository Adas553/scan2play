package com.scan2play.service;

import com.scan2play.entity.DjAccountEntity;
import com.scan2play.repository.DjAccountRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.EmailAddresses;
import com.scan2play.util.Texts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The login with an e-mail address and a password (V72), beside Google's. What it never does: tell a stranger whether an address has
 * an account — the forms answer the same either way, and what differs goes by e-mail to the address itself. The rules:
 * <ul>
 * <li><b>register</b>: a new address gets an account that cannot log in yet and an e-mail with the confirmation link. An address of a
 *     Google account of the app gets an e-mail saying it logs in with Google (one address, one way to log in); an account confirmed
 *     already, one saying so (with the new password's link). An account not confirmed yet takes the new password and name — the
 *     confirmation asks for the password, so only the person who has the mailbox <i>and</i> typed the password can confirm it.</li>
 * <li><b>confirm</b>: the link's secret and the account's password; then the person is logged in. The link alone changes nothing —
 *     a mail filter that opens links to check them cannot confirm an account.</li>
 * <li><b>log in</b>: a right password of a confirmed account. Of one not confirmed, the link goes again.</li>
 * <li><b>a new password</b>: the link by e-mail, valid {@value #RESET_HOURS} h, once; it confirms the address too (it came to it).</li>
 * </ul>
 * The limits of the wrong passwords and of the e-mails: {@link LoginLimiter}. The e-mails go after the commit, off the request's
 * thread ({@link Mailer#later}): a form never waits for Brevo.
 */
@Service
@Slf4j
public class PasswordAccountService {

    public static final int PASSWORD_MIN = 8;
    /** bcrypt reads 72 bytes; 64 characters of any script fit in them only when they are ASCII — {@link #passwordProblem} checks both. */
    public static final int PASSWORD_MAX = 64;
    private static final int BCRYPT_BYTES = 72;
    static final int VERIFY_HOURS = 48;
    static final int RESET_HOURS = 1;

    /** What a typed password lacks, as a message key's end ({@code account.password.<problem>}); null when it is good. */
    public enum PasswordProblem { SHORT, LONG }

    public enum LoginOutcome {
        OK,
        /** No such account, or a wrong password — the same answer for both. */
        WRONG,
        /** Too many wrong passwords for now ({@link LoginLimiter}). */
        LOCKED,
        /** A right password of an account whose address is not confirmed: the link went again (when the limit let it). */
        NOT_CONFIRMED
    }

    public record LoginResult(LoginOutcome outcome, DjAccountEntity account) {
    }

    /** The e-mails about an account: {@code templates/email/account-<kind>_<pl|en>.html}. */
    enum Mail { CONFIRM, RESET, EXISTS, GOOGLE }

    private final DjAccountRepository accounts;
    private final LoginLimiter limiter;
    private final Mailer mailer;
    private final Clock clock;
    /**
     * A developer's machine without Brevo's key ({@code scan2play.mail.dev-links}, the profile {@code local}: its own address,
     * {@code http://localhost:8080}): the e-mail's link goes to the log instead, on this address — the site's address is
     * production's, where the account is not. Empty (production): never — a link in the log would open the account to whoever reads it.
     */
    private final String devLinks;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the address has no account: a wrong address takes as long as a wrong password. */
    private final String dummyHash = encoder.encode("not-a-password-of-anyone");

    @Autowired
    public PasswordAccountService(DjAccountRepository accounts, LoginLimiter limiter, Mailer mailer,
                                  @Value("${scan2play.mail.dev-links:}") String devLinks) {
        this(accounts, limiter, mailer, Clock.systemUTC(), devLinks);
    }

    PasswordAccountService(DjAccountRepository accounts, LoginLimiter limiter, Mailer mailer, Clock clock) {
        this(accounts, limiter, mailer, clock, "");
    }

    PasswordAccountService(DjAccountRepository accounts, LoginLimiter limiter, Mailer mailer, Clock clock, String devLinks) {
        this.devLinks = devLinks == null ? "" : Texts.withoutTrailingSlash(devLinks.strip());
        this.accounts = accounts;
        this.limiter = limiter;
        this.mailer = mailer;
        this.clock = clock;
    }

    /** What is wrong with a new password, or null. */
    public static PasswordProblem passwordProblem(String password) {
        if (password == null || password.codePointCount(0, password.length()) < PASSWORD_MIN) {
            return PasswordProblem.SHORT;
        }
        if (password.length() > PASSWORD_MAX || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_BYTES) {
            return PasswordProblem.LONG;
        }
        return null;
    }

    /**
     * A new account (or a new password and name of one not confirmed yet) and the e-mail that goes with it — or, for an address that
     * has a confirmed account or a Google one, the e-mail that says so. The caller has checked the address's shape and the password
     * ({@link #passwordProblem}); it answers the same whatever happened here.
     */
    @Transactional
    public void register(String email, String password, String displayName, String client, Locale locale) {
        String name = Texts.oneLine(displayName, DjAccountEntity.DISPLAY_NAME_MAX);
        // hashed whatever follows: an address with an account answers no faster than a new one
        String passwordHash = encoder.encode(password);
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        if (found.isPresent() && found.get().isVerified()) {
            mail(found.get(), Mail.EXISTS, client, locale);
            return;
        }
        if (accounts.googleAccountHas(email)) {
            mailWithoutAccount(email, client, locale);
            return;
        }
        DjAccountEntity account = found.orElseGet(() -> DjAccountEntity.builder().email(email).createdAt(clock.instant()).build());
        account.setPasswordHash(passwordHash);
        account.setDisplayName(name.isEmpty() ? null : name);
        account = accounts.save(account);
        if (found.isEmpty()) {
            log.info("Password account {} registered", account.userId());
        }
        mail(account, Mail.CONFIRM, client, locale);
    }

    /** The account a confirmation link is for, while it is valid. */
    @Transactional(readOnly = true)
    public Optional<DjAccountEntity> toConfirm(String token) {
        return byToken(token, accounts::findByVerifyTokenHash).filter(a -> !a.isVerified() && valid(a.getVerifyExpiresAt()));
    }

    /**
     * The confirmation: the link's account, if the password is its own — confirmed, and the person may be logged in. Empty for a
     * link that is no longer valid, or a wrong password (counted as one, {@link LoginLimiter}).
     */
    @Transactional
    public LoginResult confirm(String token, String password, String client) {
        Optional<DjAccountEntity> found = toConfirm(token);
        if (found.isEmpty()) {
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        DjAccountEntity account = found.get();
        LoginResult checked = check(account, password, client);
        if (checked.outcome() != LoginOutcome.OK) {
            return checked;
        }
        account.setEmailVerifiedAt(clock.instant());
        account.setVerifyTokenHash(null);
        account.setVerifyExpiresAt(null);
        log.info("Password account {} confirmed", account.userId());
        return new LoginResult(LoginOutcome.OK, accounts.save(account));
    }

    /** The login form. */
    @Transactional
    public LoginResult logIn(String email, String password, String client, Locale locale) {
        if (limiter.locked(email, client)) {
            return new LoginResult(LoginOutcome.LOCKED, null);
        }
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        if (found.isEmpty()) {
            matches(password, dummyHash);
            limiter.failed(email, client);
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        LoginResult checked = check(found.get(), password, client);
        if (checked.outcome() == LoginOutcome.OK && !found.get().isVerified()) {
            mail(found.get(), Mail.CONFIRM, client, locale);
            return new LoginResult(LoginOutcome.NOT_CONFIRMED, null);
        }
        return checked;
    }

    private LoginResult check(DjAccountEntity account, String password, String client) {
        if (limiter.locked(account.getEmail(), client)) {
            return new LoginResult(LoginOutcome.LOCKED, null);
        }
        if (!matches(password, account.getPasswordHash())) {
            limiter.failed(account.getEmail(), client);
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        limiter.succeeded(account.getEmail());
        if (encoder.upgradeEncoding(account.getPasswordHash())) {
            account.setPasswordHash(encoder.encode(password));
            accounts.save(account);
        }
        return new LoginResult(LoginOutcome.OK, account);
    }

    /** bcrypt refuses more than 72 bytes: such a password is no account's (none could be set, {@link #passwordProblem}). */
    private boolean matches(String password, String hash) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_BYTES && encoder.matches(password, hash);
    }

    /**
     * "Nie pamiętam hasła": the new password's link to the address's account — or, to an address of a Google account, the e-mail that
     * says it logs in with Google. Nothing for an address the app does not know; the same answer to the form either way.
     */
    @Transactional
    public void forgotPassword(String email, String client, Locale locale) {
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        if (found.isPresent()) {
            mail(found.get(), Mail.RESET, client, locale);
        } else if (accounts.googleAccountHas(email)) {
            mailWithoutAccount(email, client, locale);
        }
    }

    /** The account a new password's link is for, while it is valid. */
    @Transactional(readOnly = true)
    public Optional<DjAccountEntity> toReset(String token) {
        return byToken(token, accounts::findByResetTokenHash).filter(a -> valid(a.getResetExpiresAt()));
    }

    /** The new password (checked by the caller, {@link #passwordProblem}) of the link's account; the link is used up. */
    @Transactional
    public Optional<DjAccountEntity> resetPassword(String token, String password) {
        return toReset(token).map(account -> {
            account.setPasswordHash(encoder.encode(password));
            account.setResetTokenHash(null);
            account.setResetExpiresAt(null);
            if (!account.isVerified()) {   // the link came to the address: it is the person's
                account.setEmailVerifiedAt(clock.instant());
                account.setVerifyTokenHash(null);
                account.setVerifyExpiresAt(null);
            }
            limiter.succeeded(account.getEmail());
            log.info("Password account {}: a new password", account.userId());
            return accounts.save(account);
        });
    }

    // ---------------------------------------------------------------- the e-mails

    /** An e-mail about the account, with a new link where it carries one; nothing when the limit says no. */
    private void mail(DjAccountEntity account, Mail kind, String client, Locale locale) {
        if (!limiter.mayEmail(account.getEmail(), client)) {
            log.info("Password account {}: no {} e-mail, the limit", account.userId(), kind);
            return;
        }
        String token = switch (kind) {
            case CONFIRM -> {
                String secret = newToken();
                account.setVerifyTokenHash(hash(secret));
                account.setVerifyExpiresAt(clock.instant().plus(Duration.ofHours(VERIFY_HOURS)));
                yield secret;
            }
            case RESET, EXISTS -> {
                String secret = newToken();
                account.setResetTokenHash(hash(secret));
                account.setResetExpiresAt(clock.instant().plus(Duration.ofHours(RESET_HOURS)));
                yield secret;
            }
            case GOOGLE -> null;
        };
        accounts.save(account);
        send(account.getEmail(), kind, token, locale);
    }

    /** The e-mail to an address of a Google account: it logs in with Google. */
    private void mailWithoutAccount(String email, String client, Locale locale) {
        if (limiter.mayEmail(email, client)) {
            send(email, Mail.GOOGLE, null, locale);
        }
    }

    private void send(String to, Mail kind, String token, Locale locale) {
        String path = switch (kind) {
            case CONFIRM -> "/register/confirm?token=" + token;
            case RESET, EXISTS -> "/password/reset?token=" + token;
            case GOOGLE -> "/login";
        };
        if (!devLinks.isEmpty() && !mailer.isOn()) {
            log.info("No e-mail on this machine (no BREVO_API_KEY): the {} e-mail's link is {}", kind, devLinks + path);
            return;
        }
        String link = mailer.siteUrl() + path;
        String lang = Mailer.languageOf(locale);
        String template = "account-" + kind.name().toLowerCase(Locale.ROOT);
        AfterCommit.run(() -> mailer.later(() -> {
            String html = mailer.render(template, locale, Map.of("link", link + (link.contains("?") ? "&" : "?") + "lang=" + lang));
            mailer.send(new Email(to, subject(kind, lang), html, Mailer.plainText(html), null));
        }));
    }

    static String subject(Mail kind, String lang) {
        boolean en = "en".equals(lang);
        return switch (kind) {
            case CONFIRM -> en ? "Scan2Play — confirm your e-mail address" : "Scan2Play — potwierdź adres e-mail";
            case RESET -> en ? "Scan2Play — set a new password" : "Scan2Play — ustaw nowe hasło";
            case EXISTS -> en ? "Scan2Play — you have an account already" : "Scan2Play — masz już konto";
            case GOOGLE -> en ? "Scan2Play — you log in with Google" : "Scan2Play — logujesz się przez Google";
        };
    }

    // ---------------------------------------------------------------- the links' secrets

    private boolean valid(Instant expiresAt) {
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    private static Optional<DjAccountEntity> byToken(String token, Function<String, Optional<DjAccountEntity>> find) {
        return token == null || token.isBlank() || token.length() > 100 ? Optional.empty() : find.apply(hash(token));
    }

    /** 32 random bytes, base64url: the secret of a link, sent once and kept only as {@link #hash}. */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The SHA-256 of a link's secret, hex: what the account keeps of it. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The address as an account keeps it, or empty when it is not one ({@link EmailAddresses#clean}). */
    public static Optional<String> cleanEmail(String typed) {
        return EmailAddresses.clean(typed);
    }
}
