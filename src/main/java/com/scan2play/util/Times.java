package com.scan2play.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * How a moment is shown (review item 1.8). The database keeps moments as {@code timestamptz} and the code as {@link Instant} —
 * the same on a machine in Poland and on a server in UTC, and a night when the clocks go back cannot reorder the history. People
 * read them in Polish time, whatever the server's zone. Used by the templates ({@code T(com.scan2play.util.Times)}).
 */
public final class Times {

    /** The zone the DJ and the guests read the times in. */
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Warsaw");

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM").withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter DAY_WITH_YEAR = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(DISPLAY_ZONE);
    /** Fixed width, UTC: the lists sort their time column as text ({@code data-val}). */
    private static final DateTimeFormatter SORT_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Times() {
    }

    /** "01.10.2026 13:04:59" in Polish time; empty for no moment. */
    public static String display(Instant moment) {
        return moment == null ? "" : DISPLAY.format(moment);
    }

    /** "13:04" in Polish time — the lists' time column, with {@link #daysAgo} / {@link #day} under it; empty for no moment. */
    public static String clock(Instant moment) {
        return moment == null ? "" : CLOCK.format(moment);
    }

    /** How many days before today (in Polish time) the moment was: 0 today, 1 yesterday; -1 for no moment. */
    public static long daysAgo(Instant moment) {
        return daysAgo(moment, LocalDate.now(DISPLAY_ZONE));
    }

    static long daysAgo(Instant moment, LocalDate today) {
        return moment == null ? -1 : ChronoUnit.DAYS.between(moment.atZone(DISPLAY_ZONE).toLocalDate(), today);
    }

    /** "01.10" — or "01.10.2025" in another year than this one; empty for no moment. */
    public static String day(Instant moment) {
        return day(moment, LocalDate.now(DISPLAY_ZONE));
    }

    static String day(Instant moment, LocalDate today) {
        if (moment == null) {
            return "";
        }
        return moment.atZone(DISPLAY_ZONE).getYear() == today.getYear() ? DAY.format(moment) : DAY_WITH_YEAR.format(moment);
    }

    /** A text that sorts like the moment; empty for no moment. */
    public static String sortKey(Instant moment) {
        return moment == null ? "" : SORT_KEY.format(moment);
    }
}
