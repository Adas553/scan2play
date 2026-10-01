package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.service.GuestQueueService.GuestQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a guest sees of the music: what plays now, the next guest songs in the order they play, and where the guest's own song
 * waits (the owner's decision, 2026-09-30).
 */
class GuestQueueServiceTest {

    private static final String PARTY = "ABC12";

    private DjService djService;
    private PlayHistoryService playHistoryService;
    private GuestQueueService service;

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        playHistoryService = mock(PlayHistoryService.class);
        service = new GuestQueueService(djService, playHistoryService);
        when(playHistoryService.getRecentlyPlayed(PARTY, 1)).thenReturn(List.of());
    }

    /** Accepted songs with the ids given, oldest first — the order the dashboard's queue (and the player) takes them in. */
    private void givenQueue(long... ids) {
        when(djService.getDashboardQueue(PARTY)).thenReturn(LongStream.of(ids)
                .mapToObj(id -> SongRequestEntity.builder().id(id).partyCode(PARTY).songName("Song " + id).build())
                .toList());
    }

    private static HistoryEntry played(String title, Instant at) {
        return new HistoryEntry(Source.BACKGROUND, 1L, at, title, null, "aaaaaaaaaaA", null, "played", null, null);
    }

    @Test
    void theNextSongs_areTheFirstFive_inTheOrderTheyPlay() {
        givenQueue(10, 11, 12, 13, 14, 15, 16);

        GuestQueue queue = service.view(PARTY, Set.of());

        assertThat(queue.upNext()).extracting(SongRequestEntity::getId).containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(queue.myPosition()).isNull();
        assertThat(queue.mySong()).isNull();
    }

    @Test
    void theGuestsSong_isFoundInTheWholeQueue_notOnlyInTheFiveShown() {
        givenQueue(10, 11, 12, 13, 14, 15, 16);

        GuestQueue queue = service.view(PARTY, Set.of(16L, 99L));

        assertThat(queue.myPosition()).isEqualTo(7);
        assertThat(queue.mySong()).isEqualTo("Song 16");
    }

    @Test
    void ofSeveralSongsOfTheGuest_theOneThatPlaysFirstIsNamed() {
        givenQueue(10, 11, 12);

        assertThat(service.view(PARTY, Set.of(12L, 11L)).myPosition()).isEqualTo(2);
    }

    @Test
    void whatPlaysNow_isTheLastTrackThatStarted_ifItStartedLately() {
        givenQueue();
        when(playHistoryService.getRecentlyPlayed(PARTY, 1))
                .thenReturn(List.of(played("Wilki - Baśka", Instant.now().minus(2, ChronoUnit.MINUTES))));

        assertThat(service.view(PARTY, Set.of()).nowPlaying()).isEqualTo("Wilki - Baśka");
    }

    @Test
    void aTrackThatStartedLongAgo_isNotShownAsPlaying() {
        givenQueue();
        when(playHistoryService.getRecentlyPlayed(PARTY, 1))
                .thenReturn(List.of(played("Old", Instant.now().minus(20, ChronoUnit.MINUTES))));

        assertThat(service.view(PARTY, Set.of()).nowPlaying()).isNull();
    }
}
