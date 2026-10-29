package com.scan2play.model;

/**
 * The QR print's heading font as the DJ picks it (V51): the theme's own (the sans; the serif on the elegant theme), or the sans, the
 * serif or the script whatever the theme — qr-print.css, {@code body.font-<name>}, the fonts of {@code static/fonts}. The heading only:
 * the DJ's line, the address and the code's texts stay readable in the sans.
 */
public enum PosterFont {
    THEME, SANS, SERIF, SCRIPT;

    /** The value sent by the print page's form; anything else is {@link #THEME}. */
    public static PosterFont of(String value) {
        if (value != null) {
            for (PosterFont font : values()) {
                if (font.name().equalsIgnoreCase(value.trim())) {
                    return font;
                }
            }
        }
        return THEME;
    }

    /** The CSS class of the page: "font-script"; the theme's own font has none. */
    public String cssClass() {
        return this == THEME ? "" : "font-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
