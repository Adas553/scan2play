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

    /**
     * The AI's name of a song as the app keeps it: a control character becomes a dash between the artist and the title, every
     * kind of dash ("–", "—", "−") becomes "-", dashes in a row are one, on one line. 2026-10-07: a guest's "–" (as iTunes'
     * suggestions write it) came back from the AI as a backspace ("Zenon Martyniuk & Edward Hulewicz □ Za zdrowie Pań") or a line
     * break ("Brathanki↵– Czerwone Korale"). "" for null.
     */
    public static String tidy(String name) {
        if (name == null) {
            return "";
        }
        String dashed = name.replaceAll("\\s*\\p{Cc}+\\s*", " - ").replaceAll("[\\u2010-\\u2015\\u2212]", "-");
        return dashed.replaceAll("-(?:\\s*-)+", "-").replaceAll("\\s+", " ").strip();
    }

    /** A word of the guest's shorter than this ("o", "w", "ta", "AC/DC"'s halves) is not looked for: it says nothing on its own. */
    static final int SHORTEST_WORD = 3;

    /**
     * Whether the song the AI named has none of the guest's words in it — a sign that the AI may have put another song in place of
     * the one asked for (2026-10-07: "orła cień" became "Dżem - Sen o Victorii", by its mood). The DJ is asked to check it.
     * <p>
     * A word counts as found when its stem — its first max({@value #SHORTEST_WORD}, length − 2) letters: "sen" stays "sen",
     * "cien" is "cie", "basce" is "bas", "victorii" is "victor" — is anywhere in the {@link #comparable} name: Polish endings
     * change ("ta o Baśce" is "Wilki - Baśka") and words run together ("labamba" is "La Bamba"). Loose on purpose: a missed warning costs less than a correct song marked. False when the guest
     * typed no word long enough, or either text is empty.
     */
    public static boolean sharesNoWord(String guestText, String songName) {
        String name = comparable(songName);
        if (guestText == null || name.isEmpty()) {
            return false;
        }
        boolean anyWord = false;
        for (String word : guestText.split("[^\\p{L}\\p{M}\\p{N}]+")) {
            String plain = comparable(word);
            if (plain.length() < SHORTEST_WORD) {
                continue;
            }
            anyWord = true;
            if (name.contains(plain.substring(0, Math.max(SHORTEST_WORD, plain.length() - 2)))) {
                return false;
            }
        }
        return anyWord;
    }
}
