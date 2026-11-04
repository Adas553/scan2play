package com.scan2play.template;

import com.scan2play.model.GuestTheme;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guests' themes (V59, {@code guest-themes.css}) keep what no theme may touch (PROJECT_CONTEXT 6.7): a theme block sets only tokens
 * that {@code app.css} or {@code tv.css} have on {@code :root} (and its own ornament and font), every theme the same whole set, so no
 * token is left at the classic value by mistake; no colour is written outside the blocks; the texts, the action and the TV read at
 * WCAG AA on the theme's own backgrounds; and no theme's accent looks like a status — the green "Twoja", the yellow 👍, the red "NIE"
 * (a Christmas red or gold, a summer coral did: CIEDE2000 under 20).
 */
class GuestThemesCssTest {

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\([^)]*\\)|hsla?\\([^)]*\\)");
    private static final Pattern ROOT = Pattern.compile(":root\\s*\\{([^}]*)}");
    private static final Pattern BLOCK = Pattern.compile("\\[data-guest-theme=\"([A-Z_]+)\"]\\s*\\{([^}]*)}");
    private static final Pattern DECLARATION = Pattern.compile("([-a-zA-Z0-9]+)\\s*:\\s*([^;]+);");
    /** The theme's own: the band's picture and the face of "🎧 Gra: …". */
    private static final Set<String> OWN = Set.of("--s2p-ornament", "--s2p-theme-font");
    /** What a theme must redefine: the page, the card, the edges, the texts, the action and its tints, the TV's. */
    private static final Set<String> REQUIRED = Set.of("--s2p-bg", "--s2p-bg-elevated", "--s2p-border", "--s2p-text", "--s2p-text-muted",
            "--s2p-action", "--s2p-action-hover", "--s2p-action-active", "--s2p-action-ink", "--s2p-action-glow", "--s2p-action-45",
            "--s2p-action-28", "--s2p-action-20", "--s2p-action-12", "--s2p-action-07", "--s2p-action-00",
            "--tv-bg", "--tv-card", "--tv-border", "--tv-text", "--tv-muted", "--tv-accent");
    /** The tints that must be the action's own colour (the page's glow, -20 / -12 / -00, may be the theme's mist). */
    private static final List<String> ACTION_TINTS = List.of("--s2p-action-glow", "--s2p-action-45", "--s2p-action-28", "--s2p-action-07");

    private static String css(String path) throws IOException {
        return COMMENT.matcher(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8)).replaceAll("");
    }

    private static Map<String, String> declarations(String body) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher d = DECLARATION.matcher(body);
        while (d.find()) {
            found.put(d.group(1), d.group(2).trim());
        }
        return found;
    }

    private static Map<String, String> root(String path) throws IOException {
        Matcher root = ROOT.matcher(css(path));
        assertThat(root.find()).as(path + " has its :root").isTrue();
        return declarations(root.group(1));
    }

    private static Map<String, Map<String, String>> themes() throws IOException {
        Map<String, Map<String, String>> themes = new LinkedHashMap<>();
        Matcher block = BLOCK.matcher(css("static/css/guest-themes.css"));
        while (block.find()) {
            assertThat(themes.put(block.group(1), declarations(block.group(2)))).as("one block for " + block.group(1)).isNull();
        }
        return themes;
    }

    @Test
    void everyThemeButTheClassicOne_hasItsBlock_andNoOtherIsThere() throws IOException {
        List<String> themed = Arrays.stream(GuestTheme.values()).filter(GuestTheme::themed).map(Enum::name).toList();
        assertThat(themes().keySet()).containsExactlyInAnyOrderElementsOf(themed);
    }

    @Test
    void aBlock_setsOnlyTokens_theWholeSet() throws IOException {
        Set<String> known = new TreeSet<>(root("static/css/app.css").keySet());
        known.addAll(root("static/css/tv.css").keySet());
        known.addAll(OWN);
        assertThat(known).as("the tokens a theme must set exist").containsAll(REQUIRED);
        for (Map.Entry<String, Map<String, String>> theme : themes().entrySet()) {
            Set<String> names = theme.getValue().keySet();
            assertThat(names).as(theme.getKey() + ": tokens only, ones the pages have").allMatch(known::contains);
            assertThat(names).as(theme.getKey() + ": the whole set").containsAll(REQUIRED).containsAll(OWN);
        }
    }

    @Test
    void noColour_isWrittenOutsideTheBlocks() throws IOException {
        String outside = BLOCK.matcher(css("static/css/guest-themes.css")).replaceAll("");
        List<String> found = new ArrayList<>();
        COLOUR.matcher(outside).results().forEach(r -> found.add(r.group()));
        assertThat(found).isEmpty();
    }

    @Test
    void theTexts_theAction_andTheTv_readAtWcagAA() throws IOException {
        Map<String, String> app = root("static/css/app.css");
        for (Map.Entry<String, Map<String, String>> entry : themes().entrySet()) {
            String name = entry.getKey();
            Map<String, String> t = entry.getValue();
            String card = t.get("--s2p-bg-elevated");
            String page = t.get("--s2p-bg");
            for (String text : List.of(t.get("--s2p-text"), t.get("--s2p-text-muted"), app.get("--s2p-white"), app.get("--s2p-light"))) {
                assertThat(contrast(text, card)).as(name + ": " + text + " on the card").isGreaterThanOrEqualTo(4.5);
                assertThat(contrast(text, page)).as(name + ": " + text + " on the page (the list has no card)").isGreaterThanOrEqualTo(4.5);
            }
            for (String button : List.of("--s2p-action", "--s2p-action-hover", "--s2p-action-active")) {
                assertThat(contrast(t.get("--s2p-action-ink"), t.get(button))).as(name + ": the action's text on " + button)
                        .isGreaterThanOrEqualTo(4.5);
            }
            // the accent as a text: "💌 Dodaj dedykację", a text button
            assertThat(contrast(t.get("--s2p-action"), card)).as(name + ": the accent on the card").isGreaterThanOrEqualTo(4.5);
            assertThat(contrast(t.get("--s2p-action"), page)).as(name + ": the accent on the page").isGreaterThanOrEqualTo(4.5);
            // the statuses are app.css's, on the theme's backgrounds: the green "Twoja" and the yellow 👍 seen as shapes
            for (String status : List.of("--s2p-green", "--s2p-warning", "--s2p-red")) {
                assertThat(contrast(app.get(status), card)).as(name + ": " + status + " on the card").isGreaterThanOrEqualTo(3);
            }
            for (String text : List.of(t.get("--tv-text"), t.get("--tv-muted"), t.get("--tv-accent"))) {
                assertThat(contrast(text, t.get("--tv-bg"))).as(name + ": the TV's " + text).isGreaterThanOrEqualTo(4.5);
                assertThat(contrast(text, t.get("--tv-card"))).as(name + ": the TV's " + text + " on a row").isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    @Test
    void theAccent_looksLikeNoStatus() throws IOException {
        Map<String, String> app = root("static/css/app.css");
        for (Map.Entry<String, Map<String, String>> entry : themes().entrySet()) {
            String accent = entry.getValue().get("--s2p-action");
            for (String status : List.of("--s2p-green", "--s2p-warning", "--s2p-red", "--s2p-danger-text")) {
                assertThat(deltaE2000(accent, app.get(status))).as(entry.getKey() + ": the accent " + accent + " beside " + status)
                        .isGreaterThanOrEqualTo(20);
            }
            assertThat(entry.getValue().get("--tv-accent")).as(entry.getKey() + ": the TV's accent is the page's").isEqualTo(accent);
        }
    }

    @Test
    void theActionsTints_areTheActionsColour() throws IOException {
        for (Map.Entry<String, Map<String, String>> entry : themes().entrySet()) {
            int[] accent = rgb(entry.getValue().get("--s2p-action"));
            for (String tint : ACTION_TINTS) {
                assertThat(rgb(entry.getValue().get(tint))).as(entry.getKey() + ": " + tint).containsExactly(accent);
            }
        }
    }

    @Test
    void theOrnaments_areOurFiles() throws IOException {
        for (Map.Entry<String, Map<String, String>> entry : themes().entrySet()) {
            Matcher url = Pattern.compile("url\\(\"(/images/themes/[a-z]+\\.svg)\"\\)").matcher(entry.getValue().get("--s2p-ornament"));
            assertThat(url.matches()).as(entry.getKey() + ": the ornament is one of ours (CSP: img-src 'self')").isTrue();
            assertThat(new ClassPathResource("static" + url.group(1)).exists()).as(url.group(1)).isTrue();
        }
    }

    // --- colour arithmetic ---

    /** "#rrggbb" or "rgba(r, g, b, a)" as its red, green and blue. */
    private static int[] rgb(String colour) {
        String c = colour.trim();
        if (c.startsWith("#") && c.length() == 4) {   // "#fff"
            return new int[]{Integer.parseInt(c.substring(1, 2).repeat(2), 16), Integer.parseInt(c.substring(2, 3).repeat(2), 16),
                    Integer.parseInt(c.substring(3, 4).repeat(2), 16)};
        }
        if (c.startsWith("#")) {
            return new int[]{Integer.parseInt(c.substring(1, 3), 16), Integer.parseInt(c.substring(3, 5), 16),
                    Integer.parseInt(c.substring(5, 7), 16)};
        }
        String[] parts = c.substring(c.indexOf('(') + 1, c.indexOf(')')).split(",");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double luminance(String colour) {
        int[] c = rgb(colour);
        return 0.2126 * linear(c[0]) + 0.7152 * linear(c[1]) + 0.0722 * linear(c[2]);
    }

    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double[] lab(String colour) {
        int[] c = rgb(colour);
        double r = linear(c[0]);
        double g = linear(c[1]);
        double b = linear(c[2]);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }

    /** CIEDE2000: how different two colours look (about 2 is barely, 20 and more is clearly another colour). */
    static double deltaE2000(String first, String second) {
        double[] p = lab(first);
        double[] q = lab(second);
        double c1 = Math.hypot(p[1], p[2]);
        double c2 = Math.hypot(q[1], q[2]);
        double cMean = (c1 + c2) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cMean, 7) / (Math.pow(cMean, 7) + Math.pow(25, 7))));
        double a1 = (1 + g) * p[1];
        double a2 = (1 + g) * q[1];
        double c1p = Math.hypot(a1, p[2]);
        double c2p = Math.hypot(a2, q[2]);
        double h1 = (Math.toDegrees(Math.atan2(p[2], a1)) + 360) % 360;
        double h2 = (Math.toDegrees(Math.atan2(q[2], a2)) + 360) % 360;
        double dL = q[0] - p[0];
        double dC = c2p - c1p;
        double dh = h2 - h1;
        if (c1p * c2p == 0) {
            dh = 0;
        } else if (dh > 180) {
            dh -= 360;
        } else if (dh < -180) {
            dh += 360;
        }
        double dH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
        double lMean = (p[0] + q[0]) / 2;
        double cpMean = (c1p + c2p) / 2;
        double hMean;
        if (c1p * c2p == 0) {
            hMean = h1 + h2;
        } else if (Math.abs(h1 - h2) <= 180) {
            hMean = (h1 + h2) / 2;
        } else if (h1 + h2 < 360) {
            hMean = (h1 + h2 + 360) / 2;
        } else {
            hMean = (h1 + h2 - 360) / 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hMean - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hMean))
                + 0.32 * Math.cos(Math.toRadians(3 * hMean + 6)) - 0.2 * Math.cos(Math.toRadians(4 * hMean - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hMean - 275) / 25, 2));
        double rC = 2 * Math.sqrt(Math.pow(cpMean, 7) / (Math.pow(cpMean, 7) + Math.pow(25, 7)));
        double sL = 1 + 0.015 * Math.pow(lMean - 50, 2) / Math.sqrt(20 + Math.pow(lMean - 50, 2));
        double sC = 1 + 0.045 * cpMean;
        double sH = 1 + 0.015 * cpMean * t;
        double rT = -Math.sin(Math.toRadians(2 * dTheta)) * rC;
        return Math.sqrt(Math.pow(dL / sL, 2) + Math.pow(dC / sC, 2) + Math.pow(dH / sH, 2) + rT * (dC / sC) * (dH / sH));
    }

    /** The arithmetic itself, on known pairs: black on white is 21 : 1; a Christmas red beside the red of "NIE" is the same colour. */
    @Test
    void theArithmetic_isRight() {
        assertThat(contrast("#000000", "#ffffff")).isCloseTo(21, org.assertj.core.data.Offset.offset(0.01));
        assertThat(contrast("#0dcaf0", "#1e1e1e")).isCloseTo(8.5, org.assertj.core.data.Offset.offset(0.1));
        assertThat(deltaE2000("#e0526b", "#ff4444")).isLessThan(20);
        assertThat(deltaE2000("#0dcaf0", "#1db954")).isGreaterThan(20);
        assertThat(deltaE2000("#ffffff", "#ffffff")).isZero();
    }
}
