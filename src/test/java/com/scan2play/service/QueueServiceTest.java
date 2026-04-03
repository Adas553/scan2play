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
 * Verifies correct routing to Spotify/YouTube implementations and error handling.
 */
class QueueServiceTest {

    private MusicProvider spotifyProvider;
    private MusicProvider youtubeProvider;
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        spotifyProvider = mock(MusicProvider.class);
        youtubeProvider = mock(MusicProvider.class);
        when(spotifyProvider.getType()).thenReturn(MusicProviderType.SPOTIFY);
        when(youtubeProvider.getType()).thenReturn(MusicProviderType.YOUTUBE);

        queueService = new QueueService(List.of(spotifyProvider, youtubeProvider));
    }

    // ---- resolveTrack ----

    @Test
    void resolveTrack_shouldDelegateToSpotifyProvider() {
        when(spotifyProvider.findTrackUrl("Nirvana")).thenReturn("spotify:track:abc123");

        String result = queueService.resolveTrack("Nirvana", MusicProviderType.SPOTIFY);

        assertThat(result).isEqualTo("spotify:track:abc123");
        verify(spotifyProvider).findTrackUrl("Nirvana");
        verify(youtubeProvider, never()).findTrackUrl(any());
    }

    @Test
    void resolveTrack_shouldDelegateToYouTubeProvider() {
        when(youtubeProvider.findTrackUrl("Nirvana")).thenReturn("https://www.youtube.com/watch?v=hTWKbfoikeg");

        String result = queueService.resolveTrack("Nirvana", MusicProviderType.YOUTUBE);

        assertThat(result).isEqualTo("https://www.youtube.com/watch?v=hTWKbfoikeg");
        verify(youtubeProvider).findTrackUrl("Nirvana");
        verify(spotifyProvider, never()).findTrackUrl(any());
    }

    @Test
    void resolveTrack_shouldThrow_whenProviderNotConfigured() {
        // Create service with only Spotify — no YouTube
        QueueService partialService = new QueueService(List.of(spotifyProvider));

        assertThatThrownBy(() -> partialService.resolveTrack("Song", MusicProviderType.YOUTUBE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    // ---- addToQueue ----

    @Test
    void addToQueue_shouldDelegateToCorrectProvider() {
        queueService.addToQueue("ABC12", "spotify:track:abc", MusicProviderType.SPOTIFY);

        verify(spotifyProvider).addToQueue("ABC12", "spotify:track:abc");
        verify(youtubeProvider, never()).addToQueue(any(), any());
    }
}

