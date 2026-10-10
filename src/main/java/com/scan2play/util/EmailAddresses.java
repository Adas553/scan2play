package com.scan2play.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The e-mail address of an invitation to a party's staff (V34): the organiser types it, the login's verified address is matched
 * against it. No e-mail is ever sent to it.
 */
public final class EmailAddresses {

    /** The longest address (RFC 5321's path limit; the column). */
    public static final int MAX = 254;

    /** One "@", something before it, a domain with a dot after it, no spaces — a typo check, not RFC 5322. */
    private static final Pattern SHAPE = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");

    private EmailAddresses() {
    }

    /** The address as typed, trimmed and in lower case — or empty when it is not one ("ola", "ola@gmail", two "@", too long). */
    public static Optional<String> clean(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String address = typed.strip().toLowerCase(Locale.ROOT);
        return address.length() <= MAX && SHAPE.matcher(address).matches() ? Optional.of(address) : Optional.empty();
    }

    /**
     * The address as an account is matched by it: lower case, and for Gmail as Gmail delivers it — dots in the name and a "+…" after
     * it do not count, "googlemail.com" is "gmail.com" (the organiser types "Ola.Kowalska@gmail.com", Google says
     * "olakowalska@gmail.com"). Other domains as they are: whether they ignore dots is theirs to say.
     */
    public static String key(String address) {
        String lower = address.strip().toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf('@');
        if (at <= 0) {
            return lower;
        }
        String name = lower.substring(0, at);
        String domain = lower.substring(at + 1);
        if (!domain.equals("gmail.com") && !domain.equals("googlemail.com")) {
            return lower;
        }
        int plus = name.indexOf('+');
        if (plus >= 0) {
            name = name.substring(0, plus);
        }
        return name.replace(".", "") + "@gmail.com";
    }
}
