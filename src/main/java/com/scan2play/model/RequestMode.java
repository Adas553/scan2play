package com.scan2play.model;

/**
 * What the guest chose to ask for on the request form: a specific song — a title, an artist or a line of the lyrics — or a mood
 * the AI picks a song for. The guest says which, rather than the AI guessing: a guess can take a line of lyrics for a mood and
 * play another song.
 */
public enum RequestMode {
    SONG,
    MOOD;

    /** The mode of the form field; anything else (an old page, a missing field) is {@link #SONG}, the form's default. */
    public static RequestMode fromParam(String value) {
        return "MOOD".equalsIgnoreCase(value == null ? "" : value.strip()) ? MOOD : SONG;
    }
}
