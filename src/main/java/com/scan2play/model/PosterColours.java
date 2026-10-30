package com.scan2play.model;

import java.util.List;

/**
 * The colours the QR print's editor offers (qr-print.html): the paper's light tints (V53) and the heading's and the frame's colours (V47),
 * each with its name — a screen reader says "Kremowy", never "#fbf3e2" (the bundles' {@code qr.colour.<name>}). Any other colour is the
 * DJ's own, picked in the colour field. The swatches' dots are qr-print.css {@code .swatch-<hex>}.
 */
public final class PosterColours {

    /** A colour of the editor: "#rrggbb" as kept (PosterTheme.accent) and the name of its message. */
    public record Colour(String hex, String name) {

        /** The CSS class of its dot: "swatch-fbf3e2". */
        public String swatchClass() {
            return "swatch-" + hex.substring(1);
        }
    }

    public static final List<Colour> PAPERS = List.of(new Colour("#fbf3e2", "cream"), new Colour("#fdeef3", "pink"),
            new Colour("#e9f6ee", "mint"), new Colour("#e8f3fb", "sky"), new Colour("#f0ecf9", "lavender"), new Colour("#f2f2f2", "grey"));

    public static final List<Colour> ACCENTS = List.of(new Colour("#1565c0", "blue"), new Colour("#2e7d32", "green"),
            new Colour("#c2185b", "raspberry"), new Colour("#d84315", "orange"), new Colour("#6a1b9a", "violet"), new Colour("#b08a3e", "gold"),
            new Colour("#00bcd4", "turquoise"), new Colour("#111111", "black"));

    private PosterColours() {
    }

    /** Whether {@code hex} is one of {@code colours} (a colour not of them is the DJ's own). */
    public static boolean offers(List<Colour> colours, String hex) {
        return hex != null && colours.stream().anyMatch(c -> c.hex().equals(hex));
    }
}
