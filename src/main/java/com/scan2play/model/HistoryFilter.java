package com.scan2play.model;

import java.util.Locale;

/**
 * Which entries of the DJ's history to read — the buttons above the history list. The filter is applied by the server,
 * inside the bounded query, so the page limit counts the entries of the chosen kind.
 */
public enum HistoryFilter {

    /** Everything: the guests' songs that played or were rejected. */
    ALL(true, true),
    /** Only what played. */
    PLAYED(true, false),
    /** Only the requests that were rejected (by the AI, skipped or cleared by the DJ). */
    REJECTED(false, true);

    private final boolean played;
    private final boolean rejected;

    HistoryFilter(boolean played, boolean rejected) {
        this.played = played;
        this.rejected = rejected;
    }

    /** Whether the requests that were played belong to this filter. */
    public boolean includesPlayed() {
        return played;
    }

    /** Whether the requests that were rejected belong to this filter. */
    public boolean includesRejected() {
        return rejected;
    }

    /** The value used in the URL ({@code ?filter=played}) and by the buttons ({@code data-list-filter}). */
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The filter for a URL parameter; anything unknown, or none, is {@link #ALL} (a bad link should not break the page — nor an
     * old one: "guest" and "background" were filters while the parties had a background playlist).
     */
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
