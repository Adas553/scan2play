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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The login with an e-mail address and a password (V72), beside Google's. What it never does: tell a stranger whether an address has
 * an account — not by the forms' answers, not by how long they take: "Załóż konto" and "Nie pamiętam hasła" check the client's limit
 * and answer at once, their work (a bcrypt hash, the lookups, the e-mail) runs on a thread of its own ({@link #inBackground}); the
 * login takes one bcrypt comparison whatever the address, and a right password of an account not confirmed yet reads as a wrong one.
 * The rules:
 * <ul>
 * <li><b>register</b>: a new address gets an account that cannot log in yet and an e-mail with the confirmation link. An address of a
 *     Google account of the app gets an e-mail saying it logs in with Google (one address, one way to log in); an account confirmed
 *     already, one saying so (with the new password's link). An account waiting for its confirmation (its link still valid) keeps its
 *     password — whoever registers it again gets the new password's link, which confirms it (it came to the address); one whose
 *     link has expired takes the new password and name. Nothing changes without its e-mail ({@link LoginLimiter#mayEmail}).</li>
 * <li><b>confirm</b>: the link's secret and the account's password; then the person is logged in. The link alone changes nothing —
 *     a mail filter that opens links to check them cannot confirm an account.</li>
 * <li><b>log in</b>: a right password of a confirmed account. Of one not confirmed: the answer of a wrong password (counted as one),
 *     and the link goes again.</li>
 * <li><b>a new password</b>: the link by e-mail, valid {@value #RESET_HOURS} h, once; it confirms the address too (it came to it).</li>
 * </ul>
 * A link is used up by one {@code UPDATE} of its row ({@link DjAccountRepository#useResetToken}): two requests with it at once,
 * one goes. The limits of the wrong passwords and of the e-mails: {@link LoginLimiter}. The e-mails go after the commit.
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
    /** One thread for the forms' work: a burst of them costs one core at most; a full queue drops the task (the form answered already). */
    private static final int QUEUE = 50;

    /** What a typed password lacks, as a message key's end ({@code account.password.<problem>}); null when it is good. */
    public enum PasswordProblem { SHORT, LONG }

    public enum LoginOutcome {
        OK,
        /** No such account, a wrong password, or a right one of an account not confirmed yet — the same answer for all. */
        WRONG,
        /** Too many wrong passwords for now ({@link LoginLimiter}). */
        LOCKED
    }

    public record LoginResult(LoginOutcome outcome, DjAccountEntity account) {
    }

    /** The e-mails about an account: {@code templates/email/account-<kind>_<pl|en>.html}. */
    enum Mail { CONFIRM, RESET, EXISTS, PENDING, GOOGLE }

    private final DjAccountRepository accounts;
    private final LoginLimiter limiter;
    private final Mailer mailer;
    private final Clock clock;
    private final Executor background;
    private final TransactionOperations transactions;
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
                                  @Value("${scan2play.mail.dev-links:}") String devLinks, PlatformTransactionManager transactionManager) {
        this(accounts, limiter, mailer, Clock.systemUTC(), devLinks,
                new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
                        Thread.ofPlatform().name("account-", 1).daemon().factory()),
                new TransactionTemplate(transactionManager));
    }

    /** For the tests: the forms' work at once, without a transaction. */
    PasswordAccountService(DjAccountRepository accounts, LoginLimiter limiter, Mailer mailer, Clock clock) {
        this(accounts, limiter, mailer, clock, "", Runnable::run, TransactionOperations.withoutTransaction());
    }

    PasswordAccountService(DjAccountRepository accounts, LoginLimiter limiter, Mailer mailer, Clock clock, String devLinks,
                           Executor background, TransactionOperations transactions) {
        this.devLinks = devLinks == null ? "" : Texts.withoutTrailingSlash(devLinks.strip());
        this.accounts = accounts;
        this.limiter = limiter;
        this.mailer = mailer;
        this.clock = clock;
        this.background = background;
        this.transactions = transactions;
    }

    /** What is wrong with a new password, or null. */
    public static PasswordProblem passwordProblem(String password) {
        String typed = normalized(password);
        if (typed == null || typed.codePointCount(0, typed.length()) < PASSWORD_MIN) {
            return PasswordProblem.SHORT;
        }
        if (typed.length() > PASSWORD_MAX || typed.getBytes(StandardCharsets.UTF_8).length > BCRYPT_BYTES) {
            return PasswordProblem.LONG;
        }
        return null;
    }

    /** A password as it is kept and compared: "ą" typed as one character or as "a" and its mark is the same password (NFC). */
    private static String normalized(String password) {
        return password == null ? null : Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    // ---------------------------------------------------------------- the forms that answer "Sprawdź skrzynkę"

    /**
     * "Załóż konto": the client's limit of new accounts, then {@link #register} on the forms' thread. The caller has checked the
     * address's shape and the password ({@link #passwordProblem}); it answers the same whatever happens.
     */
    public void requestRegistration(String email, String password, String displayName, String client, Locale locale) {
        if (!limiter.mayRegister(client)) {
            log.info("Password account: no registration, the client's limit");
            return;
        }
        inBackground(() -> register(email, password, displayName, client, locale));
    }

    /** "Nie pamiętam hasła": {@link #forgotPassword} on the forms' thread; the caller answers the same whatever happens. */
    public void requestPasswordReset(String email, String client, Locale locale) {
        inBackground(() -> forgotPassword(email, client, locale));
    }

    /**
     * A form's work off the request's thread, in a transaction of its own. Two registrations of one new address at once: the
     * second one's insert breaks the unique index — nothing more to do, the first one's e-mail goes.
     */
    private void inBackground(Runnable work) {
        try {
            background.execute(() -> {
                try {
                    transactions.executeWithoutResult(status -> work.run());
                } catch (DataIntegrityViolationException twoAtOnce) {
                    log.info("Password account: the same address twice at once — the first one counts");
                } catch (RuntimeException e) {
                    log.warn("Password account: a form's work failed: {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Password account: a form's work dropped (the queue is full)");
        }
    }

    /** What "Załóż konto" does (see the class); on the forms' thread, in its transaction. */
    void register(String email, String password, String displayName, String client, Locale locale) {
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        if (found.isPresent() && found.get().isVerified()) {
            if (limiter.mayEmail(email, client)) {
                mail(found.get(), Mail.EXISTS, locale);
            }
            return;
        }
        if (accounts.googleAccountHas(email)) {
            if (limiter.mayEmail(email, client)) {
                send(email, Mail.GOOGLE, null, locale);
            }
            return;
        }
        if (found.isPresent() && valid(found.get().getVerifyExpiresAt())) {
            // its password stays: a stranger registering the address again cannot spoil the owner's link
            if (limiter.mayEmail(email, client)) {
                mail(found.get(), Mail.PENDING, locale);
            }
            return;
        }
        if (!limiter.mayEmail(email, client)) {
            return;   // nothing changes without its e-mail
        }
        String name = Texts.oneLine(displayName, DjAccountEntity.DISPLAY_NAME_MAX);
        DjAccountEntity account = found.orElseGet(() -> DjAccountEntity.builder().email(email).createdAt(clock.instant()).build());
        account.setPasswordHash(encoder.encode(normalized(password)));
        account.setDisplayName(name.isEmpty() ? null : name);
        account = accounts.save(account);
        if (found.isEmpty()) {
            log.info("Password account {} registered", account.userId());
        }
        mail(account, Mail.CONFIRM, locale);
    }

    /** What "Nie pamiętam hasła" does: the new password's link to the address's account, or "you log in with Google", or nothing. */
    void forgotPassword(String email, String client, Locale locale) {
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        if (found.isPresent()) {
            if (limiter.mayEmail(email, client)) {
                mail(found.get(), Mail.RESET, locale);
            }
        } else if (accounts.googleAccountHas(email) && limiter.mayEmail(email, client)) {
            send(email, Mail.GOOGLE, null, locale);
        }
    }

    // ---------------------------------------------------------------- the confirmation

    /** The account a confirmation link is for, while it is valid. */
    @Transactional(readOnly = true)
    public Optional<DjAccountEntity> toConfirm(String token) {
        return byToken(token, accounts::findByVerifyTokenHash).filter(a -> !a.isVerified() && valid(a.getVerifyExpiresAt()));
    }

    /**
     * The confirmation: the link's account, if the password is its own — confirmed, and the person may be logged in. WRONG for a link
     * that is no longer valid (or used meanwhile), or a wrong password (counted as one, {@link LoginLimiter}).
     */
    @Transactional
    public LoginResult confirm(String token, String password, String client) {
        Optional<DjAccountEntity> found = toConfirm(token);
        if (found.isEmpty()) {
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        DjAccountEntity account = found.get();
        if (limiter.locked(account.getEmail(), client)) {
            return new LoginResult(LoginOutcome.LOCKED, null);
        }
        Match match = check(password, account.getPasswordHash());
        if (match == Match.NONE) {
            limiter.failed(account.getEmail(), client);
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        if (accounts.useVerifyToken(account.getId(), hash(token)) == 0) {
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        limiter.succeeded(account.getEmail(), client);
        account.setEmailVerifiedAt(clock.instant());
        account.setVerifyTokenHash(null);
        account.setVerifyExpiresAt(null);
        upgrade(account, password, match);
        log.info("Password account {} confirmed", account.userId());
        return new LoginResult(LoginOutcome.OK, accounts.save(account));
    }

    // ---------------------------------------------------------------- the login

    /** The login form: one bcrypt comparison whatever the address. */
    @Transactional
    public LoginResult logIn(String email, String password, String client, Locale locale) {
        if (limiter.locked(email, client)) {
            return new LoginResult(LoginOutcome.LOCKED, null);
        }
        Optional<DjAccountEntity> found = accounts.findByEmail(email);
        Match match = check(password, found.map(DjAccountEntity::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || match == Match.NONE) {
            limiter.failed(email, client);
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        DjAccountEntity account = found.get();
        if (!account.isVerified()) {
            // read as a wrong password and counted as one — "not confirmed" would say the address has an account (a stranger can
            // register any address with a password of their own); the link goes again, off the request's thread
            limiter.failed(email, client);
            Long id = account.getId();
            inBackground(() -> resendConfirmation(id, client, locale));
            return new LoginResult(LoginOutcome.WRONG, null);
        }
        limiter.succeeded(email, client);
        upgrade(account, password, match);
        return new LoginResult(LoginOutcome.OK, account);
    }

    private void resendConfirmation(long id, String client, Locale locale) {
        accounts.findById(id).filter(account -> !account.isVerified())
                .filter(account -> limiter.mayEmail(account.getEmail(), client))
                .ifPresent(account -> mail(account, Mail.CONFIRM, locale));
    }

    /**
     * A hash of an older kind (or strength), or of the password as typed rather than in NFC ({@link Match#AS_TYPED}), is made
     * again from the right password.
     */
    private void upgrade(DjAccountEntity account, String password, Match match) {
        if (match == Match.AS_TYPED || encoder.upgradeEncoding(account.getPasswordHash())) {
            account.setPasswordHash(encoder.encode(normalized(password)));
            accounts.save(account);
        }
    }

    /**
     * How a typed password matched a hash. {@code AS_TYPED}: not in its NFC form but as typed — a hash kept before passwords were
     * normalized, of a password typed decomposed; its owner must not be locked out by the normalization.
     */
    private enum Match { NONE, NFC, AS_TYPED }

    /**
     * bcrypt refuses more than 72 bytes: such a password is no account's (none could be set, {@link #passwordProblem}). The second
     * comparison happens only for a password that is not in NFC — what was typed decides it, never whether the address has an
     * account.
     */
    private Match check(String password, String hash) {
        String typed = normalized(password);
        if (typed == null || typed.getBytes(StandardCharsets.UTF_8).length > BCRYPT_BYTES) {
            encoder.matches("not-a-password-of-anyone", dummyHash);   // as long as any other answer
            return Match.NONE;
        }
        if (encoder.matches(typed, hash)) {
            return Match.NFC;
        }
        boolean decomposed = !typed.equals(password) && password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_BYTES;
        return decomposed && encoder.matches(password, hash) ? Match.AS_TYPED : Match.NONE;
    }

    // ---------------------------------------------------------------- the new password

    /** The account a new password's link is for, while it is valid. */
    @Transactional(readOnly = true)
    public Optional<DjAccountEntity> toReset(String token) {
        return byToken(token, accounts::findByResetTokenHash).filter(a -> valid(a.getResetExpiresAt()));
    }

    /** The new password (checked by the caller, {@link #passwordProblem}) of the link's account; the link is used up. */
    @Transactional
    public Optional<DjAccountEntity> resetPassword(String token, String password) {
        return toReset(token).filter(account -> accounts.useResetToken(account.getId(), hash(token)) == 1).map(account -> {
            account.setPasswordHash(encoder.encode(normalized(password)));
            account.setResetTokenHash(null);
            account.setResetExpiresAt(null);
            if (!account.isVerified()) {   // the link came to the address: it is the person's
                account.setEmailVerifiedAt(clock.instant());
                account.setVerifyTokenHash(null);
                account.setVerifyExpiresAt(null);
            }
            limiter.passwordChanged(account.getEmail());
            log.info("Password account {}: a new password", account.userId());
            return accounts.save(account);
        });
    }

    // ---------------------------------------------------------------- the e-mails

    /** An e-mail about the account, with a new link where it carries one; the caller has asked the limit. */
    private void mail(DjAccountEntity account, Mail kind, Locale locale) {
        String token = newToken();
        if (kind == Mail.CONFIRM) {
            account.setVerifyTokenHash(hash(token));
            account.setVerifyExpiresAt(clock.instant().plus(Duration.ofHours(VERIFY_HOURS)));
        } else {
            account.setResetTokenHash(hash(token));
            account.setResetExpiresAt(clock.instant().plus(Duration.ofHours(RESET_HOURS)));
        }
        accounts.save(account);
        send(account.getEmail(), kind, token, locale);
    }

    private void send(String to, Mail kind, String token, Locale locale) {
        String path = switch (kind) {
            case CONFIRM -> "/register/confirm?token=" + token;
            case RESET, EXISTS, PENDING -> "/password/reset?token=" + token;
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
            case PENDING -> en ? "Scan2Play — finish your account" : "Scan2Play — dokończ zakładanie konta";
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
