package com.scan2play.util;

/**
 * Free text as the app keeps it: one line, the spaces collapsed, cut to a length.
 */
public final class Texts {

    private Texts() {
    }

    /** The text on one line (any run of white space, line breaks too, is one space), stripped, at most {@code max} characters; "" for null. */
    public static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() > max ? line.substring(0, max).strip() : line;
    }
}
