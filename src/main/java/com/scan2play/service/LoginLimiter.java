package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The limits of the login with a password (V72), in memory — one instance by design (Section 13); a restart forgets them, which
 * costs an attacker nothing they could not get by waiting. Each is a fixed window from its first count:
 * <ul>
 * <li>wrong passwords of one address: {@value #FAILURES_PER_EMAIL} in {@value #FAILURE_WINDOW_MINUTES} min, then that address waits —
 *     guessing one person's password is slow;</li>
 * <li>wrong passwords from one client address: {@value #FAILURES_PER_CLIENT} in the same window — trying one password on many
 *     addresses is slow too;</li>
 * <li>e-mails to one address (a confirmation, a new password's link, "you have an account already"): one a minute and
 *     {@value #EMAILS_PER_ADDRESS_HOUR} an hour — the forms cannot be used to flood a mailbox;</li>
 * <li>e-mails asked for from one client address: {@value #EMAILS_PER_CLIENT_HOUR} an hour — nor many mailboxes.</li>
 * </ul>
 */
@Component
public class LoginLimiter {

    static final int FAILURES_PER_EMAIL = 10;
    static final int FAILURES_PER_CLIENT = 50;
    static final int FAILURE_WINDOW_MINUTES = 15;
    static final int EMAILS_PER_ADDRESS_HOUR = 5;
    static final int EMAILS_PER_CLIENT_HOUR = 20;
    private static final int MAX_KEYS = 50_000;

    private final Cache<String, AtomicInteger> failuresByEmail;
    private final Cache<String, AtomicInteger> failuresByClient;
    private final Cache<String, AtomicInteger> emailsMinute;
    private final Cache<String, AtomicInteger> emailsHour;
    private final Cache<String, AtomicInteger> emailsByClient;

    public LoginLimiter() {
        this(Ticker.systemTicker());
    }

    LoginLimiter(Ticker ticker) {
        Duration failures = Duration.ofMinutes(FAILURE_WINDOW_MINUTES);
        failuresByEmail = window(failures, ticker);
        failuresByClient = window(failures, ticker);
        emailsMinute = window(Duration.ofMinutes(1), ticker);
        emailsHour = window(Duration.ofHours(1), ticker);
        emailsByClient = window(Duration.ofHours(1), ticker);
    }

    /** A counter's window starts with its first count (expireAfterWrite: counting in place is no write). */
    private static Cache<String, AtomicInteger> window(Duration length, Ticker ticker) {
        return Caffeine.newBuilder().expireAfterWrite(length).maximumSize(MAX_KEYS).ticker(ticker).build();
    }

    private static int count(Cache<String, AtomicInteger> cache, String key) {
        AtomicInteger counter = cache.getIfPresent(key);
        return counter == null ? 0 : counter.get();
    }

    private static int add(Cache<String, AtomicInteger> cache, String key) {
        return cache.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Whether this address, or this client, has used up its wrong passwords for now. */
    public boolean locked(String email, String client) {
        return count(failuresByEmail, email) >= FAILURES_PER_EMAIL || count(failuresByClient, String.valueOf(client)) >= FAILURES_PER_CLIENT;
    }

    public void failed(String email, String client) {
        add(failuresByEmail, email);
        add(failuresByClient, String.valueOf(client));
    }

    /** A right password: the address's wrong ones are forgotten (the client's are not — it may be trying many addresses). */
    public void succeeded(String email) {
        failuresByEmail.invalidate(email);
    }

    /** Whether an e-mail may go to this address, asked from this client — counted when it may. */
    public boolean mayEmail(String email, String client) {
        if (count(emailsMinute, email) >= 1 || count(emailsHour, email) >= EMAILS_PER_ADDRESS_HOUR
                || count(emailsByClient, String.valueOf(client)) >= EMAILS_PER_CLIENT_HOUR) {
            return false;
        }
        add(emailsMinute, email);
        add(emailsHour, email);
        add(emailsByClient, String.valueOf(client));
        return true;
    }
}
