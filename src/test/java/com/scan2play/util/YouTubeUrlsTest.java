package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeUrlsTest {

    // ---- extractVideoId: the tracks the app stores ----

    @Test
    @DisplayName("a watch URL gives its video ID, wherever v= is among the parameters")
    void shouldExtractTheVideoIdOfAWatchUrl() {
        assertThat(YouTubeUrls.extractVideoId("https://www.youtube.com/watch?v=hTWKbfoikeg")).contains("hTWKbfoikeg");
        assertThat(YouTubeUrls.extractVideoId("https://www.youtube.com/watch?feature=share&v=hTWKbfoikeg&t=42"))
                .contains("hTWKbfoikeg");
    }

    @Test
    @DisplayName("a short youtu.be URL gives its video ID")
    void shouldExtractTheVideoIdOfAShortUrl() {
        assertThat(YouTubeUrls.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc")).contains("dQw4w9WgXcQ");
    }

    @Test
    @DisplayName("a YouTube search page, a Spotify URI, blank and null give nothing — the embedded player cannot play them")
    void shouldGiveNothingForWhatCannotBePlayed() {
        assertThat(YouTubeUrls.extractVideoId("https://www.youtube.com/results?search_query=some+song")).isEmpty();
        assertThat(YouTubeUrls.extractVideoId("spotify:track:abc123")).isEmpty();
        assertThat(YouTubeUrls.extractVideoId("")).isEmpty();
        assertThat(YouTubeUrls.extractVideoId(null)).isEmpty();
    }

    @Test
    @DisplayName("something shorter than the 11 characters of a video ID is not one")
    void shouldRequireAnElevenCharacterId() {
        assertThat(YouTubeUrls.extractVideoId("https://www.youtube.com/watch?v=short")).isEmpty();
    }

    // ---- extractPlaylistId (unchanged behaviour, pinned here because the lease and the queue rely on it) ----

    @Test
    @DisplayName("a playlist URL gives the playlist ID, a single video gives V:<id>, blank gives null")
    void shouldExtractThePlaylistId() {
        assertThat(YouTubeUrls.extractPlaylistId("https://youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
                .isEqualTo("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf");
        assertThat(YouTubeUrls.extractPlaylistId("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("V:dQw4w9WgXcQ");
        assertThat(YouTubeUrls.extractPlaylistId("dQw4w9WgXcQ")).isEqualTo("V:dQw4w9WgXcQ");
        assertThat(YouTubeUrls.extractPlaylistId("  ")).isNull();
        assertThat(YouTubeUrls.extractPlaylistId(null)).isNull();
    }

    @Test
    void isMix_tellsAYouTubeMixFromAPlaylist() {
        assertThat(YouTubeUrls.isMix(YouTubeUrls.extractPlaylistId("https://www.youtube.com/watch?v=3z-jNRAwSHk&list=RD3z-jNRAwSHk"))).isTrue();
        assertThat(YouTubeUrls.isMix("RDMM")).isTrue();
        assertThat(YouTubeUrls.isMix("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")).isFalse();
        assertThat(YouTubeUrls.isMix("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs")).as("a curated YouTube Music list").isFalse();
        assertThat(YouTubeUrls.isMix("V:3z-jNRAwSHk")).as("a single video").isFalse();
        assertThat(YouTubeUrls.isMix(null)).isFalse();
    }

    @Test
    void cleanVideoTitle_dropsTheTagsOfTheUpload_butKeepsWhatBelongsToTheSong() {
        assertThat(YouTubeUrls.cleanVideoTitle("Wilki - Baśka (Official Video)")).isEqualTo("Wilki - Baśka");
        assertThat(YouTubeUrls.cleanVideoTitle("Chciałem być - Krzysztof Krawczyk [HD]  (Teledysk)")).isEqualTo("Chciałem być - Krzysztof Krawczyk");
        assertThat(YouTubeUrls.cleanVideoTitle("Artist - Song (Official Lyric Video) [4K]")).isEqualTo("Artist - Song");
        assertThat(YouTubeUrls.cleanVideoTitle("Eiffel 65 - Blue (Da Ba Dee)")).isEqualTo("Eiffel 65 - Blue (Da Ba Dee)");
        assertThat(YouTubeUrls.cleanVideoTitle("A - B (feat. C) (Remix)")).isEqualTo("A - B (feat. C) (Remix)");
        assertThat(YouTubeUrls.cleanVideoTitle("Videoclub - Amour plastique")).as("a word outside brackets stays")
                .isEqualTo("Videoclub - Amour plastique");
        assertThat(YouTubeUrls.cleanVideoTitle(null)).isNull();
    }
}
