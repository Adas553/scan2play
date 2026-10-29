package com.scan2play.model;

/**
 * The QR print's heading size as the DJ picks it (V54): smaller, as the theme has it, or larger — qr-print.css {@code body.size-<name>}
 * sets {@code --heading-scale} (0.85, 1, 1.2). The heading only; a long one is still made smaller to its lines (qr-print.js).
 */
public enum PosterSize {
    SMALL, NORMAL, LARGE;

    /** The value sent by the print page's form; anything else is {@link #NORMAL}. */
    public static PosterSize of(String value) {
        if (value != null) {
            for (PosterSize size : values()) {
                if (size.name().equalsIgnoreCase(value.trim())) {
                    return size;
                }
            }
        }
        return NORMAL;
    }

    /** The CSS class of the page: "size-large"; as the theme has it has none. */
    public String cssClass() {
        return this == NORMAL ? "" : "size-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
