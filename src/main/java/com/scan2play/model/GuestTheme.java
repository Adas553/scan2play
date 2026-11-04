package com.scan2play.model;

import java.util.Locale;

/**
 * The look of the guests' pages as the DJ picks it (V59, "Motyw dla gości"): the party page, the result, the "party ended" page, the
 * kiosk and the TV screen. Every theme is dark — the pages are read in a dark hall —; a theme redefines the colour tokens of
 * {@code app.css} and {@code tv.css} and adds one ornament above the card ({@code static/css/guest-themes.css}, {@code body[data-guest-theme]}).
 * The statuses (the green "Twoja", the yellow 👍, the red "NIE") are the same in every theme: no theme's accent may look like them
 * ({@code GuestThemesCssTest}). Stored by name; the check of V59 lists them (guarded by {@code GuestThemeTest}).
 */
public enum GuestTheme {
    CLASSIC, CHRISTMAS, SCARY, CONFETTI, WEDDING;

    /** The value sent by the card's form; anything else is {@link #CLASSIC}. */
    public static GuestTheme of(String value) {
        if (value != null) {
            for (GuestTheme theme : values()) {
                if (theme.name().equalsIgnoreCase(value.trim())) {
                    return theme;
                }
            }
        }
        return CLASSIC;
    }

    /** Whether the pages load guest-themes.css: the classic look is app.css alone. */
    public boolean themed() {
        return this != CLASSIC;
    }

    /** The key of its name in the messages ("guest.theme.scary"). */
    public String messageKey() {
        return "guest.theme." + name().toLowerCase(Locale.ROOT);
    }
}
