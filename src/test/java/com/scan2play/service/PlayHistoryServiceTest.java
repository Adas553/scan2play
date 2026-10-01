package com.scan2play.service;

import com.scan2play.entity.FallbackPlayEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.model.HistoryFilter;
import com.scan2play.repository.FallbackPlayRepository;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DJ's timeline of what played: guest songs and background tracks, from two tables, on one list.
 */
@ExtendWith(MockitoExtension.class)
class PlayHistoryServiceTest {

    private static final String PARTY = "ABC12";
    private static final List<String> PLAYED_AND_REJECTED = List.of("played", "rejected");
    private static final List<String> PLAYED = List.of("played");
    private static final Instant NOON = java.time.LocalDateTime.of(2026, 9, 29, 12, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();

    @Mock
    private SongRequestRepository songRequestRepository;
    @Mock
    private FallbackPlayRepository fallbackPlayRepository;

    @InjectMocks
    private PlayHistoryService service;

    private static SongRequestEntity song(long id, String name, String decision, String trackUrl,
                                          Instant requestedAt, Instant playedAt) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).style("Pop").decision(decision)
                .djComment("ok").energyLevel(6).trackUrl(trackUrl).requestedAt(requestedAt).playedAt(playedAt).build();
    }

    private static SongRequestEntity playedSong(long id, String name, Instant playedAt) {
        return song(id, name, "played", "https://www.youtube.com/watch?v=hTWKbfoikeg", playedAt.minus(30, ChronoUnit.MINUTES), playedAt);
    }

    /** One row of the play log: the id is the id of that play. */
    private static FallbackPlayEntity play(long id, String videoId, String title, Instant playedAt) {
        return FallbackPlayEntity.builder().id(id).partyCode(PARTY).videoId(videoId).title(title)
                .fetchedAt(playedAt.minus(1, ChronoUnit.DAYS)).playedAt(playedAt).build();
    }

    private void givenGuests(List<String> decisions, int n, SongRequestEntity... songs) {
        when(songRequestRepository.findHistory(eq(PARTY), eq(decisions), eq(PageRequest.of(0, n)))).thenReturn(List.of(songs));
    }

    private void givenTracks(int n, FallbackPlayEntity... plays) {
        when(fallbackPlayRepository.findRecent(eq(PARTY), eq(PageRequest.of(0, n)))).thenReturn(List.of(plays));
    }

    // ---- the history: one timeline ----

    @Test
    @DisplayName("guest songs and background tracks are merged into one list, the most recent event first")
    void shouldMergeBothSourcesNewestFirst() {
        givenGuests(PLAYED_AND_REJECTED, 51,
                playedSong(1, "Guest song, played 12:10", NOON.plus(10, ChronoUnit.MINUTES)),
                playedSong(2, "Guest song, played 11:20", NOON.minus(40, ChronoUnit.MINUTES)));
        givenTracks(51,
                play(7, "aaaaaaaaaaa", "Playlist track 12:30", NOON.plus(30, ChronoUnit.MINUTES)),
                play(6, "bbbbbbbbbbb", "Playlist track 11:50", NOON.minus(10, ChronoUnit.MINUTES)));

        PlayHistoryService.Page page = service.getHistory(PARTY, 50);

        assertThat(page.entries()).extracting(HistoryEntry::title).containsExactly(
                "Playlist track 12:30", "Guest song, played 12:10", "Playlist track 11:50", "Guest song, played 11:20");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("a guest song carries the guest's own words (V14); a background track has none")
    void shouldCarryTheGuestsWords() {
        SongRequestEntity shrek = playedSong(1, "Smash Mouth - All Star", NOON);
        shrek.setGuestText("the one from Shrek");
        givenGuests(PLAYED_AND_REJECTED, 51, shrek);
        givenTracks(51, play(7, "aaaaaaaaaaa", "Playlist track", NOON.plus(5, ChronoUnit.MINUTES)));

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::guestText)
                .containsExactly(null, "the one from Shrek");
    }

    @Test
    @DisplayName("a guest song is placed by when it was played, not by when it was requested")
    void shouldOrderByThePlayTime_notTheRequestTime() {
        // requested first, played last
        SongRequestEntity requestedEarly = song(1, "Requested early", "played", null, NOON.minus(3, ChronoUnit.HOURS), NOON.plus(1, ChronoUnit.HOURS));
        SongRequestEntity requestedLate = song(2, "Requested late", "played", null, NOON, NOON.minus(5, ChronoUnit.MINUTES));
        givenGuests(PLAYED_AND_REJECTED, 51, requestedEarly, requestedLate);
        givenTracks(51);

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::title)
                .containsExactly("Requested early", "Requested late");
        assertThat(service.getHistory(PARTY, 50).entries().getFirst().at()).isEqualTo(NOON.plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("a rejected request, and one played before V6 (no play time), are placed by when they were requested")
    void shouldFallBackToTheRequestTime() {
        SongRequestEntity rejected = song(1, "Rejected", "rejected", null, NOON.plus(5, ChronoUnit.MINUTES), null);
        SongRequestEntity oldPlayed = song(2, "Played before V6", "played", null, NOON.minus(5, ChronoUnit.MINUTES), null);
        givenGuests(PLAYED_AND_REJECTED, 51, rejected, oldPlayed);
        givenTracks(51, play(7, "aaaaaaaaaaa", "Track", NOON));

        List<HistoryEntry> entries = service.getHistory(PARTY, 50).entries();

        assertThat(entries).extracting(HistoryEntry::title).containsExactly("Rejected", "Track", "Played before V6");
        assertThat(entries.get(0).at()).isEqualTo(NOON.plus(5, ChronoUnit.MINUTES));
        assertThat(entries.get(2).at()).isEqualTo(NOON.minus(5, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("entries at the very same moment keep a stable order: the higher id first")
    void shouldBreakTiesByIdDescending() {
        givenGuests(PLAYED_AND_REJECTED, 51, playedSong(1, "One", NOON), playedSong(3, "Three", NOON));
        givenTracks(51, play(2, "aaaaaaaaaaa", "Two", NOON));

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::title)
                .containsExactly("Three", "Two", "One");
    }

    @Test
    @DisplayName("a guest's song keeps what the AI said about it and is a GUEST entry with a key of its own")
    void shouldMapAGuestSong() {
        givenGuests(PLAYED_AND_REJECTED, 51, playedSong(42, "Guest song", NOON));
        givenTracks(51);

        HistoryEntry entry = service.getHistory(PARTY, 50).entries().getFirst();

        assertThat(entry.source()).isEqualTo(Source.GUEST);
        assertThat(entry.key()).isEqualTo("G:42");
        assertThat(entry.style()).isEqualTo("Pop");
        assertThat(entry.decision()).isEqualTo("played");
        assertThat(entry.djComment()).isEqualTo("ok");
        assertThat(entry.energyLevel()).isEqualTo(6);
        assertThat(entry.videoId()).isEqualTo("hTWKbfoikeg");
    }

    @Test
    @DisplayName("a background track is a played BACKGROUND entry with its title, a watch link and no style, comment or energy")
    void shouldMapABackgroundTrack() {
        givenGuests(PLAYED_AND_REJECTED, 51);
        givenTracks(51, play(7, "dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", NOON));

        HistoryEntry entry = service.getHistory(PARTY, 50).entries().getFirst();

        assertThat(entry.source()).isEqualTo(Source.BACKGROUND);
        assertThat(entry.key()).isEqualTo("B:7");
        assertThat(entry.title()).isEqualTo("Rick Astley - Never Gonna Give You Up");
        assertThat(entry.trackUrl()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(entry.videoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(entry.decision()).isEqualTo("played");
        assertThat(entry.style()).isNull();
        assertThat(entry.djComment()).isNull();
        assertThat(entry.energyLevel()).isNull();
    }

    @Test
    @DisplayName("a background track without a known title is shown as youtu.be/<id>, like in the up-next list")
    void shouldNameATrackWithoutATitleByItsVideoId() {
        givenGuests(PLAYED_AND_REJECTED, 51);
        givenTracks(51, play(7, "dQw4w9WgXcQ", null, NOON), play(8, "aaaaaaaaaaa", "   ", NOON.minus(1, ChronoUnit.MINUTES)));

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::title)
                .containsExactly("youtu.be/dQw4w9WgXcQ", "youtu.be/aaaaaaaaaaa");
    }

    // ---- paging ----

    @Test
    @DisplayName("each source is read with a bound of limit + 1, and the extra entry is not shown but tells there is more")
    void shouldReadOneMoreThanAsked_andSayThereIsMore() {
        List<SongRequestEntity> guests = IntStream.range(0, 4)
                .mapToObj(i -> playedSong(i, "Guest " + i, NOON.minus(2L * i, ChronoUnit.MINUTES))).toList();
        List<FallbackPlayEntity> tracks = IntStream.range(0, 4)
                .mapToObj(i -> play(100 + i, "vid" + String.format("%08d", i), "Track " + i, NOON.minus(2L * i + 1, ChronoUnit.MINUTES))).toList();
        when(songRequestRepository.findHistory(eq(PARTY), eq(PLAYED_AND_REJECTED), eq(PageRequest.of(0, 6)))).thenReturn(guests);
        when(fallbackPlayRepository.findRecent(eq(PARTY), eq(PageRequest.of(0, 6))))
                .thenReturn(tracks);

        PlayHistoryService.Page page = service.getHistory(PARTY, 5);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.entries()).hasSize(5);
        assertThat(page.entries()).extracting(HistoryEntry::title)
                .containsExactly("Guest 0", "Track 0", "Guest 1", "Track 1", "Guest 2");
    }

    @Test
    @DisplayName("exactly as many entries as asked for: nothing older, no \"more\"")
    void shouldNotSayThereIsMore_whenItAllFits() {
        givenGuests(PLAYED_AND_REJECTED, 6, playedSong(1, "One", NOON), playedSong(2, "Two", NOON.minus(1, ChronoUnit.MINUTES)),
                playedSong(3, "Three", NOON.minus(2, ChronoUnit.MINUTES)));
        givenTracks(6, play(7, "aaaaaaaaaaa", "Four", NOON.minus(3, ChronoUnit.MINUTES)), play(8, "bbbbbbbbbbb", "Five", NOON.minus(4, ChronoUnit.MINUTES)));

        PlayHistoryService.Page page = service.getHistory(PARTY, 5);

        assertThat(page.entries()).hasSize(5);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("an empty history is an empty page")
    void shouldReturnAnEmptyPage() {
        givenGuests(PLAYED_AND_REJECTED, 51);
        givenTracks(51);

        PlayHistoryService.Page page = service.getHistory(PARTY, 50);

        assertThat(page.entries()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    // ---- the filter buttons: the server reads only the entries of the chosen kind ----

    private static final List<String> REJECTED = List.of("rejected");

    @Test
    @DisplayName("Guests: only the guests' requests, played and rejected — the playlist table is not read at all")
    void shouldReadOnlyTheGuests_forTheGuestsFilter() {
        givenGuests(PLAYED_AND_REJECTED, 51, playedSong(1, "Guest song", NOON),
                song(2, "Rejected one", "rejected", null, NOON.minus(5, ChronoUnit.MINUTES), null));

        PlayHistoryService.Page page = service.getHistory(PARTY, 50, HistoryFilter.GUEST);

        assertThat(page.entries()).extracting(HistoryEntry::key).containsExactly("G:1", "G:2");
        verify(fallbackPlayRepository, never()).findRecent(any(), any());
    }

    @Test
    @DisplayName("Playlist: only the tracks of the background playlist — the requests table is not read at all")
    void shouldReadOnlyThePlaylist_forThePlaylistFilter() {
        givenTracks(51, play(7, "aaaaaaaaaaa", "Track one", NOON), play(6, "bbbbbbbbbbb", "Track two", NOON.minus(3, ChronoUnit.MINUTES)));

        PlayHistoryService.Page page = service.getHistory(PARTY, 50, HistoryFilter.BACKGROUND);

        assertThat(page.entries()).extracting(HistoryEntry::key).containsExactly("B:7", "B:6");
        verify(songRequestRepository, never()).findHistory(any(), any(), any());
    }

    @Test
    @DisplayName("Played: the guests' requests that played and the playlist tracks, no rejected request")
    void shouldReadWhatPlayed_forThePlayedFilter() {
        givenGuests(PLAYED, 51, playedSong(1, "Guest song", NOON.minus(5, ChronoUnit.MINUTES)));
        givenTracks(51, play(7, "aaaaaaaaaaa", "Track", NOON));

        PlayHistoryService.Page page = service.getHistory(PARTY, 50, HistoryFilter.PLAYED);

        assertThat(page.entries()).extracting(HistoryEntry::key).containsExactly("B:7", "G:1");
        verify(songRequestRepository, never()).findHistory(eq(PARTY), eq(PLAYED_AND_REJECTED), any());
    }

    @Test
    @DisplayName("Rejected: only the rejected requests — the playlist table is not read at all")
    void shouldReadOnlyTheRejected_forTheRejectedFilter() {
        givenGuests(REJECTED, 51, song(2, "Rejected one", "rejected", null, NOON, null));

        PlayHistoryService.Page page = service.getHistory(PARTY, 50, HistoryFilter.REJECTED);

        assertThat(page.entries()).extracting(HistoryEntry::key).containsExactly("G:2");
        assertThat(page.entries().getFirst().decision()).isEqualTo("rejected");
        verify(fallbackPlayRepository, never()).findRecent(any(), any());
    }

    @Test
    @DisplayName("the limit counts the entries of the chosen kind: however many playlist tracks lie between, the last 5 guests' requests are the page")
    void shouldCountOnlyTheChosenKindAgainstTheLimit() {
        List<SongRequestEntity> guests = IntStream.range(0, 6)
                .mapToObj(i -> playedSong(i, "Guest " + i, NOON.minus(i, ChronoUnit.HOURS))).toList();   // one an hour: between them, the playlist played a lot
        givenGuests(PLAYED_AND_REJECTED, 6, guests.toArray(new SongRequestEntity[0]));

        PlayHistoryService.Page page = service.getHistory(PARTY, 5, HistoryFilter.GUEST);

        assertThat(page.entries()).extracting(HistoryEntry::title).containsExactly("Guest 0", "Guest 1", "Guest 2", "Guest 3", "Guest 4");
        assertThat(page.hasMore()).isTrue();
        verify(fallbackPlayRepository, never()).findRecent(any(), any());
    }

    @Test
    @DisplayName("without a filter the history is everything: the same two reads as the All filter")
    void shouldMeanAll_whenNoFilterIsGiven() {
        givenGuests(PLAYED_AND_REJECTED, 51, playedSong(1, "Guest song", NOON));
        givenTracks(51, play(7, "aaaaaaaaaaa", "Track", NOON.plus(1, ChronoUnit.MINUTES)));

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::key).containsExactly("B:7", "G:1");
        assertThat(service.getHistory(PARTY, 50, HistoryFilter.ALL).entries()).extracting(HistoryEntry::key).containsExactly("B:7", "G:1");
    }

    // ---- recently played: what "previous track" walks back along ----

    @Test
    @DisplayName("recently played: only what played (no rejected requests), guest songs and background tracks together, newest first")
    void shouldListWhatPlayedRecently() {
        givenGuests(PLAYED, 30, playedSong(1, "Guest song", NOON.minus(5, ChronoUnit.MINUTES)));
        givenTracks(30, play(7, "aaaaaaaaaaa", "Track", NOON));

        List<HistoryEntry> recent = service.getRecentlyPlayed(PARTY, 30);

        assertThat(recent).extracting(HistoryEntry::key).containsExactly("B:7", "G:1");
        verify(songRequestRepository, never()).findHistory(any(), eq(PLAYED_AND_REJECTED), any());
    }

    @Test
    @DisplayName("a video that plays in two rounds of the playlist is two entries with two keys — history and \"previous track\" alike")
    void shouldKeepEveryPlayOfTheSameVideoAsAnEntryOfItsOwn() {
        // A B A: the playlist has looped and A came round again. The key must tell the two A's apart, otherwise "back"
        // from the older A would find the newer one and go round in circles.
        givenGuests(PLAYED_AND_REJECTED, 51);
        givenGuests(PLAYED, 30);
        givenTracks(51, play(9, "aaaaaaaaaaa", "A", NOON.plus(6, ChronoUnit.MINUTES)), play(8, "bbbbbbbbbbb", "B", NOON.plus(3, ChronoUnit.MINUTES)),
                play(7, "aaaaaaaaaaa", "A", NOON));
        givenTracks(30, play(9, "aaaaaaaaaaa", "A", NOON.plus(6, ChronoUnit.MINUTES)), play(8, "bbbbbbbbbbb", "B", NOON.plus(3, ChronoUnit.MINUTES)),
                play(7, "aaaaaaaaaaa", "A", NOON));

        assertThat(service.getHistory(PARTY, 50).entries()).extracting(HistoryEntry::key).containsExactly("B:9", "B:8", "B:7");
        List<HistoryEntry> recent = service.getRecentlyPlayed(PARTY, 30);
        assertThat(recent).extracting(HistoryEntry::key).containsExactly("B:9", "B:8", "B:7").doesNotHaveDuplicates();
        assertThat(recent).extracting(HistoryEntry::videoId).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa");
    }

    @Test
    @DisplayName("recently played: what the embedded player cannot play (a Spotify track, a YouTube search page) is left out")
    void shouldLeaveOutWhatCannotBePlayedAgain() {
        givenGuests(PLAYED, 30,
                song(1, "Spotify song", "played", "spotify:track:abc", NOON.minus(10, ChronoUnit.MINUTES), NOON.minus(1, ChronoUnit.MINUTES)),
                song(2, "Search page", "played", "https://www.youtube.com/results?search_query=some+song", NOON.minus(10, ChronoUnit.MINUTES), NOON.minus(2, ChronoUnit.MINUTES)),
                song(3, "No link", "played", null, NOON.minus(10, ChronoUnit.MINUTES), NOON.minus(3, ChronoUnit.MINUTES)),
                playedSong(4, "Playable", NOON.minus(4, ChronoUnit.MINUTES)));
        givenTracks(30);

        assertThat(service.getRecentlyPlayed(PARTY, 30)).extracting(HistoryEntry::title).containsExactly("Playable");
    }

    @Test
    @DisplayName("recently played: a short youtu.be link is playable too")
    void shouldFindTheVideoIdOfAShortLink() {
        givenGuests(PLAYED, 30, song(1, "Short link", "played", "https://youtu.be/hTWKbfoikeg?si=x", NOON, NOON));
        givenTracks(30);

        assertThat(service.getRecentlyPlayed(PARTY, 30)).extracting(HistoryEntry::videoId).containsExactly("hTWKbfoikeg");
    }
}
