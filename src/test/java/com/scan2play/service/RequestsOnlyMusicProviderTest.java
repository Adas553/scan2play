package com.scan2play.service;

import com.scan2play.model.MusicProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A requests-only party: a song gets a link to YouTube's search results (opened by the DJ's browser — no API call, no share of the
 * daily search budget), and there is no queue to add to.
 */
class RequestsOnlyMusicProviderTest {

    private final RequestsOnlyMusicProvider provider = new RequestsOnlyMusicProvider();

    @Test
    void aSong_getsALinkToYouTubesSearchResults_forItsName() {
        assertThat(provider.getType()).isEqualTo(MusicProviderType.REQUESTS_ONLY);
        assertThat(provider.findTrackUrl("  Wilki - Baśka & co ")).isEqualTo(
                "https://www.youtube.com/results?search_query=Wilki+-+Ba%C5%9Bka+%26+co");
    }

    @Test
    void noName_noLink() {
        assertThat(provider.findTrackUrl(" ")).isNull();
        assertThat(provider.findTrackUrl(null)).isNull();
    }

    @Test
    void thereIsNoQueueToAddTo() {
        assertThatThrownBy(() -> provider.addToQueue("ABC12", "anything")).isInstanceOf(UnsupportedOperationException.class);
    }
}
