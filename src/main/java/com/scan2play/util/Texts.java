package com.scan2play.util;

/**
 * Free text as the app keeps it: one line, the spaces collapsed, cut to a length.
 */
public final class Texts {

    private Texts() {
    }

    /**
     * The text on one line (any run of white space, line breaks too, is one space), stripped, at most {@code max} characters;
     * "" for null. An emoji (two {@code char}s) that the cut would split is left out whole, not cut in half.
     */
    /** A configured address without its last "/" ({@code scan2play.site-url}, {@code scan2play.guest-url}): paths are added to it. */
    public static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

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
