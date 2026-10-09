package com.scan2play.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The hosts' list of songs (V29): "🚫 nie grać" or "⭐ koniecznie zagrać" — one song or artist per line, as the couple, the
 * host or the DJ typed it ("Akcent", "Przez twe oczy zielone", "sanah – Szampan"). A song is on the list when the words of one of
 * its lines stand in its name one after another, the way people read them ({@link #words}): "Akcent" is every song of Akcent,
 * "Przez twe oczy zielone" that song by anyone, "Szampan" never "Szampany". A line of fewer than {@value #SHORTEST} letters is
 * left out: it would match half the songs.
 */
public final class SongList {

    /** The most lines a list keeps (and the AI's songs are compared with: a bounded loop on every request). */
    public static final int MAX_LINES = 100;
    /** The longest line. */
    public static final int LINE_MAX = 150;
    /** The longest list as stored: the column (V29) has room for {@value #MAX_LINES} lines of {@value #LINE_MAX} and the breaks. */
    public static final int TEXT_MAX = 16000;
    /** A line shorter than this (without spaces and punctuation) matches nothing: "a", "ej". */
    static final int SHORTEST = 3;

    private static final SongList EMPTY = new SongList(List.of());

    /** Each line's words, " akcent " — with a space on both sides, so a word is found whole. */
    private final List<String> phrases;

    private SongList(List<String> phrases) {
        this.phrases = phrases;
    }

    /** The list stored as {@code text} (one entry per line); empty for null. */
    public static SongList of(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        List<String> phrases = new ArrayList<>();
        for (String line : lines(text)) {
            phrases.add(" " + words(line) + " ");
        }
        return new SongList(List.copyOf(phrases));
    }

    /**
     * What a list typed in the form is kept as: one entry per line, each on one line of at most {@value #LINE_MAX} characters,
     * the same entry once, the empty ones and the ones too short to match anything left out, at most {@value #MAX_LINES};
     * null when nothing is left.
     */
    public static String tidy(String typed) {
        List<String> lines = lines(typed);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** The entries of {@code text} as {@link #tidy} keeps them. */
    static List<String> lines(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = SongNames.tidy(Texts.oneLine(raw, LINE_MAX));
            String key = words(line);
            if (key.replace(" ", "").length() >= SHORTEST && seen.add(key)) {
                kept.add(line);
                if (kept.size() == MAX_LINES) {
                    break;
                }
            }
        }
        return kept;
    }

    /** Whether any of {@code names} (the AI's song, what the guest typed) has the words of one of the list's lines in it. */
    public boolean matches(String... names) {
        if (phrases.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String text = " " + words(name) + " ";
            for (String phrase : phrases) {
                if (text.contains(phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return phrases.isEmpty();
    }

    /**
     * The name's words as compared: lower case, no accents (and "ł" is "l"), every run of anything but letters and digits one
     * space — "Sanah – Szampan!" is "sanah szampan", "AC/DC" is "ac dc".
     */
    static String words(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L');
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
