package com.scan2play.service;

/**
 * Sends one e-mail ({@link BrevoEmailSender} on the server; a fake in the tests). Never throws for a refused or failed send — it says
 * how it went: an e-mail is never a reason for a login or a page to fail.
 */
public interface EmailSender {

    /**
     * One e-mail to one person. {@code stopUrl}: the one-click "Nie wysyłaj mi tego" address, also given to the mail program as
     * {@code List-Unsubscribe} (with {@code List-Unsubscribe-Post}, RFC 8058), or null.
     */
    record Email(String to, String subject, String html, String text, String stopUrl) {

        /** The address is a person's: never in a log line. */
        @Override
        public String toString() {
            return "Email[subject=" + subject + "]";
        }
    }

    enum Result {
        SENT,
        /** The service refused the key (401 / 403): wrong, revoked, or expired after 90 days unused — nothing will go until it is changed. */
        KEY_REFUSED,
        /** Any other failure (the network, a 5xx, a refused address): worth trying again later. */
        FAILED,
        /** No key on this server (a developer's machine): nothing is sent. */
        OFF
    }

    Result send(Email email);
}
