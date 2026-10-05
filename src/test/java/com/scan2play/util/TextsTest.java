package com.scan2play.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextsTest {

    @Test
    void aText_isOneStrippedLine_cutToItsLength() {
        assertThat(Texts.oneLine(null, 10)).isEmpty();
        assertThat(Texts.oneLine("  Wilki \n -\t Baśka  ", 50)).isEqualTo("Wilki - Baśka");
        assertThat(Texts.oneLine("abcdef", 3)).isEqualTo("abc");
        assertThat(Texts.oneLine("ab cdef", 3)).isEqualTo("ab");
    }

    /** "🎉" is two chars: a cut between them would leave half an emoji, which the database cannot store as text. */
    @Test
    void anEmojiThatTheCutWouldSplit_isLeftOutWhole() {
        assertThat(Texts.oneLine("ab🎉", 3)).isEqualTo("ab");
        assertThat(Texts.oneLine("ab🎉", 4)).isEqualTo("ab🎉");
        assertThat(Texts.oneLine("a🎉b", 3)).isEqualTo("a🎉");
        assertThat(Texts.oneLine("🎉🎉", 1)).isEmpty();
    }
}
