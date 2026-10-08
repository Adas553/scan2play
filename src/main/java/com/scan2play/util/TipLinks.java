package com.scan2play.util;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The DJ's tip link (V27): the DJ's own page on a service that takes tips — Revolut, PayPal, buycoffee.to, Suppi, Tipply, Buy Me a
 * Coffee, Ko-fi. The guests pay there, straight to the DJ: Scan2Play only shows the button. The DJ pastes the link the service gave
 * them ("revolut.me/djkoko", with or without https://); the app keeps an https address on one of {@link #SITES} with a plain path
 * and nothing else (no query, no login part, no port) — the guest page links only there, never to any address typed in.
 */
public final class TipLinks {

    /** The services, by their host (a "www." or "m." before it is fine). */
    static final Set<String> SITES = Set.of("revolut.me", "paypal.me", "paypal.com", "buycoffee.to", "suppi.pl", "tipply.pl",
            "buymeacoffee.com", "ko-fi.com");

    /** One to three parts of a name, nothing a guest's browser could read as anything but a page of the service. */
    private static final Pattern PATH = Pattern.compile("(?:/[A-Za-z0-9._~@\\-]{1,80}){1,3}/?");

    private TipLinks() {
    }

    /**
     * The tip link as the app keeps it, {@code https://revolut.me/djkoko}; null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not a page on one of the services
     */
    public static String tipUrl(String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("://") && !lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IllegalArgumentException("not a tip link: " + typed);
        }
        URI uri;
        try {
            uri = URI.create(lower.startsWith("http") ? text : "https://" + text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a tip link: " + typed);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String site = host.startsWith("www.") ? host.substring(4) : host.startsWith("m.") ? host.substring(2) : host;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!SITES.contains(site) || uri.getRawUserInfo() != null || uri.getPort() != -1 || !PATH.matcher(path).matches()
                || ("paypal.com".equals(site) && !path.toLowerCase(Locale.ROOT).startsWith("/paypalme/"))) {
            throw new IllegalArgumentException("not a tip link: " + typed);
        }
        String url = "https://" + host + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        if (url.length() > 200) {
            throw new IllegalArgumentException("too long a tip link: " + typed);
        }
        return url;
    }

    /** How a guest reads a saved link, on paper too: "revolut.me/djkoko". */
    public static String display(String url) {
        if (url == null) {
            return null;
        }
        String shown = url.startsWith("https://") ? url.substring("https://".length()) : url;
        return shown.startsWith("www.") ? shown.substring(4) : shown;
    }
}
