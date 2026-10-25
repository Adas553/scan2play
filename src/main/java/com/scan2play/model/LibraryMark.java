package com.scan2play.model;

/** A waiting request against the DJ's library (V41, V42): what the queue shows under the song. */
public enum LibraryMark {
    /** "✓ Masz": the same title with an artist in common. */
    HAVE,
    /** "? Podobny": the same title, but no artist in common (or none known) — the DJ checks. */
    SIMILAR,
    /** "✗ Nie masz": no song of that title. */
    MISSING
}
