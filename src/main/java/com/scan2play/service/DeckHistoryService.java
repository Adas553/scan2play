package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.LibraryKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "Zagrane samo" (docs/ideas.md item 19): the songs the DJ's own program wrote to its play history — VirtualDJ's or Serato's, read
 * by the program on the DJ's computer and sent to {@link com.scan2play.controller.DeviceApiController} — mark the party's waiting
 * requests played, as the DJ's
 * "Zagrane" does ({@link DjService#markSongAsPlayed}). Nothing of the history is kept: a song that matches no request is dropped.
 * <p>
 * A request is marked only when the song is surely the same: its title and an artist in common, by the keys the DJ's library is
 * matched by ({@link LibraryKey}, V42) — "✓". The same title alone ("? Podobny"), a request with no artist, a song with neither
 * artist nor title: nothing. A song that played before the guest asked (the DJ's computer's clock, a little slack for it) never
 * marks the later request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeckHistoryService {

    /** The most songs in one call: the panel sends what it read since its last look, a few at most. */
    public static final int MAX_PLAYS = 20;

    /**
     * A song may have started this much "before" the request it answers: the guest asked while it was starting. With the sender's
     * clock unknown (no {@code sentAt}) the slack covers that clock's error too. Kept short when the clock is known: a song sent
     * again (the program restarted, the panel reloaded — the last 10 minutes go again) must not mark a request made just after it
     * played, "zagraj to jeszcze raz".
     */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(5);
    static final Duration SLACK_CLOCK_KNOWN = Duration.ofMinutes(2);

    /** A song older than this is no news of tonight's set: a history file read again, a day's file of yesterday. */
    public static final Duration OLDEST = Duration.ofHours(12);

    private final SongRequestRepository songRequestRepository;
    private final DjService djService;

    /** One song of the program's history: when it started (null: not known — taken as now), its artist and title as written there. */
    public record Play(Instant at, String artist, String title) {

        /**
         * The song's key: by its artist and title; with no artist (a file without tags writes its name as the title, Serato's
         * "Artist - Title"), by the title as one line, split at its dash as the AI's song names are.
         */
        LibraryKey key() {
            String plainTitle = title == null ? "" : title;
            return artist == null || artist.isBlank() ? LibraryKey.of(plainTitle) : LibraryKey.of(artist, plainTitle);
        }
    }

    /**
     * Marks played each of the party's waiting requests that one of the songs is (see the class); one read of the queue (its 400),
     * one conditional {@code UPDATE} a request marked. {@code clockKnown}: the songs' times were moved to the server's clock
     * ({@code sentAt}), so the shorter slack ({@link #SLACK_CLOCK_KNOWN}).
     *
     * @return the marked requests' song names, in the queue's order — what the panel says it did
     */
    public List<String> markPlayed(String partyCode, List<Play> plays, Instant now, boolean clockKnown) {
        Duration slack = clockKnown ? SLACK_CLOCK_KNOWN : CLOCK_SLACK;
        List<Matchable> songs = new ArrayList<>();
        for (Play play : plays) {
            Instant at = play.at() == null || play.at().isAfter(now) ? now : play.at();
            LibraryKey key = play.key();
            if (!key.isEmpty() && !at.isBefore(now.minus(OLDEST))) {
                songs.add(new Matchable(at, key));
            }
        }
        if (songs.isEmpty()) {
            return List.of();
        }
        List<String> marked = new ArrayList<>();
        for (SongRequestEntity request : songRequestRepository.findTop400ByPartyCodeAndDecisionInOrderByRequestedAtAsc(
                partyCode, List.of("accepted"))) {
            LibraryKey wanted = LibraryKey.of(request.getSongName());
            Instant asked = request.getRequestedAt() == null ? Instant.EPOCH : request.getRequestedAt().minus(slack);
            boolean played = songs.stream().anyMatch(song -> !song.at().isBefore(asked)
                    && wanted.matchIn(List.of(song.key()), Set.of()).mark() == LibraryMark.HAVE);
            if (played && djService.markSongAsPlayed(request.getId(), partyCode)) {
                marked.add(request.getSongName());
            }
        }
        if (!marked.isEmpty()) {
            log.info("The DJ's program played {} waiting request(s) at party {}", marked.size(), partyCode);
        }
        return marked;
    }

    private record Matchable(Instant at, LibraryKey key) {
    }
}
