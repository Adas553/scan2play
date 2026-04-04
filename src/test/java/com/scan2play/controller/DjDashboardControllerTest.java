package com.scan2play.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DjDashboardControllerTest {

    @Nested
    @DisplayName("extractPlaylistId")
    class ExtractPlaylistIdTests {

        // --- Null / blank → null ---

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("should return null for blank or null input")
        void blankInput(String input) {
            assertThat(DjDashboardController.extractPlaylistId(input)).isNull();
        }

        // --- Playlist URLs ---

        @Test
        @DisplayName("should extract playlist ID from full playlist URL")
        void fullPlaylistUrl() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
                    .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        }

        @Test
        @DisplayName("should extract playlist ID from watch URL with list parameter")
        void watchUrlWithList() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/watch?v=KD5fLb-WgBU&list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
                    .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        }

        @Test
        @DisplayName("should extract playlist ID when list param is first")
        void listParamFirst() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/watch?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf&v=KD5fLb-WgBU"))
                    .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        }

        @Test
        @DisplayName("should handle raw playlist ID")
        void rawPlaylistId() {
            assertThat(DjDashboardController.extractPlaylistId("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
                    .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        }

        // --- Single video URLs ---

        @Test
        @DisplayName("should extract video ID from standard watch URL")
        void standardWatchUrl() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/watch?v=KD5fLb-WgBU"))
                    .isEqualTo("V:KD5fLb-WgBU");
        }

        @Test
        @DisplayName("should extract video ID from short youtu.be URL")
        void shortUrl() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://youtu.be/KD5fLb-WgBU"))
                    .isEqualTo("V:KD5fLb-WgBU");
        }

        @Test
        @DisplayName("should extract video ID from short youtu.be URL with si parameter")
        void shortUrlWithSiParam() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://youtu.be/KD5fLb-WgBU?si=JLIFbZgk-6E8jCJq"))
                    .isEqualTo("V:KD5fLb-WgBU");
        }

        @Test
        @DisplayName("should extract video ID from watch URL with extra params")
        void watchUrlWithExtraParams() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/watch?v=KD5fLb-WgBU&t=120"))
                    .isEqualTo("V:KD5fLb-WgBU");
        }

        @Test
        @DisplayName("should handle raw 11-char video ID")
        void rawVideoId() {
            assertThat(DjDashboardController.extractPlaylistId("KD5fLb-WgBU"))
                    .isEqualTo("V:KD5fLb-WgBU");
        }

        // --- Priority: playlist > video ---

        @Test
        @DisplayName("should prefer playlist ID over video ID when both present")
        void playlistPriorityOverVideo() {
            assertThat(DjDashboardController.extractPlaylistId(
                    "https://www.youtube.com/watch?v=KD5fLb-WgBU&list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
                    .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        }
    }
}

