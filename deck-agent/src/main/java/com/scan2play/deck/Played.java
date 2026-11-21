package com.scan2play.deck;

import java.time.Instant;

/**
 * One song a DJ program says played: when it started (null when the file does not say), its artist and title as written there. Never
 * a file's path: it names the DJ's computer and account.
 */
public record Played(Instant at, String artist, String title) {

    public Played {
        artist = artist == null ? "" : artist.strip();
        title = title == null ? "" : title.strip();
    }

    /** Neither artist nor title: nothing to match. */
    public boolean isEmpty() {
        return artist.isEmpty() && title.isEmpty();
    }

    /** What makes it the same song twice in a history read again. */
    String identity() {
        return at + "|" + artist + "|" + title;
    }

    /**
     * A time as a program writes it, in seconds since 1970: some write milliseconds, macOS's own clock counts from 2001. Null for none
     * or nonsense.
     */
    static Instant fromEpoch(long value) {
        if (value <= 0) {
            return null;
        }
        if (value > 100_000_000_000L) {          // milliseconds
            return Instant.ofEpochMilli(value);
        }
        if (value < 1_000_000_000L) {            // seconds since 2001-01-01 (Apple's reference date)
            return Instant.ofEpochSecond(value + 978_307_200L);
        }
        return Instant.ofEpochSecond(value);
    }

    @Override
    public String toString() {
        return artist.isEmpty() ? title : artist + " - " + title;
    }
}
