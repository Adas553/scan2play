package com.scan2play.model;

import java.util.regex.Pattern;

/**
 * The QR print's look (V47, V52; the poster, the cards and the tent): its paper, ink and frame — qr-print.css,
 * {@code body.theme-<name>}. The code stays black on white in every one: a phone reads it best so. V52: RUSTIC (kraft, olive, a
 * stitched frame), GALA (ivory, gold, a double frame), NEON (white, a gradient frame), CONFETTI (white, dots in the frame's band).
 */
public enum PosterTheme {
    CLASSIC, ELEGANT, CLUB, PASTEL, RUSTIC, GALA, NEON, CONFETTI;

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** The value sent by the print page's form; anything else is {@link #CLASSIC}. */
    public static PosterTheme of(String value) {
        if (value != null) {
            for (PosterTheme theme : values()) {
                if (theme.name().equalsIgnoreCase(value.trim())) {
                    return theme;
                }
            }
        }
        return CLASSIC;
    }

    /** The DJ's accent as kept, "#rrggbb" in lower case; null — the theme's own — for anything else. */
    public static String accent(String value) {
        if (value == null || !HEX.matcher(value.trim()).matches()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The CSS class of the page: "theme-classic". */
    public String cssClass() {
        return "theme-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
