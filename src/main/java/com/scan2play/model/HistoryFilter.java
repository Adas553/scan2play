package com.scan2play.model;

import java.util.Locale;

/**
 * Which entries of the DJ's history to read — the buttons above the history list. The filter is applied by the server,
 * inside the bounded queries, so the page limit counts the entries of the chosen kind: with a long playlist between the
 * guests' songs, "Guests" still shows the last guests' songs and not the few that happen to be among the last rows.
 */
public enum HistoryFilter {

    /** Everything: guests' songs that played or were rejected, and the tracks of the background playlist. */
    ALL(true, true, true),
    /** Only what guests asked for, played or rejected. */
    GUEST(true, true, false),
    /** Only the tracks of the DJ's background playlist. */
    BACKGROUND(false, false, true),
    /** Everything that played: guests' songs and playlist tracks, not the rejected requests. */
    PLAYED(true, false, true),
    /** Only the guests' requests that were rejected. */
    REJECTED(false, true, false);

    private final boolean guestsPlayed;
    private final boolean guestsRejected;
    private final boolean backgroundTracks;

    HistoryFilter(boolean guestsPlayed, boolean guestsRejected, boolean backgroundTracks) {
        this.guestsPlayed = guestsPlayed;
        this.guestsRejected = guestsRejected;
        this.backgroundTracks = backgroundTracks;
    }

    /** Whether the guests' requests that were played belong to this filter. */
    public boolean includesGuestsPlayed() {
        return guestsPlayed;
    }

    /** Whether the guests' requests that were rejected belong to this filter. */
    public boolean includesGuestsRejected() {
        return guestsRejected;
    }

    /** Whether the tracks of the background playlist belong to this filter. */
    public boolean includesBackgroundTracks() {
        return backgroundTracks;
    }

    /** The value used in the URL ({@code ?filter=guest}) and by the buttons ({@code data-list-filter}). */
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The filter for a URL parameter; anything unknown, or none, is {@link #ALL} (a bad link should not break the page). */
    public static HistoryFilter fromParam(String value) {
        if (value != null) {
            for (HistoryFilter filter : values()) {
                if (filter.param().equalsIgnoreCase(value.trim())) {
                    return filter;
                }
            }
        }
        return ALL;
    }
}
