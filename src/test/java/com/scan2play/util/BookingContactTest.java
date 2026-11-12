package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DJ's contact for bookings (V67): a plain address and a phone number, and the links the guests' page makes of them. */
class BookingContactTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("an address as typed: trimmed, in lower case")
    @CsvSource({
            "rezerwacje@djkoko.pl, rezerwacje@djkoko.pl",
            "' DJ.Koko+wesela@Gmail.com ', dj.koko+wesela@gmail.com",
            "biuro@dj-koko.com.pl, biuro@dj-koko.com.pl"})
    void anAddress_isKept(String typed, String kept) {
        assertThat(BookingContact.email(typed)).isEqualTo(kept);
    }

    /** Nothing a {@code mailto:} would read as more than an address: no "?", "&", "#", "%0A", spaces, quotes or a second "@". */
    @ParameterizedTest
    @ValueSource(strings = {"djkoko", "dj@koko", "dj@koko.", "dj koko@x.pl", "dj@x.pl?bcc=a@b.pl", "dj@x.pl&cc=a@b.pl",
            "dj@x.pl#x", "\"dj\"@x.pl", "a@b@x.pl", "dj..koko@x.pl", "javascript:alert(1)@x.pl", "dj@x.p1"})
    void notAPlainAddress_isRefused(String typed) {
        assertThatThrownBy(() -> BookingContact.email(typed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAddressTooLong_isRefused() {
        assertThatThrownBy(() -> BookingContact.email("a".repeat(60) + "@" + ("b".repeat(60) + ".").repeat(4) + "pl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a number as written, one space between its parts")
    @CsvSource({
            "600100200, 600100200",
            "' 600  100 200 ', 600 100 200",
            "+48 600-100-200, +48 600-100-200",
            "(22) 123 45 67, (22) 123 45 67",
            "+44 7700 900123, +44 7700 900123"})
    void aNumber_isKept(String typed, String kept) {
        assertThat(BookingContact.phone(typed)).isEqualTo(kept);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567890123456", "600 100 200 wew. 12", "48+600100200", "tel:600100200", "600/100/200",
            "+48 (600) 100-200 – 300"})
    void notANumber_isRefused(String typed) {
        assertThatThrownBy(() -> BookingContact.phone(typed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingTyped_isNothing() {
        assertThat(BookingContact.email(null)).isNull();
        assertThat(BookingContact.email("  ")).isNull();
        assertThat(BookingContact.phone(null)).isNull();
        assertThat(BookingContact.phone(" ")).isNull();
    }

    /** The links: the subject encoded for a URL (spaces as %20, not "+"), the number's digits with its "+". */
    @Test
    void theLinks_forTheGuestsPage() {
        BookingContact both = BookingContact.of("rezerwacje@djkoko.pl", "+48 600 100 200", "Zapytanie o rezerwację DJ-a — impreza 14 listopada");
        assertThat(both.mailto()).isEqualTo("mailto:rezerwacje@djkoko.pl?subject=Zapytanie%20o%20rezerwacj%C4%99%20DJ-a%20"
                + "%E2%80%94%20impreza%2014%20listopada");
        assertThat(both.tel()).isEqualTo("tel:+48600100200");

        BookingContact phoneOnly = BookingContact.of(null, "(22) 123 45 67", "x");
        assertThat(phoneOnly.mailto()).isNull();
        assertThat(phoneOnly.email()).isNull();
        assertThat(phoneOnly.tel()).isEqualTo("tel:221234567");

        assertThat(BookingContact.of(null, null, "x")).isNull();
    }
}
