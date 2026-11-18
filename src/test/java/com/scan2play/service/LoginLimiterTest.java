package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits of the password login (V72): a stranger's wrong passwords lock only the stranger, many clients together lock the
 * address, an IPv6 client is its /64, and the owner's own "Nie pamiętasz hasła?" arrives however often a stranger asks.
 */
class LoginLimiterTest {

    private static final String OLA = "ola@example.com";
    private final AtomicLong nanos = new AtomicLong();
    private final LoginLimiter limiter = new LoginLimiter(nanos::get);

    private void later(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void aStrangersWrongPasswords_lockTheStranger_notTheOwner() {
        for (int i = 0; i < LoginLimiter.FAILURES_PER_EMAIL_AND_CLIENT; i++) {
            limiter.failed(OLA, "198.51.100.1");
        }
        assertThat(limiter.locked(OLA, "198.51.100.1")).isTrue();
        assertThat(limiter.locked(OLA, "203.0.113.7")).as("the owner, elsewhere").isFalse();
        assertThat(limiter.locked("inna@example.com", "198.51.100.1")).as("another address, the same client").isFalse();

        later(Duration.ofMinutes(LoginLimiter.FAILURE_WINDOW_MINUTES).plusSeconds(1));
        assertThat(limiter.locked(OLA, "198.51.100.1")).isFalse();
    }

    @Test
    void manyClientsTogether_lockTheAddress_untilANewPasswordIsSet() {
        for (int i = 0; i < LoginLimiter.FAILURES_PER_EMAIL; i++) {
            limiter.failed(OLA, "198.51.100." + i);
        }
        assertThat(limiter.locked(OLA, "203.0.113.7")).isTrue();

        limiter.passwordChanged(OLA);
        assertThat(limiter.locked(OLA, "203.0.113.7")).isFalse();
        assertThat(limiter.locked(OLA, "198.51.100.1")).isFalse();
    }

    @Test
    void oneClient_tryingManyAddresses_isLocked() {
        for (int i = 0; i < LoginLimiter.FAILURES_PER_CLIENT; i++) {
            limiter.failed("dj" + i + "@example.com", "198.51.100.1");
        }
        assertThat(limiter.locked("nowy@example.com", "198.51.100.1")).isTrue();
    }

    @Test
    void anIpv6Client_isItsSlash64() {
        assertThat(LoginLimiter.clientKey("2001:db8:1:2:aaaa::1")).isEqualTo(LoginLimiter.clientKey("2001:db8:1:2:bbbb:cccc:dddd:eeee"))
                .isNotEqualTo(LoginLimiter.clientKey("2001:db8:1:3::1"));
        assertThat(LoginLimiter.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(LoginLimiter.clientKey("not:an:address:zz")).isEqualTo("not:an:address:zz");
        assertThat(LoginLimiter.clientKey("zz:1")).as("not a literal's characters: never a name to look up").isEqualTo("zz:1");
        assertThat(LoginLimiter.clientKey(null)).isEqualTo("null");

        for (int i = 0; i < LoginLimiter.FAILURES_PER_EMAIL_AND_CLIENT; i++) {
            limiter.failed(OLA, "2001:db8:1:2::" + Integer.toHexString(i + 1));
        }
        assertThat(limiter.locked(OLA, "2001:db8:1:2::ffff")).as("another address of the same /64").isTrue();
    }

    @Test
    void theEMails_oneAMinute_aFewAnHour_butAStrangerCannotStopTheOwnersOwn() {
        assertThat(limiter.mayEmail(OLA, "198.51.100.1")).isTrue();
        assertThat(limiter.mayEmail(OLA, "198.51.100.1")).as("the same minute").isFalse();
        for (int i = 1; i < LoginLimiter.EMAILS_PER_ADDRESS_AND_CLIENT_HOUR; i++) {
            later(Duration.ofMinutes(2));
            assertThat(limiter.mayEmail(OLA, "198.51.100.1")).isTrue();
        }
        later(Duration.ofMinutes(2));
        assertThat(limiter.mayEmail(OLA, "198.51.100.1")).as("the client's hour is used up").isFalse();

        // strangers fill the address's hour (each client's first one of the hour goes: a mailbox gets one e-mail a client) …
        for (int i = 2; i < 2 + LoginLimiter.EMAILS_PER_ADDRESS_HOUR; i++) {
            assertThat(limiter.mayEmail(OLA, "198.51.100." + i)).isTrue();
        }
        // … the owner's first of the hour still goes, a second one not
        assertThat(limiter.mayEmail(OLA, "203.0.113.7")).isTrue();
        later(Duration.ofMinutes(2));
        assertThat(limiter.mayEmail(OLA, "203.0.113.7")).isFalse();

        later(Duration.ofHours(1));
        assertThat(limiter.mayEmail(OLA, "203.0.113.7")).isTrue();
    }

    /** Many clients (an IPv6 /56 is 256 of them), each with its first e-mail of the hour: one mailbox gets a day's ceiling at most. */
    @Test
    void manyClients_cannotFloodOneMailbox() {
        int sent = 0;
        for (int i = 0; i < 256; i++) {
            if (limiter.mayEmail(OLA, "2001:db8:0:" + Integer.toHexString(i) + "::1")) {
                sent++;
            }
        }
        assertThat(sent).isEqualTo(LoginLimiter.EMAILS_PER_ADDRESS_DAY);

        later(Duration.ofDays(1));
        assertThat(limiter.mayEmail(OLA, "203.0.113.7")).as("the next day").isTrue();
    }

    /** Account e-mails together stop at their day's ceiling, before Brevo's — the app's other e-mails keep going. */
    @Test
    void allTheAccountEMails_stopAtTheDaysCeiling() {
        int sent = 0;
        for (int i = 0; i < LoginLimiter.ACCOUNT_EMAILS_DAY + 20; i++) {
            if (limiter.mayEmail("dj" + i + "@example.com", "2001:db8:" + Integer.toHexString(i) + "::1")) {
                sent++;
            }
        }
        assertThat(sent).isEqualTo(LoginLimiter.ACCOUNT_EMAILS_DAY);
    }

    @Test
    void oneClient_asksForAFewEMailsAnHour_andMakesAFewAccounts() {
        for (int i = 0; i < LoginLimiter.EMAILS_PER_CLIENT_HOUR; i++) {
            assertThat(limiter.mayEmail("dj" + i + "@example.com", "198.51.100.1")).isTrue();
        }
        assertThat(limiter.mayEmail("nowy@example.com", "198.51.100.1")).isFalse();

        for (int i = 0; i < LoginLimiter.REGISTRATIONS_PER_CLIENT_HOUR; i++) {
            assertThat(limiter.mayRegister("198.51.100.1")).isTrue();
        }
        assertThat(limiter.mayRegister("198.51.100.1")).isFalse();
        assertThat(limiter.mayRegister("203.0.113.7")).isTrue();
    }
}
