package com.scan2play.template;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner, 2026-10-24 (as on another DJ app's page): the logo that leads home turns the app's accent on hover, and the action
 * button rises a little under the pointer — only where there is a pointer that hovers (a phone has none: a tap would leave the
 * button raised), and not at all for whom the system asks for less motion.
 */
class AppCssHoverTest {

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static String css() throws IOException {
        return COMMENT.matcher(new ClassPathResource("static/css/app.css").getContentAsString(StandardCharsets.UTF_8)).replaceAll("");
    }

    @Test
    void theHomeLogo_turnsTheAccentOnHover() throws IOException {
        assertThat(css()).containsPattern("\\.s2p-home-logo:hover\\s*\\{[^}]*color:\\s*var\\(--s2p-action\\)")
                .containsPattern("\\.s2p-home-logo:hover \\.s2p-logo-mark\\s*\\{[^}]*box-shadow:[^}]*var\\(--s2p-action\\)");
    }

    @Test
    void theActionButton_risesOnHover_onlyWithAPointer_andNotWithLessMotion() throws IOException {
        String css = css();
        assertThat(css).containsPattern("@media \\(hover: hover\\)\\s*\\{\\s*\\.btn-action:hover:not\\(:disabled\\)\\s*\\{[^}]*"
                + "transform:\\s*translateY\\(-2px\\)[^}]*box-shadow:\\s*0 6px 16px var\\(--s2p-action-glow\\)");
        assertThat(css).containsPattern("@media \\(prefers-reduced-motion: reduce\\)\\s*\\{[^@]*\\.btn-action[^{]*\\{[^}]*transform:\\s*none");
    }

    /** A phone: a tap must not leave an outline button filled, as if it were on — hover changes nothing there. */
    @Test
    void anOutlineButton_onAScreenWithoutHover_staysAsItIs() throws IOException {
        assertThat(css()).containsPattern("@media \\(hover: none\\)\\s*\\{\\s*\\.btn\\[class\\*=\"btn-outline-\"\\]\\s*\\{[^}]*"
                + "--bs-btn-hover-color:\\s*var\\(--bs-btn-color\\);[^}]*--bs-btn-hover-bg:\\s*transparent;[^}]*"
                + "--bs-btn-hover-border-color:\\s*var\\(--bs-btn-border-color\\)");
    }
}
