package com.scan2play.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotifyTokenConverterTest {

    /** Test keys (base64 of 32 bytes), never real ones. */
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String OTHER_KEY = "HyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4=";

    private final SpotifyTokenConverter converter = new SpotifyTokenConverter(KEY);

    @Test
    void aTokenIsStoredEncryptedAndReadBack() {
        String column = converter.convertToDatabaseColumn("BQD-token");

        assertThat(column).startsWith("enc:v1:").doesNotContain("BQD-token");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("BQD-token");
        assertThat(converter.convertToDatabaseColumn("BQD-token")).as("a new nonce every time").isNotEqualTo(column);
    }

    @Test
    void noTokenStaysNone() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void aTokenWrittenBeforeTheEncryptionIsReadAsItIs() {
        assertThat(converter.convertToEntityAttribute("BQD-plain")).isEqualTo("BQD-plain");
    }

    @Test
    void aTokenOfAnotherKeyOrDamagedIsReadAsNone() {
        String column = new SpotifyTokenConverter(OTHER_KEY).convertToDatabaseColumn("BQD-token");

        assertThat(converter.convertToEntityAttribute(column)).isNull();
        assertThat(converter.convertToEntityAttribute("enc:v1:not base64!")).isNull();
        assertThat(converter.convertToEntityAttribute("enc:v1:AAAA")).as("shorter than the nonce").isNull();
    }

    @Test
    void theKeyMustBe32BytesOfBase64() {
        assertThatThrownBy(() -> new SpotifyTokenConverter("not base64!")).hasMessageContaining("SPOTIFY_TOKEN_KEY");
        assertThatThrownBy(() -> new SpotifyTokenConverter("AAECAwQFBgcICQoLDA0ODw==")).hasMessageContaining("32 bytes");
    }
}
