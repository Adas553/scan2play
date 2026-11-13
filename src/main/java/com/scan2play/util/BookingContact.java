package com.scan2play.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The DJ's contact for bookings (V67, Pro with the profiles): an e-mail address and a phone number shown to the guests under "Zarezerwuj
 * tego DJ-a na swoją imprezę" as a {@code mailto:} and a {@code tel:} link — the guest writes or calls the DJ straight, Scan2Play keeps
 * nothing of the guest. Both are on a public page, so only plain shapes are kept: the links are built from them and nothing typed can
 * add a header to the e-mail ("?bcc=") or another scheme.
 *
 * @param email   the address as kept, or null
 * @param phone   the number as kept (as the DJ wrote it: "600 100 200", "+48 600-100-200"), or null
 * @param mailto  the e-mail link with the subject filled in, or null
 * @param tel     the phone link ("tel:+48600100200"), or null
 */
public record BookingContact(String email, String phone, String mailto, String tel) {

    /** The longest address kept (the column; RFC 5321's path limit). */
    public static final int EMAIL_MAX = 254;
    /** The longest number kept, as written (the column). */
    public static final int PHONE_MAX = 20;

    /**
     * A name of letters, digits and {@code . _ + -}, a domain of labels with a dot — nothing a mail link would read as more ("%" too:
     * a mail app decodes "%0A" in a {@code mailto:} into a new line).
     */
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._+-]{1,64}@[a-z0-9-]{1,63}(?:\\.[a-z0-9-]{1,63})*\\.[a-z]{2,24}");
    /** Digits with spaces, dashes, dots or brackets between them, a "+" only in front. */
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ().-]+");

    /**
     * The links for the guests; null when the DJ gave neither.
     *
     * @param subject the e-mail's subject ("Zapytanie o rezerwację DJ-a"), in the guest's language; null: none
     */
    public static BookingContact of(String email, String phone, String subject) {
        if (email == null && phone == null) {
            return null;
        }
        String mailto = email == null ? null : subject == null ? "mailto:" + email
                : "mailto:" + email + "?subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20");
        String tel = phone == null ? null : "tel:" + (phone.startsWith("+") ? "+" : "") + phone.replaceAll("[^0-9]", "");
        return new BookingContact(email, phone, mailto, tel);
    }

    /**
     * The address as kept: trimmed, in lower case; null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not a plain address
     */
    public static String email(String typed) {
        String text = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > EMAIL_MAX || !EMAIL.matcher(text).matches() || text.contains("..")) {
            throw new IllegalArgumentException("not an e-mail address: " + typed);
        }
        return text;
    }

    /**
     * The number as kept: as written, trimmed, the spaces between its parts one each; null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not a phone number: other characters, fewer than 6 or more than 15 digits (E.164),
     *                                  or longer than {@link #PHONE_MAX}
     */
    public static String phone(String typed) {
        String text = typed == null ? "" : typed.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        int digits = text.replaceAll("[^0-9]", "").length();
        if (text.length() > PHONE_MAX || !PHONE.matcher(text).matches() || digits < 6 || digits > 15) {
            throw new IllegalArgumentException("not a phone number: " + typed);
        }
        return text;
    }
}
