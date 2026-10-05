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
