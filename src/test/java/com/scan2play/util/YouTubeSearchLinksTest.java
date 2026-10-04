package com.scan2play.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A song's "🔍 Podejrzyj" link: YouTube's search results, opened by the DJ's browser — no API call. */
class YouTubeSearchLinksTest {

    @Test
    void aSong_getsALinkToYouTubesSearchResults_forItsName() {
        assertThat(YouTubeSearchLinks.forQuery("  Wilki - Baśka & co ")).isEqualTo(
                "https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka+%26+co");
    }

    @Test
    void noName_noLink() {
        assertThat(YouTubeSearchLinks.forQuery(" ")).isNull();
        assertThat(YouTubeSearchLinks.forQuery(null)).isNull();
    }
}
