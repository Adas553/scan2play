package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** "Zagrane samo" (docs/ideas.md item 18): which songs of the DJ's program mark which waiting requests. */
class DeckHistoryServiceTest {

    private static final String PARTY = "AB123";
    private static final Instant NOW = Instant.parse("2026-11-21T21:00:00Z");

    private final SongRequestRepository repository = mock(SongRequestRepository.class);
    private final DjService dj = mock(DjService.class);
    private final NowPlayingService nowPlaying = new NowPlayingService();
    private final DeckHistoryService service = new DeckHistoryService(repository, dj, nowPlaying);

    @BeforeEach
    void setUp() {
        when(dj.markSongAsPlayed(anyLong(), anyString())).thenReturn(true);
    }

    private void waiting(SongRequestEntity... requests) {
        when(repository.findTop400ByPartyCodeAndDecisionInOrderByRequestedAtAsc(PARTY, List.of("accepted"))).thenReturn(List.of(requests));
    }

    private static SongRequestEntity request(long id, String song, Duration ago) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(song).decision("accepted").requestedAt(NOW.minus(ago)).build();
    }

    private static DeckHistoryService.Play play(String artist, String title, Duration ago) {
        return new DeckHistoryService.Play(NOW.minus(ago), artist, title);
    }

    @Test
    void theSameSongByItsArtistAndTitle_marksTheRequest() {
        waiting(request(1, "Kayah, Goran Bregović - Prawy do lewego", Duration.ofMinutes(30)),
                request(2, "Sanah - Szampan", Duration.ofMinutes(20)));

        List<String> marked = service.markPlayed(PARTY, List.of(play("Kayah & Bregović", "Prawy do lewego (Radio Edit)", Duration.ZERO)), NOW, false);

        assertThat(marked).containsExactly("Kayah, Goran Bregović - Prawy do lewego");
        verify(dj).markSongAsPlayed(1L, PARTY);
        verify(dj, never()).markSongAsPlayed(2L, PARTY);
    }

    @Test
    void aFileWithoutTags_matchesByItsName() {
        // Serato writes a song without tags with no artist and the file's name as its title
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));

        assertThat(service.markPlayed(PARTY, List.of(play("", "Sanah - Szampan", Duration.ZERO)), NOW, false)).containsExactly("Sanah - Szampan");
    }

    @Test
    void theSameTitleOfAnotherArtist_marksNothing() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));

        assertThat(service.markPlayed(PARTY, List.of(play("Inny Wykonawca", "Szampan", Duration.ZERO)), NOW, false)).isEmpty();
        verify(dj, never()).markSongAsPlayed(anyLong(), anyString());
    }

    @Test
    void aRequestWithoutAnArtist_isNeverMarkedByItself() {
        waiting(request(1, "Szampan", Duration.ofMinutes(10)));

        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ZERO)), NOW, false)).isEmpty();
    }

    @Test
    void aSongThatPlayedBeforeTheGuestAsked_marksNothing() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));

        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ofMinutes(40))), NOW, false)).isEmpty();
        // the DJ's computer's clock a little ahead of the server's is no reason to miss it
        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ofMinutes(12))), NOW, false))
                .containsExactly("Sanah - Szampan");
    }

    @Test
    void withTheSendersClockKnown_aSongThreeMinutesBeforeTheRequest_marksNothing() {
        // "Szampan" played at 21:00, a guest asked for it again at 21:03, the program restarted and sent the last minutes again
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(7)));

        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ofMinutes(10))), NOW, true)).isEmpty();
        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ofMinutes(8))), NOW, true))
                .as("a song that started a minute before the guest asked: the guest asked while it was starting")
                .containsExactly("Sanah - Szampan");
    }

    @Test
    void aSongOfAnotherDay_andAnEmptyOne_areNotEvenLookedUp() {
        List<DeckHistoryService.Play> plays = List.of(play("Sanah", "Szampan", Duration.ofHours(13)), play(null, " ", Duration.ZERO),
                new DeckHistoryService.Play(null, null, null));

        assertThat(service.markPlayed(PARTY, plays, NOW, false)).isEmpty();
        verifyNoInteractions(repository, dj);
    }

    @Test
    void aSongWithoutItsTime_isTakenAsPlayingNow() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(1)));

        assertThat(service.markPlayed(PARTY, List.of(new DeckHistoryService.Play(null, "Sanah", "Szampan")), NOW, false))
                .containsExactly("Sanah - Szampan");
    }

    @Test
    void aRequestTheDjMarkedMeanwhile_isNotSaidToBeMarked() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));
        when(dj.markSongAsPlayed(eq(1L), anyString())).thenReturn(false);

        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ZERO)), NOW, false)).isEmpty();
    }

    // ---- "🎶 Teraz: …" (NowPlayingService): only what the program played, only a guest's request the AI judged ----

    @Test
    void aGuestsRequestThePlayedProgramMarks_isTheRoomsNowPlaying() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));

        service.markPlayed(PARTY, List.of(play("sanah", "Szampan", Duration.ofSeconds(45))), NOW, true);

        assertThat(nowPlaying.now(PARTY, NOW)).contains(new NowPlayingService.Song("Sanah - Szampan", NOW.minusSeconds(45)));
        assertThat(nowPlaying.now(PARTY, NOW.plus(NowPlayingService.SHOWN))).as("a few minutes, not for ever").isEmpty();
    }

    /** Two requests marked by one call (a mix, a call late): the room's song is the one that started last, not the queue's first. */
    @Test
    void twoRequestsMarkedAtOnce_theOneThatStartedLast_isNowPlaying() {
        waiting(request(1, "Kult - Arahja", Duration.ofMinutes(30)), request(2, "Sanah - Szampan", Duration.ofMinutes(20)));

        service.markPlayed(PARTY, List.of(play("Kult", "Arahja", Duration.ofMinutes(4)), play("Sanah", "Szampan", Duration.ofSeconds(45))),
                NOW, true);

        assertThat(nowPlaying.now(PARTY, NOW)).contains(new NowPlayingService.Song("Sanah - Szampan", NOW.minusSeconds(45)));
    }

    @Test
    void theDjsOwnSong_orAGuestsUncheckedWords_areNoNowPlaying() {
        SongRequestEntity own = request(1, "Sanah - Szampan", Duration.ofMinutes(10));
        own.setAddedByDj(true);
        SongRequestEntity unchecked = request(2, "Wilki - Baśka", Duration.ofMinutes(10));
        unchecked.setChecked(false);
        waiting(own);
        assertThat(service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ZERO)), NOW, true)).hasSize(1);
        assertThat(nowPlaying.now(PARTY, NOW)).as("the DJ's own song").isEmpty();

        waiting(unchecked);
        assertThat(service.markPlayed(PARTY, List.of(play("Wilki", "Baśka", Duration.ZERO)), NOW, true)).hasSize(1);
        assertThat(nowPlaying.now(PARTY, NOW)).as("a guest's words the AI did not judge").isEmpty();
    }

    @Test
    void aRequestPlayedBeforeTheCallsNewestSong_isOverAlready_andTheNextSongEndsTheBar() {
        waiting(request(1, "Sanah - Szampan", Duration.ofMinutes(10)));
        // the program restarted: the history read again, the request's song and the one after it
        service.markPlayed(PARTY, List.of(play("Sanah", "Szampan", Duration.ofMinutes(4)), play("Inny", "Utwór", Duration.ofMinutes(1))),
                NOW, true);
        assertThat(nowPlaying.now(PARTY, NOW)).isEmpty();

        waiting(request(2, "Wilki - Baśka", Duration.ofMinutes(10)));
        service.markPlayed(PARTY, List.of(play("Wilki", "Baśka", Duration.ofSeconds(45))), NOW, true);
        assertThat(nowPlaying.now(PARTY, NOW)).isPresent();
        waiting();
        service.markPlayed(PARTY, List.of(play("Wilki", "Baśka", Duration.ofSeconds(44))), NOW, true);
        assertThat(nowPlaying.now(PARTY, NOW)).as("the same song sent again: still now").isPresent();
        service.markPlayed(PARTY, List.of(play("Inny", "Utwór", Duration.ZERO)), NOW.plusSeconds(150), true);
        assertThat(nowPlaying.now(PARTY, NOW.plusSeconds(150))).as("the next song").isEmpty();
    }
}
