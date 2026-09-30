package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the Auto-Pilot of a Spotify party in {@link SongEvaluationService}: an accepted song goes straight into the DJ's
 * Spotify queue and counts as played only when Spotify has taken it.
 */
@ExtendWith(MockitoExtension.class)
class SongEvaluationServiceTest {

    private static final String PARTY_CODE = "SPT01";
    private static final String TRACK = "spotify:track:abc123";
    private static final String FAILED_NOTE = "(Auto-Pilot failed)";

    @Mock
    private SongRequestRepository songRequestRepository;
    @Mock
    private PartySettingsQueryService partySettingsQueryService;
    @Mock
    private QueueService queueService;
    @Mock
    private MessageSource messageSource;
    @Mock
    private PlatformTransactionManager transactionManager;

    private SongEvaluationService service;

    @BeforeEach
    void setUp() {
        // The Gemini client is not used by the auto-queue; the prompts are the real ones from the classpath.
        service = new SongEvaluationService(null, new ObjectMapper(), songRequestRepository, partySettingsQueryService,
                queueService, messageSource, new DefaultResourceLoader(), transactionManager);
        service.init();
    }

    private static PartySettingsEntity spotifyAutoParty() {
        return PartySettingsEntity.builder().partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.SPOTIFY).playbackMode(PlaybackMode.AUTO).build();
    }

    private SongRequestEntity acceptedSong() {
        SongRequestEntity song = SongRequestEntity.builder().id(7L).partyCode(PARTY_CODE).songName("Song")
                .decision(DECISION_ACCEPTED).djComment("Great pick!").trackUrl(TRACK).build();
        when(songRequestRepository.findById(7L)).thenReturn(Optional.of(song));
        return song;
    }

    @Test
    void handleAutoQueue_marksTheSongPlayed_whenSpotifyTookIt() {
        SongRequestEntity song = acceptedSong();
        when(queueService.addToQueue(PARTY_CODE, TRACK, MusicProviderType.SPOTIFY))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.handleAutoQueue(spotifyAutoParty(), song, TRACK, FAILED_NOTE);

        assertThat(song.getDecision()).isEqualTo(DECISION_PLAYED);
        assertThat(song.getPlayedAt()).isNotNull();
        assertThat(song.getDjComment()).isEqualTo("Great pick!");
    }

    @Test
    void handleAutoQueue_leavesTheSongInTheQueueWithANote_whenSpotifyRefusedIt() {
        SongRequestEntity song = acceptedSong();
        when(queueService.addToQueue(PARTY_CODE, TRACK, MusicProviderType.SPOTIFY))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no active device")));

        service.handleAutoQueue(spotifyAutoParty(), song, TRACK, FAILED_NOTE);

        assertThat(song.getDecision()).isEqualTo(DECISION_ACCEPTED);
        assertThat(song.getPlayedAt()).isNull();
        assertThat(song.getDjComment()).isEqualTo("Great pick! " + FAILED_NOTE);
    }

    @Test
    void handleAutoQueue_doesNothing_forAYouTubeParty() {
        SongRequestEntity song = SongRequestEntity.builder().id(7L).decision(DECISION_ACCEPTED).build();
        PartySettingsEntity youtube = PartySettingsEntity.builder().partyCode(PARTY_CODE)
                .activeProvider(MusicProviderType.YOUTUBE).playbackMode(PlaybackMode.AUTO).build();

        service.handleAutoQueue(youtube, song, TRACK, FAILED_NOTE);

        verify(queueService, never()).addToQueue(PARTY_CODE, TRACK, MusicProviderType.YOUTUBE);
    }
}
