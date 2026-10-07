package com.scan2play.util;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DJ's profiles on Instagram, Facebook and TikTok (V24), for the guests to follow. The DJ types a profile the way they have it —
 * "@djkoko", "djkoko", or a link copied from the app ("instagram.com/djkoko?igsh=…") — and the app keeps one https address on that
 * site, written here: the guest page links only there, never to an address a DJ (or someone with their login) typed in.
 */
public final class SocialLinks {

    private static final Pattern INSTAGRAM_NAME = Pattern.compile("[A-Za-z0-9._]{1,30}");
    private static final Pattern TIKTOK_NAME = Pattern.compile("[A-Za-z0-9._]{2,24}");
    private static final Pattern FACEBOOK_NAME = Pattern.compile("[A-Za-z0-9.\\-]{2,80}");
    private static final Pattern FACEBOOK_PEOPLE = Pattern.compile("/people/[^/?#\\s]{1,80}/\\d{1,20}/?");
    private static final Pattern FACEBOOK_ID = Pattern.compile("(?:^|&)id=(\\d{1,20})(?:&|$)");

    /** The first part of an Instagram link that is not a profile: a post, a reel, the stories, the search. */
    private static final Set<String> INSTAGRAM_NOT_PROFILES = Set.of("p", "reel", "reels", "stories", "explore", "accounts");

    private SocialLinks() {
    }

    /**
     * The Instagram profile's address, {@code https://www.instagram.com/<name>/}; null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not an Instagram profile
     */
    public static String instagram(String typed) {
        String name = nameOf(typed, "instagram.com");
        if (name == null) {
            return null;
        }
        if (!INSTAGRAM_NAME.matcher(name).matches() || INSTAGRAM_NOT_PROFILES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("not an Instagram profile: " + typed);
        }
        return "https://www.instagram.com/" + name + "/";
    }

    /**
     * The TikTok profile's address, {@code https://www.tiktok.com/@<name>}; null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not a TikTok profile
     */
    public static String tiktok(String typed) {
        String name = nameOf(typed, "tiktok.com");
        if (name == null) {
            return null;
        }
        if (!TIKTOK_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a TikTok profile: " + typed);
        }
        return "https://www.tiktok.com/@" + name;
    }

    /**
     * The Facebook page's address: {@code https://www.facebook.com/<name>}, or the forms Facebook gives a page without a name
     * ({@code /profile.php?id=…}, {@code /people/<Name>/<id>}); null for nothing typed.
     *
     * @throws IllegalArgumentException when it is not a Facebook page
     */
    public static String facebook(String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return null;
        }
        URI uri = onSite(text, "facebook.com", "fb.com");
        if (uri != null) {
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.equals("/profile.php")) {
                Matcher id = FACEBOOK_ID.matcher(uri.getRawQuery() == null ? "" : uri.getRawQuery());
                if (id.find()) {
                    return "https://www.facebook.com/profile.php?id=" + id.group(1);
                }
                throw new IllegalArgumentException("not a Facebook page: " + typed);
            }
            if (FACEBOOK_PEOPLE.matcher(path).matches()) {
                return "https://www.facebook.com" + path;
            }
            text = firstPart(path);
        } else if (text.contains("/") || text.contains(":")) {
            throw new IllegalArgumentException("not a Facebook page: " + typed);
        }
        String name = text.startsWith("@") ? text.substring(1) : text;
        if (!FACEBOOK_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a Facebook page: " + typed);
        }
        return "https://www.facebook.com/" + name;
    }

    /**
     * How a guest reads a saved profile: "@djkoko" for Instagram and TikTok, the page's name for Facebook — or null when the address
     * names none (a Facebook page known only by its number).
     */
    public static String handle(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("https://www.facebook.com/")) {
            String name = url.substring("https://www.facebook.com/".length());
            return FACEBOOK_NAME.matcher(name).matches() ? name : null;
        }
        String name = firstPart(URI.create(url).getRawPath());
        return name.isEmpty() ? null : "@" + (name.startsWith("@") ? name.substring(1) : name);
    }

    /** The profile's name from what the DJ typed: the first part of a link on {@code site}, or the text without its "@". */
    private static String nameOf(String typed, String site) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return null;
        }
        URI uri = onSite(text, site);
        if (uri != null) {
            text = firstPart(uri.getRawPath());
        } else if (text.contains("/") || text.contains(":")) {
            throw new IllegalArgumentException("not a profile on " + site + ": " + typed);
        }
        return text.startsWith("@") ? text.substring(1) : text;
    }

    /** The link, when the text is an address on one of {@code sites} (or a subdomain: www., m.); null when it names no site. */
    private static URI onSite(String text, String... sites) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean named = false;
        for (String site : sites) {
            named |= lower.contains(site);
        }
        if (!named) {
            return null;
        }
        try {
            URI uri = URI.create(lower.startsWith("http://") || lower.startsWith("https://") ? text : "https://" + text);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            for (String site : sites) {
                if (host.equals(site) || host.endsWith("." + site)) {
                    return uri;
                }
            }
        } catch (IllegalArgumentException e) {
            // a space or another character no address has: not a link
        }
        throw new IllegalArgumentException("not an address on " + String.join(" / ", sites) + ": " + text);
    }

    /** The first part of a link's path: "djkoko" of "/djkoko/reels/". */
    private static String firstPart(String path) {
        for (String part : (path == null ? "" : path).split("/")) {
            if (!part.isEmpty()) {
                return part;
            }
        }
        return "";
    }
}
