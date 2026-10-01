package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.RequestMode;
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
    @Mock
    private YouTubePlaylistClient youTubePlaylistClient;

    private SongEvaluationService service;

    @BeforeEach
    void setUp() {
        // The Gemini client is not used by the auto-queue; the prompts are the real ones from the classpath.
        service = new SongEvaluationService(null, new ObjectMapper(), songRequestRepository, partySettingsQueryService,
                queueService, messageSource, new DefaultResourceLoader(), transactionManager, youTubePlaylistClient);
        service.init();
    }

    // ---- buildPrompt: each mode has its own prompt ----

    @Test
    void theSongMode_asksForTheSongTheGuestMeans_theMoodMode_forASongThatFitsTheMood() {
        String song = service.buildPrompt("chciałbym być marynarzem", "ANY", null, java.util.Locale.of("pl"), RequestMode.SONG);
        String mood = service.buildPrompt("coś do tańca", "ANY", null, java.util.Locale.of("pl"), RequestMode.MOOD);
        String moodEn = service.buildPrompt("something to dance to", "ANY", "A - B", java.util.Locale.ENGLISH, RequestMode.MOOD);

        assertThat(song).contains("KONKRETNĄ PIOSENKĘ", "\"chciałbym być marynarzem\"", "\"lyrics\"").doesNotContain("%s");
        // a song the model does not know (a new one: "Shakira & Burna Boy – Dai Dai", May 2026) is not rejected for that
        assertThat(song).contains("NIE ZNASZ piosenki, NIE jest powodem do odrzucenia");
        assertThat(mood).contains("NASTRÓJ", "\"coś do tańca\"").doesNotContain("KONKRETNĄ PIOSENKĘ").doesNotContain("%s");
        assertThat(moodEn).contains("MOOD", "\"something to dance to\"", "A - B").doesNotContain("%s");
    }

    @Test
    void aMood_isNeverSearchedByTheGuestsWords() {
        DjResponse lyricsByMistake = new DjResponse("accepted", "ok", "Artist - Dance Song", 8, "lyrics");

        assertThat(service.searchQueryFor(lyricsByMistake, "coś do tańca", MusicProviderType.YOUTUBE, RequestMode.MOOD))
                .isEqualTo("Artist - Dance Song");
    }

    // ---- searchQueryFor: a line of lyrics is searched by the guest's own words ----

    private static DjResponse answer(String json) throws Exception {
        return new ObjectMapper().readValue(json, DjResponse.class);
    }

    @Test
    void theAnswerOfTheAi_isReadWithTheKindOfTheRequest_andWithoutIt() throws Exception {
        DjResponse lyrics = answer("{\"decision\":\"accepted\",\"comment\":\"Na pokład!\",\"songName\":\"X - Y\",\"energyLevel\":7,"
                + "\"requestKind\":\"lyrics\"}");
        assertThat(lyrics.isLyrics()).isTrue();
        assertThat(lyrics.songName()).isEqualTo("X - Y");

        DjResponse old = answer("{\"decision\":\"accepted\",\"comment\":\"ok\",\"songName\":\"X - Y\",\"energyLevel\":7}");
        assertThat(old.requestKind()).isNull();
        assertThat(old.isLyrics()).isFalse();
    }

    @Test
    void lyricsAtAYouTubeParty_areSearchedByTheGuestsOwnWords() {
        DjResponse ai = new DjResponse("accepted", "Na pokład!", "Elektryczne Gitary - Chciałbym być marynarzem", 7, "lyrics");

        assertThat(service.searchQueryFor(ai, "  chciałbym być marynarzem ", MusicProviderType.YOUTUBE, RequestMode.SONG))
                .isEqualTo("chciałbym być marynarzem");
    }

    @Test
    void everythingElse_isSearchedByTheAisName() {
        DjResponse title = new DjResponse("accepted", "ok", "Wilki - Baśka", 7, "title");
        DjResponse lyrics = new DjResponse("accepted", "ok", "Wilki - Baśka", 7, "lyrics");
        DjResponse unknown = new DjResponse("accepted", "ok", "Wilki - Baśka", 7);

        assertThat(service.searchQueryFor(title, "baska wilki", MusicProviderType.YOUTUBE, RequestMode.SONG)).isEqualTo("Wilki - Baśka");
        assertThat(service.searchQueryFor(unknown, "baska wilki", MusicProviderType.YOUTUBE, RequestMode.SONG)).isEqualTo("Wilki - Baśka");
        assertThat(service.searchQueryFor(lyrics, "baśka miała fajny biust", MusicProviderType.SPOTIFY, RequestMode.SONG))
                .as("Spotify's search does not match lyrics").isEqualTo("Wilki - Baśka");
    }

    // ---- nameOfTrack: the name of what plays, not the AI's guess ----

    @Test
    void nameOfTrack_isTheVideosOwnTitle_cleanedOfTheUploadTags() {
        when(youTubePlaylistClient.findTitle("gH476CxJxfg"))
                .thenReturn(Optional.of("Chciałem być - Krzysztof Krawczyk (Official Video) [HD]"));

        String name = service.nameOfTrack("Janusz Rewiński - Chciałbym być marynarzem",
                "https://www.youtube.com/watch?v=gH476CxJxfg", MusicProviderType.YOUTUBE);

        assertThat(name).isEqualTo("Chciałem być - Krzysztof Krawczyk");
    }

    @Test
    void nameOfTrack_keepsTheName_withoutAVideoOrItsTitle() {
        when(youTubePlaylistClient.findTitle("gH476CxJxfg")).thenReturn(Optional.empty());

        assertThat(service.nameOfTrack("A - B", "https://www.youtube.com/watch?v=gH476CxJxfg", MusicProviderType.YOUTUBE))
                .as("no title (no key, an API error)").isEqualTo("A - B");
        assertThat(service.nameOfTrack("A - B", "https://www.youtube.com/results?search_query=A+B", MusicProviderType.YOUTUBE))
                .as("a search link").isEqualTo("A - B");
        assertThat(service.nameOfTrack("A - B", null, MusicProviderType.YOUTUBE)).as("nothing found").isEqualTo("A - B");
        assertThat(service.nameOfTrack("A - B", TRACK, MusicProviderType.SPOTIFY)).as("a Spotify party").isEqualTo("A - B");
        verify(youTubePlaylistClient).findTitle("gH476CxJxfg");
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
