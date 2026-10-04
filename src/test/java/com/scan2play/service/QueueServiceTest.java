package com.scan2play.service;

import com.scan2play.integration.MusicProvider;
import com.scan2play.model.MusicProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link QueueService} — provider delegation logic.
 * Verifies correct routing to the YouTube / requests-only implementations and error handling.
 */
class QueueServiceTest {

    private MusicProvider requestsOnlyProvider;
    private MusicProvider youtubeProvider;
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        requestsOnlyProvider = mock(MusicProvider.class);
        youtubeProvider = mock(MusicProvider.class);
        when(requestsOnlyProvider.getType()).thenReturn(MusicProviderType.REQUESTS_ONLY);
        when(youtubeProvider.getType()).thenReturn(MusicProviderType.YOUTUBE);

        queueService = new QueueService(List.of(requestsOnlyProvider, youtubeProvider));
    }

    // ---- resolveTrack ----

    @Test
    void resolveTrack_shouldDelegateToTheRequestsOnlyProvider() {
        when(requestsOnlyProvider.findTrackUrl("Nirvana")).thenReturn("https://www.youtube.com/results?search_query=Nirvana");

        String result = queueService.resolveTrack("Nirvana", MusicProviderType.REQUESTS_ONLY);

        assertThat(result).isEqualTo("https://www.youtube.com/results?search_query=Nirvana");
        verify(requestsOnlyProvider).findTrackUrl("Nirvana");
        verify(youtubeProvider, never()).findTrackUrl(any());
    }

    @Test
    void resolveTrack_shouldDelegateToYouTubeProvider() {
        when(youtubeProvider.findTrackUrl("Nirvana")).thenReturn("https://www.youtube.com/watch?v=hTWKbfoikeg");

        String result = queueService.resolveTrack("Nirvana", MusicProviderType.YOUTUBE);

        assertThat(result).isEqualTo("https://www.youtube.com/watch?v=hTWKbfoikeg");
        verify(youtubeProvider).findTrackUrl("Nirvana");
        verify(requestsOnlyProvider, never()).findTrackUrl(any());
    }

    @Test
    void resolveTrack_shouldThrow_whenProviderNotConfigured() {
        // Create service with only the requests-only provider — no YouTube
        QueueService partialService = new QueueService(List.of(requestsOnlyProvider));

        assertThatThrownBy(() -> partialService.resolveTrack("Song", MusicProviderType.YOUTUBE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }
}
