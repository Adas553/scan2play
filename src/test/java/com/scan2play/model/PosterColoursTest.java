package com.scan2play.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The colours of the QR print's editor (PosterColours): each one a colour the server keeps as it is ("#rrggbb" in lower case,
 * PosterTheme.accent), a dot in qr-print.css and a name in both languages — the screen reader's word for it.
 */
class PosterColoursTest {

    private static Properties bundle(String name) throws IOException {
        Properties texts = new Properties();
        try (InputStream in = PosterColoursTest.class.getResourceAsStream("/" + name)) {
            texts.load(in);
        }
        return texts;
    }

    @Test
    void everyColour_isKeptAsItIs_hasItsDot_andItsNameInBothLanguages() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/qr-print.css"), StandardCharsets.UTF_8);
        Properties english = bundle("messages.properties");
        Properties polish = bundle("messages_pl.properties");
        List<PosterColours.Colour> all = new ArrayList<>(PosterColours.PAPERS);
        all.addAll(PosterColours.ACCENTS);

        assertThat(all).hasSize(14).allSatisfy(c -> {
            assertThat(PosterTheme.accent(c.hex())).as(c.name()).isEqualTo(c.hex());
            assertThat(css).as(c.name()).contains("." + c.swatchClass() + " { background: " + c.hex() + "; }");
            assertThat(english.getProperty("qr.colour." + c.name())).as(c.name()).isNotBlank();
            assertThat(polish.getProperty("qr.colour." + c.name())).as(c.name()).isNotBlank();
        });
        assertThat(all.stream().map(PosterColours.Colour::name).distinct()).hasSize(14);
        assertThat(PosterColours.offers(PosterColours.PAPERS, "#fbf3e2")).isTrue();
        assertThat(PosterColours.offers(PosterColours.ACCENTS, "#fbf3e2")).isFalse();
        assertThat(PosterColours.offers(PosterColours.ACCENTS, null)).isFalse();
    }
}
