package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The limits of the login with a password (V72), in memory — one instance by design (Section 13); a restart forgets them, which
 * costs an attacker nothing they could not get by waiting. Each is a fixed window from its first count. A client is its address —
 * an IPv6 address by its /64, which one connection owns whole ({@link #clientKey}).
 * <ul>
 * <li>wrong passwords of one address from one client: {@value #FAILURES_PER_EMAIL_AND_CLIENT} in {@value #FAILURE_WINDOW_MINUTES}
 *     min, then that client waits for that address — guessing one person's password is slow, and a stranger's wrong passwords do
 *     not lock the owner out;</li>
 * <li>wrong passwords of one address from all clients: {@value #FAILURES_PER_EMAIL} in the same window, then the address waits —
 *     guessing from many clients is slow too. Locking someone out this way takes that many requests every 15 minutes from
 *     several clients, and "Nie pamiętasz hasła?" still lets the owner in (the new password's link logs in past this lock);</li>
 * <li>wrong passwords from one client: {@value #FAILURES_PER_CLIENT} in the same window — trying one password on many addresses
 *     is slow;</li>
 * <li>e-mails to one address asked from one client: one a minute and {@value #EMAILS_PER_ADDRESS_AND_CLIENT_HOUR} an hour;
 *     from all clients {@value #EMAILS_PER_ADDRESS_HOUR} an hour — except a client's first one of the hour: a stranger asking
 *     again and again cannot keep the owner's own "Nie pamiętasz hasła?" from arriving;</li>
 * <li>e-mails asked from one client: {@value #EMAILS_PER_CLIENT_HOUR} an hour — nor many mailboxes;</li>
 * <li>new accounts from one client: {@value #REGISTRATIONS_PER_CLIENT_HOUR} an hour — each costs a bcrypt hash.</li>
 * </ul>
 */
@Component
public class LoginLimiter {

    static final int FAILURES_PER_EMAIL_AND_CLIENT = 10;
    static final int FAILURES_PER_EMAIL = 50;
    static final int FAILURES_PER_CLIENT = 50;
    static final int FAILURE_WINDOW_MINUTES = 15;
    static final int EMAILS_PER_ADDRESS_AND_CLIENT_HOUR = 3;
    static final int EMAILS_PER_ADDRESS_HOUR = 10;
    static final int EMAILS_PER_CLIENT_HOUR = 20;
    static final int REGISTRATIONS_PER_CLIENT_HOUR = 10;
    private static final int MAX_KEYS = 50_000;

    private final Cache<String, AtomicInteger> failuresByEmailAndClient;
    private final Cache<String, AtomicInteger> failuresByEmail;
    private final Cache<String, AtomicInteger> failuresByClient;
    private final Cache<String, AtomicInteger> emailsMinute;
    private final Cache<String, AtomicInteger> emailsPairHour;
    private final Cache<String, AtomicInteger> emailsHour;
    private final Cache<String, AtomicInteger> emailsByClient;
    private final Cache<String, AtomicInteger> registrationsByClient;

    public LoginLimiter() {
        this(Ticker.systemTicker());
    }

    LoginLimiter(Ticker ticker) {
        Duration failures = Duration.ofMinutes(FAILURE_WINDOW_MINUTES);
        Duration hour = Duration.ofHours(1);
        failuresByEmailAndClient = window(failures, ticker);
        failuresByEmail = window(failures, ticker);
        failuresByClient = window(failures, ticker);
        emailsMinute = window(Duration.ofMinutes(1), ticker);
        emailsPairHour = window(hour, ticker);
        emailsHour = window(hour, ticker);
        emailsByClient = window(hour, ticker);
        registrationsByClient = window(hour, ticker);
    }

    /** A counter's window starts with its first count (expireAfterWrite: counting in place is no write). */
    private static Cache<String, AtomicInteger> window(Duration length, Ticker ticker) {
        return Caffeine.newBuilder().expireAfterWrite(length).maximumSize(MAX_KEYS).ticker(ticker).build();
    }

    private static int count(Cache<String, AtomicInteger> cache, String key) {
        AtomicInteger counter = cache.getIfPresent(key);
        return counter == null ? 0 : counter.get();
    }

    private static void add(Cache<String, AtomicInteger> cache, String key) {
        cache.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * The key of a client address: an IPv4 address as it is; an IPv6 address by its first 64 bits — a provider gives a connection a
     * whole /64, so its addresses are one client. Not an address at all (a header gone wrong): as it is.
     */
    static String clientKey(String client) {
        if (client == null || client.indexOf(':') < 0) {
            return String.valueOf(client);
        }
        try {
            // a literal with ':' is parsed, never looked up
            byte[] bytes = InetAddress.getByName(client.strip()).getAddress();
            return bytes.length == 16 ? HexFormat.of().formatHex(bytes, 0, 8) + "::/64" : client;
        } catch (UnknownHostException | IllegalArgumentException notAnAddress) {
            return client;
        }
    }

    private static String pair(String email, String clientKey) {
        return email + "|" + clientKey;
    }

    /** Whether this client may not try another password of this address for now (its own, the address's or its wrong ones). */
    public boolean locked(String email, String client) {
        String key = clientKey(client);
        return count(failuresByEmailAndClient, pair(email, key)) >= FAILURES_PER_EMAIL_AND_CLIENT
                || count(failuresByEmail, email) >= FAILURES_PER_EMAIL
                || count(failuresByClient, key) >= FAILURES_PER_CLIENT;
    }

    public void failed(String email, String client) {
        String key = clientKey(client);
        add(failuresByEmailAndClient, pair(email, key));
        add(failuresByEmail, email);
        add(failuresByClient, key);
    }

    /** A right password: the address's wrong ones are forgotten (the client's are not — it may be trying many addresses). */
    public void succeeded(String email, String client) {
        failuresByEmailAndClient.invalidate(pair(email, clientKey(client)));
        failuresByEmail.invalidate(email);
    }

    /** A new password set by the e-mail's link: whoever tried this address's old one, it no longer counts. */
    public void passwordChanged(String email) {
        failuresByEmail.invalidate(email);
        failuresByEmailAndClient.asMap().keySet().removeIf(key -> key.startsWith(email + "|"));
    }

    /** Whether an e-mail may go to this address, asked from this client — counted when it may. */
    public boolean mayEmail(String email, String client) {
        String key = clientKey(client);
        String pair = pair(email, key);
        boolean firstOfThePair = count(emailsPairHour, pair) == 0;
        if (count(emailsMinute, pair) >= 1 || count(emailsPairHour, pair) >= EMAILS_PER_ADDRESS_AND_CLIENT_HOUR
                || (!firstOfThePair && count(emailsHour, email) >= EMAILS_PER_ADDRESS_HOUR)
                || count(emailsByClient, key) >= EMAILS_PER_CLIENT_HOUR) {
            return false;
        }
        add(emailsMinute, pair);
        add(emailsPairHour, pair);
        add(emailsHour, email);
        add(emailsByClient, key);
        return true;
    }

    /** Whether this client may make another account now — counted when it may. */
    public boolean mayRegister(String client) {
        String key = clientKey(client);
        if (count(registrationsByClient, key) >= REGISTRATIONS_PER_CLIENT_HOUR) {
            return false;
        }
        add(registrationsByClient, key);
        return true;
    }
}
