package com.scan2play.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The guest's own words beside the song the AI made of them, on the DJ's queue and history: the DJ checks the AI against what the
 * guest asked for ("ta piosenka z Shreka co leci na weselach" → "Smash Mouth - All Star").
 */
public final class GuestWords {

    private GuestWords() {
    }

    /**
     * What the guest typed, when it says something else than the song's name; {@code null} when there is nothing to show — no
     * words (a DJ's pick, a request from before they were kept), or the same words as the name, give or take the case, the accents,
     * the punctuation and the spaces ("wilki baśka" beside "Wilki - Baśka").
     */
    public static String beside(String guestText, String songName) {
        if (guestText == null || guestText.isBlank()) {
            return null;
        }
        return comparable(guestText).equals(comparable(songName)) ? null : guestText;
    }

    private static String comparable(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L');
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
