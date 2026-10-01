package com.scan2play.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestWordsTest {

    @Test
    void wordsThatSaySomethingElse_areShown() {
        assertThat(GuestWords.beside("ta piosenka z Shreka co leci na weselach", "Smash Mouth - All Star"))
                .isEqualTo("ta piosenka z Shreka co leci na weselach");
        assertThat(GuestWords.beside("wilki", "Wilki - Baśka")).isEqualTo("wilki");
    }

    @Test
    void theSameWordsAsTheName_areNotShownTwice() {
        assertThat(GuestWords.beside("wilki baśka", "Wilki - Baśka")).isNull();
        assertThat(GuestWords.beside("WILKI BASKA", "Wilki - Baśka")).isNull();
        assertThat(GuestWords.beside("Łzy - Agnieszka", "lzy agnieszka")).isNull();
    }

    @Test
    void noWords_nothingToShow() {
        assertThat(GuestWords.beside(null, "Song")).isNull();
        assertThat(GuestWords.beside("  ", "Song")).isNull();
        assertThat(GuestWords.beside("something", null)).isEqualTo("something");
    }
}
