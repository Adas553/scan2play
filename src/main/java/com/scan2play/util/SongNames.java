package com.scan2play.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Song names compared the way people read them: "Wilki - Baśka", "wilki baśka" and "WILKI – BASKA" are one song.
 */
public final class SongNames {

    private SongNames() {
    }

    /**
     * The name without what does not tell two songs apart: the case, the accents (and the Polish ł, which has none to strip),
     * the punctuation and the spaces. Empty for null.
     */
    public static String comparable(String name) {
        if (name == null) {
            return "";
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L');
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Whether two names are the same song by {@link #comparable}; never for a name that compares as empty. */
    public static boolean same(String a, String b) {
        String first = comparable(a);
        return !first.isEmpty() && first.equals(comparable(b));
    }
}
