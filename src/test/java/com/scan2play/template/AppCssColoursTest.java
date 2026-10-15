package com.scan2play.template;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The colours of {@code app.css} are tokens on {@code :root} (PROJECT_CONTEXT 6.7; review 2026-10-14: 111 colours were written
 * where they were used, so one shade could not be changed in one place). A rule names a colour by its token, {@code var(--s2p-…)};
 * a new colour gets a token first.
 */
class AppCssColoursTest {

    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\([^)]*\\)|hsla?\\([^)]*\\)");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern ROOT = Pattern.compile(":root\\s*\\{[^}]*}");

    @Test
    void everyColour_isATokenOnRoot() throws IOException {
        String css = COMMENT.matcher(new ClassPathResource("static/css/app.css").getContentAsString(StandardCharsets.UTF_8))
                .replaceAll("");
        Matcher root = ROOT.matcher(css);
        assertThat(root.find()).as("app.css has its :root").isTrue();
        assertThat(COLOUR.matcher(root.group()).results().count()).as("the tokens").isGreaterThan(20);

        List<String> found = new ArrayList<>();
        Matcher colour = COLOUR.matcher(css.substring(0, root.start()) + css.substring(root.end()));
        while (colour.find()) {
            found.add(colour.group());
        }
        assertThat(found).as("colours written outside :root").isEmpty();
    }
}
