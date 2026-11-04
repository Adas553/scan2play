package com.scan2play.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The guests' themes (V59): the check of the migration names every theme of the enum and nothing else; a form's value read leniently. */
class GuestThemeTest {

    @Test
    void theMigrationsCheck_isTheEnum() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V59__guest_theme.sql")) {
            assertThat(in).as("V59").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher check = Pattern.compile("guest_theme IN \\(([^)]*)\\)").matcher(sql);
        assertThat(check.find()).isTrue();
        List<String> listed = Arrays.stream(check.group(1).split(",")).map(s -> s.trim().replace("'", "")).toList();
        assertThat(listed).containsExactly(Arrays.stream(GuestTheme.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void aValue_isReadLeniently() {
        assertThat(GuestTheme.of("scary")).isEqualTo(GuestTheme.SCARY);
        assertThat(GuestTheme.of(" WEDDING ")).isEqualTo(GuestTheme.WEDDING);
        assertThat(GuestTheme.of("WINTER")).isEqualTo(GuestTheme.CLASSIC);
        assertThat(GuestTheme.of(null)).isEqualTo(GuestTheme.CLASSIC);
        assertThat(GuestTheme.CLASSIC.themed()).isFalse();
        assertThat(GuestTheme.CONFETTI.themed()).isTrue();
        assertThat(GuestTheme.SCARY.messageKey()).isEqualTo("guest.theme.scary");
    }
}
