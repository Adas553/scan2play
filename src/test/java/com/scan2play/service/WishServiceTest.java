package com.scan2play.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** "Życzenia przed imprezą" (V68): how a wish and an event's name are kept, and the key that makes one song of two spellings. */
class WishServiceTest {

    @Test
    void theSameSong_typedTwoWays_hasOneKey() {
        assertThat(WishService.keyOf("Wilki - Baśka")).isEqualTo(WishService.keyOf(WishService.tidySong("wilki – BASKA (Radio Edit)")));
        assertThat(WishService.keyOf("Daft Punk - One More Time (feat. Romanthony)"))
                .isEqualTo(WishService.keyOf("Daft Punk - One More Time"));
        assertThat(WishService.keyOf("Queen - Bohemian Rhapsody")).isNotEqualTo(WishService.keyOf("Queen - Don't Stop Me Now"));
    }

    @Test
    void aLineWithoutTheDash_isKeyedApart_byItsWords() {
        assertThat(WishService.keyOf("coś od Zenka")).isEqualTo(WishService.UNNAMED + "cosodzenka");
        assertThat(WishService.keyOf("Baśka")).as("a title alone is not \"Wilki - Baśka\"").isNotEqualTo(WishService.keyOf("Wilki - Baśka"));
        assertThat(WishService.keyOf("x".repeat(500))).hasSize(1 + com.scan2play.util.LibraryKey.MAX);
    }

    @Test
    void aSong_isKeptOnOneLine_cutTo150_andNothingIsNone() {
        assertThat(WishService.tidySong("  Wilki\n–  Baśka ")).isEqualTo("Wilki - Baśka");
        assertThat(WishService.tidySong("a".repeat(400))).hasSize(WishService.SONG_MAX);
        assertThat(WishService.tidySong(" ?! - ")).isEmpty();
        assertThat(WishService.tidySong(null)).isEmpty();
    }

    @Test
    void anEventsName_isKeptOnOneLine_cutTo80() {
        assertThat(WishService.tidyName(" Wesele\tAni\r\n i  Kuby ")).isEqualTo("Wesele Ani i Kuby");
        assertThat(WishService.tidyName("x".repeat(200))).hasSize(WishService.NAME_MAX);
        assertThat(WishService.tidyName("   ")).isNull();
        assertThat(WishService.tidyName(null)).isNull();
    }
}
