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

    /** Polish counts take one of three forms: 1 prośba, 2–4 prośby (but 12–14 próśb), 5 próśb, 22 prośby, 25 próśb. */
    @Test
    void aPolishCount_takesItsForm() {
        String[] forms = {"prośba", "prośby", "próśb"};
        assertThat(Texts.polishPlural(1, forms[0], forms[1], forms[2])).isEqualTo("prośba");
        assertThat(Texts.polishPlural(0, forms[0], forms[1], forms[2])).isEqualTo("próśb");
        for (long few : new long[] {2, 3, 4, 22, 33, 104}) {
            assertThat(Texts.polishPlural(few, forms[0], forms[1], forms[2])).as("%d", few).isEqualTo("prośby");
        }
        for (long many : new long[] {5, 11, 12, 13, 14, 21, 25, 112, 1000}) {
            assertThat(Texts.polishPlural(many, forms[0], forms[1], forms[2])).as("%d", many).isEqualTo("próśb");
        }
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
