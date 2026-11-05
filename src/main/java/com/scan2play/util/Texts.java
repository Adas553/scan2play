package com.scan2play.util;

/**
 * Free text as the app keeps it: one line, the spaces collapsed, cut to a length; and a Polish count's word.
 */
public final class Texts {

    private Texts() {
    }

    /** A configured address without its last "/" ({@code scan2play.site-url}, {@code scan2play.guest-url}): paths are added to it. */
    public static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The word of a Polish count, by its number: {@code one} for 1 ("prośba"), {@code few} for 2–4, 22–24 … but not 12–14 ("prośby"),
     * {@code many} for the rest, 0 too ("próśb") — a page's texts in the bundles take no number, an e-mail's labels do.
     */
    public static String polishPlural(long count, String one, String few, String many) {
        long tens = count % 100;
        long units = count % 10;
        if (count == 1) {
            return one;
        }
        return units >= 2 && units <= 4 && (tens < 12 || tens > 14) ? few : many;
    }

    /**
     * The text on one line (any run of white space, line breaks too, is one space), stripped, at most {@code max} characters;
     * "" for null. An emoji (two {@code char}s) that the cut would split is left out whole, not cut in half.
     */
    public static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\s+", " ").strip();
        if (line.length() <= max) {
            return line;
        }
        int end = max > 0 && Character.isHighSurrogate(line.charAt(max - 1)) ? max - 1 : max;
        return line.substring(0, end).strip();
    }
}
