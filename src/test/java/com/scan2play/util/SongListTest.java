package com.scan2play.util;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The hosts' lists (V29): what a line of the list matches, and what the form's text is kept as. */
class SongListTest {

    @Test
    void anArtist_matchesEverySongOfTheirs() {
        SongList list = SongList.of("Akcent");

        assertThat(list.matches("Akcent - Przez twe oczy zielone")).isTrue();
        assertThat(list.matches("Zenek Martyniuk - Przekorny los")).isFalse();
    }

    @Test
    void aTitle_matchesTheSongByAnyone_asPeopleReadIt() {
        SongList list = SongList.of("przez twe oczy ZIELONE\nsanah – Szampan");

        assertThat(list.matches("Akcent - Przez twe oczy zielone")).isTrue();
        assertThat(list.matches("Sanah - Szampan")).as("the dash, the case").isTrue();
        assertThat(list.matches("Baśka")).isFalse();
    }

    @Test
    void accentsAndTheLetterL_areIgnored() {
        assertThat(SongList.of("Baśka").matches("Wilki - Baska")).isTrue();
        assertThat(SongList.of("Hej sokoly").matches("Hej sokoły")).isTrue();
    }

    @Test
    void aWord_isFoundWhole_notInsideAnother() {
        SongList list = SongList.of("Szampan");

        assertThat(list.matches("Sanah - Szampany")).isFalse();
        assertThat(SongList.of("Love").matches("Glove - Song")).isFalse();
    }

    @Test
    void anyOfTheNames_isChecked_theAisSongOrTheGuestsWords() {
        SongList list = SongList.of("Baby Shark");

        assertThat(list.matches("Pinkfong - Baby Shark Dance", null)).isTrue();
        assertThat(list.matches(null, "puść baby shark!")).isTrue();
        assertThat(list.matches("", "  ")).isFalse();
    }

    @Test
    void anEmptyList_matchesNothing() {
        assertThat(SongList.of(null).matches("Akcent")).isFalse();
        assertThat(SongList.of("  \n ").isEmpty()).isTrue();
    }

    @Test
    void tidy_keepsOneEntryPerLine_withoutBlanksRepeatsOrLinesTooShortToMatch() {
        String typed = "  Akcent  \r\n\r\nakcent\nA\nej\nsanah – Szampan\n";

        assertThat(SongList.tidy(typed)).isEqualTo("Akcent\nsanah - Szampan");
        assertThat(SongList.tidy(" \n ")).isNull();
        assertThat(SongList.tidy(null)).isNull();
    }

    @Test
    void tidy_keepsAtMostAHundredLines_eachOfAtMost150Characters() {
        String many = IntStream.rangeClosed(1, 150).mapToObj(i -> "Song " + i).collect(Collectors.joining("\n"));
        String longLine = "x".repeat(400);

        assertThat(SongList.tidy(many).lines()).hasSize(SongList.MAX_LINES).last().isEqualTo("Song 100");
        assertThat(SongList.tidy(longLine)).hasSize(SongList.LINE_MAX);
        String full = IntStream.rangeClosed(1, 120).mapToObj(i -> i + "x".repeat(200)).collect(Collectors.joining("\n"));
        assertThat(SongList.tidy(full).length()).as("fits the column").isLessThanOrEqualTo(SongList.TEXT_MAX);
    }
}
