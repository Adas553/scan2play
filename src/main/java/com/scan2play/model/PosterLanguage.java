package com.scan2play.model;

/**
 * The language of the QR print (V46, the poster and the cards): what the guests read on paper. {@link #AUTO} is the panel's: a Polish
 * panel prints Polish and English at once (a Polish party's guests are of both), any other English alone.
 */
public enum PosterLanguage {
    AUTO, BOTH, PL, EN;

    /** "both", "pl" or "en" for a panel in {@code panelLanguage} (a language code, "pl", "en", "de"…). */
    public String printed(String panelLanguage) {
        return switch (this) {
            case AUTO -> "pl".equals(panelLanguage) ? "both" : "en";
            case BOTH -> "both";
            case PL -> "pl";
            case EN -> "en";
        };
    }

    /** The value sent by the print page's form; anything else is {@link #AUTO}. */
    public static PosterLanguage of(String value) {
        if (value != null) {
            for (PosterLanguage language : values()) {
                if (language.name().equalsIgnoreCase(value.trim())) {
                    return language;
                }
            }
        }
        return AUTO;
    }
}
