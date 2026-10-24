package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SongNamesTest {

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("the AI's song shares a word with the guest's: no warning — Polish endings, words run together, a bare artist")
    @CsvSource(delimiter = '|', value = {
            "ta o Baśce, co ją Wilki grają | Wilki - Baśka",
            "ta o Baśce                    | Wilki - Baśka",
            "golec uorkiestra              | Golec uOrkiestra - Sciernisco",
            "labamba                       | Ritchie Valens - La Bamba",
            "widziałem orła cień           | Elektryczne Gitary - Widziałem Orła Cień",
            "Dzem – Wehikuł Czasu / To byłby cud | Dżem - Wehikuł Czasu",
            "BEATLES yesterday             | The Beatles - Yesterday",
            "chciałbym być marynarzem      | Elektryczne Gitary - Chciałbym być marynarzem",
            "sen o wiktorii                | Dżem - Sen o Victorii",
            "orła cień                     | Budka Suflera - Cień Wielkiej Góry",
    })
    void aSharedWord_isNoWarning(String guestText, String songName) {
        assertThat(SongNames.sharesNoWord(guestText, songName)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("none of the guest's words in the AI's song: a warning (2026-10-07: \"orła cień\" became Dżem by its mood)")
    @CsvSource(delimiter = '|', value = {
            "orła cień                     | Dżem - Sen o Victorii",
            "ta z Shreka                   | Smash Mouth - All Star",
            "bitelsi                       | The Beatles - Yesterday",
    })
    void noSharedWord_isAWarning(String guestText, String songName) {
        assertThat(SongNames.sharesNoWord(guestText, songName)).isTrue();
    }

    @Test
    @DisplayName("tidy: a control character is a dash (the AI's backspace, line break), every dash is \"-\", one in a row; a good name stays")
    void tidy() {
        assertThat(SongNames.tidy("Zenon Martyniuk & Edward Hulewicz \b Za zdrowie Pań")).isEqualTo("Zenon Martyniuk & Edward Hulewicz - Za zdrowie Pań");
        assertThat(SongNames.tidy("Brathanki\n– Czerwone Korale")).isEqualTo("Brathanki - Czerwone Korale");
        assertThat(SongNames.tidy("Kleks & sanah — Jestem Twoją Bajką")).isEqualTo("Kleks & sanah - Jestem Twoją Bajką");
        assertThat(SongNames.tidy("Jay-Z - 99 Problems")).isEqualTo("Jay-Z - 99 Problems");
        assertThat(SongNames.tidy("  Wilki  -  Baśka ")).isEqualTo("Wilki - Baśka");
        assertThat(SongNames.tidy(null)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("nothing to compare — no text, no song, only short words: no warning")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "null         | Wilki - Baśka",
            "'   '        | Wilki - Baśka",
            "AC/DC        | Highway to Hell",
            "o ta w       | Wilki - Baśka",
            "wilki baśka  | null",
            "wilki baśka  | ' - '",
    })
    void nothingToCompare_isNoWarning(String guestText, String songName) {
        assertThat(SongNames.sharesNoWord(guestText, songName)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" is \"{1}\" in the DJ's library")
    @DisplayName("a song of the DJ's library: the AI's name matches it without the versions, the guests and the punctuation")
    @CsvSource(delimiter = '|', value = {
            "Daft Punk - One More Time                    | Daft Punk - One More Time (Radio Edit)",
            "Daft Punk - One More Time                    | DAFT PUNK – one more time [Extended Mix]",
            "Wilki - Baśka                                | Wilki - Baska",
            "Calvin Harris - This Is What You Came For    | Calvin Harris feat. Rihanna - This Is What You Came For",
            "Calvin Harris - This Is What You Came For    | Calvin Harris - This Is What You Came For (feat. Rihanna)",
            "Calvin Harris ft. Rihanna - This Is What You Came For | Calvin Harris - This Is What You Came For",
    })
    void theLibraryKey_isTheSongWithoutItsVersion(String asked, String inLibrary) {
        assertThat(SongNames.libraryKey(asked)).isNotEmpty().isEqualTo(SongNames.libraryKey(inLibrary));
    }

    @Test
    @DisplayName("another song, or another artist's, is another key; nothing to compare is an empty key; a long one is cut")
    void theLibraryKey_tellsSongsApart() {
        assertThat(SongNames.libraryKey("Daft Punk - One More Time")).isNotEqualTo(SongNames.libraryKey("Daft Punk - Digital Love"));
        assertThat(SongNames.libraryKey("Wilki - Baśka")).isNotEqualTo(SongNames.libraryKey("Dżem - Baśka"));
        assertThat(SongNames.libraryKey(null)).isEmpty();
        assertThat(SongNames.libraryKey(" (Radio Edit) - !!")).isEmpty();
        assertThat(SongNames.libraryKey("a".repeat(500))).hasSize(SongNames.LIBRARY_KEY_MAX);
    }
}
