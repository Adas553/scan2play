package com.scan2play.model;

/**
 * The white badge behind the DJ's logo on the QR print (V70): {@link #AUTO} — on the club's dark paper and on a paper of the DJ's own,
 * where a dark logo with a transparent background would vanish —, {@link #ALWAYS} — a logo with a white background of its own then
 * reads as a rounded badge on any paper, not a white rectangle —, or {@link #NEVER}. The badge's room is the logo's on every paper
 * (qr-print.css .dj-logo), so the choice only colours it: the logo keeps its size and place.
 */
public enum PosterLogoBadge {
    AUTO, ALWAYS, NEVER;

    /** The value sent by the print page's form; anything else is {@link #AUTO}. */
    public static PosterLogoBadge of(String value) {
        if (value != null) {
            for (PosterLogoBadge badge : values()) {
                if (badge.name().equalsIgnoreCase(value.trim())) {
                    return badge;
                }
            }
        }
        return AUTO;
    }

    /** The CSS class of the page: "badge-always"; the automatic one has none. */
    public String cssClass() {
        return this == AUTO ? "" : "badge-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
