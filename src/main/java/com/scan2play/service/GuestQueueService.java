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

        /** How many requests wait (the shown and the folded ones). */
        public int total() {
            return shown.size() + more.size();
        }

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
        List<SongRequestEntity> songs = byVotes(queue);
        int shown = Math.min(SHOWN, songs.size());
        return new GuestQueue(songs.subList(0, shown), songs.subList(shown, songs.size()), myIds, mySong, mine.size(), myVotes);
    }

    /** Every waiting song, the most votes first, the newest first among equals (the queue comes oldest first). */
    static List<SongRequestEntity> byVotes(List<SongRequestEntity> queue) {
        return queue.reversed().stream()
                .sorted(Comparator.comparingInt(SongRequestEntity::getVotes).reversed())   // stable: the newest first among equals
                .toList();
    }
}
