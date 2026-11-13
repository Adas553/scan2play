package com.scan2play.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The guests' themes (V59, V69): the checks of the migrations name every theme of the enum and nothing else; a form's value read leniently. */
class GuestThemeTest {

    /**
     * Every CHECK of a guests' look names every theme of the enum and nothing else: the party's (V59) and an event's of "Życzenia przed
     * imprezą" (V69) — a look missing from one would make its form a 500.
     */
    @ParameterizedTest
    @ValueSource(strings = {"V59__guest_theme.sql", "V69__wish_event_theme.sql"})
    void theMigrationsCheck_isTheEnum(String migration) throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/" + migration)) {
            assertThat(in).as(migration).isNotNull();
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
