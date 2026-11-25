package com.scan2play.model;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.util.Times;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What is new in Scan2Play (V80): the panel's card "Nowości" and the e-mail of it to the organisers who said yes to "Nowości mailem".
 * One news at a time, by its number: a new one is a new {@link #NUMBER} and {@link #SINCE}, the card's words ({@code dashboard.news.*})
 * and the e-mail's pages ({@code templates/email/news_*.html}) — the steps in {@code docs/news.md}.
 */
public final class PanelNews {

    /** The number of the news now; the card shows to an organiser who closed a lower one, the e-mail goes to one who had a lower one. */
    public static final int NUMBER = 1;

    /** The day the news came: an account made on or after it never knew the panel without it, so it is not news to them. */
    public static final Instant SINCE = LocalDate.of(2026, 11, 26).atStartOfDay(Times.DISPLAY_ZONE).toInstant();

    private PanelNews() {
    }

    /** Whether the organiser's panel shows the card: not closed yet, and the account older than the news. */
    public static boolean shows(PartySettingsEntity settings) {
        return settings.getNewsSeen() < NUMBER && (settings.getCreatedAt() == null || settings.getCreatedAt().isBefore(SINCE));
    }
}
