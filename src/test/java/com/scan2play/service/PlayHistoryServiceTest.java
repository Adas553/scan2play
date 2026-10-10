package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryFilter;
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
 * The DJ's history: the guests' requests that played or were rejected, one bounded query, newest event first (the query's order).
 */
@ExtendWith(MockitoExtension.class)
class PlayHistoryServiceTest {

    private static final String PARTY = "ABC12";
    private static final List<String> PLAYED_AND_REJECTED = List.of("played", "rejected");
    private static final Instant NOON = java.time.LocalDateTime.of(2026, 9, 29, 12, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();

    @Mock
    private SongRequestRepository songRequestRepository;

    @InjectMocks
    private PlayHistoryService service;

    private static SongRequestEntity song(long id, String name, String decision, Instant requestedAt, Instant playedAt) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).style("Pop").decision(decision)
                .djComment("ok").trackUrl("https://www.youtube.com/results?search_query=x")
                .requestedAt(requestedAt).playedAt(playedAt).build();
    }

    private void givenRequests(List<String> decisions, int n, SongRequestEntity... songs) {
        when(songRequestRepository.findHistory(eq(PARTY), eq(decisions), eq(PageRequest.of(0, n)))).thenReturn(List.of(songs));
    }

    @Test
    @DisplayName("a request keeps what the AI said about it, its link, the guest's words and its votes")
    void shouldMapARequest() {
        SongRequestEntity played = song(7, "Wilki - Baśka", "played", NOON.minus(30, ChronoUnit.MINUTES), NOON);
        played.setGuestText("ta o Baśce");
        played.setVotes(3);
        givenRequests(PLAYED_AND_REJECTED, 51, played);

        HistoryEntry entry = service.getHistory(PARTY, 50).entries().getFirst();

        assertThat(entry).isEqualTo(new HistoryEntry(7L, NOON, "Wilki - Baśka", "https://www.youtube.com/results?search_query=x",
                "Pop", "played", "ok", "ta o Baśce", 3));
    }

    @Test
    @DisplayName("a rejected request, and one played before V6 (no play time), are placed by when they were requested")
    void shouldFallBackToTheRequestTime() {
        Instant asked = NOON.minus(5, ChronoUnit.MINUTES);
        givenRequests(PLAYED_AND_REJECTED, 51, song(1, "Rejected", "rejected", asked, null));

        assertThat(service.getHistory(PARTY, 50).entries().getFirst().at()).isEqualTo(asked);
    }

    @Test
    @DisplayName("one entry more than asked for is read; it is not shown, but tells there is more")
    void shouldReadOneMoreThanAsked_andSayThereIsMore() {
        givenRequests(PLAYED_AND_REJECTED, 4, IntStream.range(0, 4)
                .mapToObj(i -> song(10 - i, "Song " + i, "played", NOON, NOON.minus(i, ChronoUnit.MINUTES)))
                .toArray(SongRequestEntity[]::new));

        PlayHistoryService.Page page = service.getHistory(PARTY, 3);

        assertThat(page.entries()).extracting(HistoryEntry::title).containsExactly("Song 0", "Song 1", "Song 2");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("exactly as many entries as asked for: nothing older, no \"more\"")
    void shouldNotSayThereIsMore_whenItAllFits() {
        givenRequests(PLAYED_AND_REJECTED, 3, song(1, "A", "played", NOON, NOON), song(2, "B", "played", NOON, NOON));

        PlayHistoryService.Page page = service.getHistory(PARTY, 2);

        assertThat(page.entries()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("an empty history is an empty page")
    void shouldReturnAnEmptyPage() {
        givenRequests(PLAYED_AND_REJECTED, 51);

        PlayHistoryService.Page page = service.getHistory(PARTY, 50);

        assertThat(page.entries()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("Played and Rejected read only their decision; no filter is All")
    void shouldReadTheDecisionsOfTheFilter() {
        givenRequests(List.of("played"), 51);
        givenRequests(List.of("rejected"), 51);
        givenRequests(PLAYED_AND_REJECTED, 51);

        service.getHistory(PARTY, 50, HistoryFilter.PLAYED);
        service.getHistory(PARTY, 50, HistoryFilter.REJECTED);
        service.getHistory(PARTY, 50);

        verify(songRequestRepository).findHistory(PARTY, List.of("played"), PageRequest.of(0, 51));
        verify(songRequestRepository).findHistory(PARTY, List.of("rejected"), PageRequest.of(0, 51));
        verify(songRequestRepository).findHistory(PARTY, PLAYED_AND_REJECTED, PageRequest.of(0, 51));
        verify(songRequestRepository, never()).findHistory(eq("other"), any(), any());
    }
}
