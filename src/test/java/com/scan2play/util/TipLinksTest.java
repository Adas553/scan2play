package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DJ's tip link (V27): a page on one of the tipping services, kept as a plain https address. */
class TipLinksTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a link as the service gives it, with or without https:// — one address, no query, no trailing slash")
    @CsvSource({
            "revolut.me/djkoko, https://revolut.me/djkoko",
            "https://revolut.me/djkoko?currency=PLN, https://revolut.me/djkoko",
            "' https://paypal.me/DJKoko/ ', https://paypal.me/DJKoko",
            "http://www.paypal.com/paypalme/djkoko, https://www.paypal.com/paypalme/djkoko",
            "buycoffee.to/dj.koko, https://buycoffee.to/dj.koko",
            "https://suppi.pl/djkoko, https://suppi.pl/djkoko",
            "tipply.pl/@djkoko, https://tipply.pl/@djkoko",
            "https://www.buymeacoffee.com/djkoko, https://www.buymeacoffee.com/djkoko",
            "ko-fi.com/djkoko#top, https://ko-fi.com/djkoko",
            "HTTPS://Revolut.ME/djkoko, https://revolut.me/djkoko"})
    void aTipPage_isKept(String typed, String kept) {
        assertThat(TipLinks.tipUrl(typed)).isEqualTo(kept);
    }

    @Test
    void nothingTyped_isNoLink() {
        assertThat(TipLinks.tipUrl(null)).isNull();
        assertThat(TipLinks.tipUrl("   ")).isNull();
    }

    @ParameterizedTest
    @DisplayName("anything else is refused: another site, a look-alike host, no page, a login part, a port, another scheme")
    @ValueSource(strings = {"https://evil.example/revolut.me/djkoko", "https://revolut.me.evil.example/djkoko", "revolut.me",
            "https://revolut.me/", "https://user@revolut.me/djkoko", "https://revolut.me:8443/djkoko", "javascript:alert(1)",
            "ftp://revolut.me/djkoko", "https://www.paypal.com/signin", "https://paypal.me/dj koko", "@djkoko",
            "https://revolut.me/a/b/c/d", "https://revolut.me/dj%2Fkoko"})
    void notATipPage_isRefused(String typed) {
        assertThatThrownBy(() -> TipLinks.tipUrl(typed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGuestReadsTheLink_withoutHttpsAndWww() {
        assertThat(TipLinks.display("https://revolut.me/djkoko")).isEqualTo("revolut.me/djkoko");
        assertThat(TipLinks.display("https://www.paypal.com/paypalme/djkoko")).isEqualTo("paypal.com/paypalme/djkoko");
        assertThat(TipLinks.display(null)).isNull();
    }
}
