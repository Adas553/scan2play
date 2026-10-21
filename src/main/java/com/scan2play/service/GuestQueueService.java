package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a guest sees of the requests on the party page: one list of the waiting requests, the most votes first and, among equal
 * votes, the newest first — what the room wants on top, a fresh request in sight to be voted for —, the first
 * {@value #SHOWN} shown, the rest folded under "Pokaż wszystkie prośby (N)" (the owner, 2026-10-08: three lists — "🔥 Najwięcej
 * głosów", "Ostatnio wysłane", all of them — showed one song up to three times). Each song once, with its 👍
 * ({@link GuestVoteService}) or "Twoja". No order to play in: the DJ plays from their own software, in the order the DJ likes.
 */
@Service
@RequiredArgsConstructor
public class GuestQueueService {

    /** How many of the waiting requests the page shows before "Pokaż wszystkie prośby". */
    static final int SHOWN = 5;

    /** How many songs the screen for a TV shows: what a room reads from across the hall. */
    static final int TV_SHOWN = 10;

    private final DjService djService;

    /**
     * @param shown     the first {@value #SHOWN} of the waiting requests, by {@link #byVotes}
     * @param more      the rest of them (the queue's 100 at most), folded on the page
     * @param myIds     the guest's own requests (to mark them in the list)
     * @param mySong    the name of the guest's waiting song when exactly one waits, else null — of several, naming one made the
     *                  guest think the AI had mixed up the songs (the owner, 2026-10-07): the page counts them instead
     * @param myWaiting how many of the guest's songs wait (in the whole queue, not only the ones shown)
     * @param myVotes   the waiting songs the guest gave their 👍
     */
    public record GuestQueue(List<SongRequestEntity> shown, List<SongRequestEntity> more, Set<Long> myIds, String mySong,
                             int myWaiting, Set<Long> myVotes) {

        /** The waiting request {@code id}, if it still waits. */
        public Optional<SongRequestEntity> song(long id) {
            return java.util.stream.Stream.concat(shown.stream(), more.stream()).filter(song -> song.getId() == id).findFirst();
        }
    }

    public GuestQueue view(String partyCode, Set<Long> myIds) {
        return view(partyCode, myIds, Set.of());
    }

    public GuestQueue view(String partyCode, Set<Long> myIds, Set<Long> myVotes) {
        List<SongRequestEntity> queue = djService.getDashboardQueue(partyCode); // oldest first (cached 3 s)
        List<SongRequestEntity> mine = queue.stream().filter(song -> myIds.contains(song.getId())).toList();
        String mySong = mine.size() == 1 ? mine.getFirst().getSongName() : null;
        // a request the AI did not judge is the guest's own words: the one who sent it sees it, the other guests do not (V39)
        List<SongRequestEntity> songs = byVotes(queue.stream()
                .filter(song -> song.isChecked() || myIds.contains(song.getId()))
                .toList());
        int shown = Math.min(SHOWN, songs.size());
        return new GuestQueue(songs.subList(0, shown), songs.subList(shown, songs.size()), myIds, mySong, mine.size(), myVotes);
    }

    /**
     * The screen for a TV ({@code /p/{code}/tv}, Next 7 c): the {@value #TV_SHOWN} most wanted of the waiting songs, in the order of
     * the guests' list — the screen tells the room what the room wants, not what plays next. Only requests the AI judged: an
     * unchecked one is a guest's own words, on a screen the whole hall reads (V39, the review of 2026-10-21).
     */
    public List<SongRequestEntity> mostWanted(String partyCode) {
        List<SongRequestEntity> songs = byVotes(djService.getDashboardQueue(partyCode).stream()   // cached 3 s: a TV polling costs little
                .filter(SongRequestEntity::isChecked)
                .toList());
        return songs.subList(0, Math.min(TV_SHOWN, songs.size()));
    }

    /** Every waiting song, the most votes first, the newest first among equals (the queue comes oldest first). */
    static List<SongRequestEntity> byVotes(List<SongRequestEntity> queue) {
        return queue.reversed().stream()
                .sorted(Comparator.comparingInt(SongRequestEntity::getVotes).reversed())   // stable: the newest first among equals
                .toList();
    }
}
