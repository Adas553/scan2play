package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.MusicProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What a guest sees of the music on the party page: what plays now, the guest songs that come next in the order they will play
 * (oldest first — a list of the newest would show a guest their song on top although it waits at the end), and where the guest's
 * own song waits.
 * <p>
 * The tracks of the background playlist are left out of "next": they play only when no guest song waits, and listing them would
 * make a guest think their song comes after them.
 * <p>
 * A requests-only party has no order: its DJ plays the requests from their own software, in the order the DJ likes. Its guests see
 * the requests sent lately (the newest first) and that their own waits for the DJ — no place in a queue ({@link GuestQueue#inOrder()}).
 */
@Service
@RequiredArgsConstructor
public class GuestQueueService {

    /** How many of the waiting guest songs the page lists. */
    static final int UP_NEXT_SHOWN = 5;

    /**
     * A track counts as playing now for this long after it started. The server knows when a track started, not when it ended;
     * past a usual song's length the page says nothing rather than something that has probably ended.
     */
    static final Duration NOW_PLAYING_FOR = Duration.ofMinutes(8);

    private final DjService djService;
    private final PlayHistoryService playHistoryService;
    private final PartySettingsQueryService partySettingsQueryService;

    /**
     * @param nowPlaying the title of what plays now, or null when nothing started lately or the party is a requests-only one (or
     *                   the party's player is not the embedded one — the timeline knows only what that player played)
     * @param upNext     the next guest songs, in the order they will play
     * @param myIds      the guest's own requests (to mark them in the list)
     * @param myPosition where the guest's first waiting song is in the whole queue (1 = next), or null
     * @param mySong     the name of that song, or null
     * @param mostWanted the waiting songs more than one guest asked for, the most votes first (at most {@value #MOST_WANTED_SHOWN})
     * @param inOrder    whether the songs play in the queue's order (a YouTube party): then {@code upNext} is the next
     *                   songs and {@code myPosition} a place; otherwise (a requests-only party) {@code upNext} is the songs sent
     *                   lately, the newest first, and the guest's song only "waits for the DJ"
     */
    public record GuestQueue(String nowPlaying, List<SongRequestEntity> upNext, Set<Long> myIds, Integer myPosition,
                             String mySong, List<SongRequestEntity> mostWanted, boolean inOrder) {

        /** A queue without votes on it, in play order. */
        public GuestQueue(String nowPlaying, List<SongRequestEntity> upNext, Set<Long> myIds, Integer myPosition, String mySong) {
            this(nowPlaying, upNext, myIds, myPosition, mySong, List.of(), true);
        }
    }

    /** How many of the most wanted songs the page lists. */
    static final int MOST_WANTED_SHOWN = 3;

    public GuestQueue view(String partyCode, Set<Long> myIds) {
        List<SongRequestEntity> queue = djService.getDashboardQueue(partyCode); // oldest first = the order they play (cached 3 s)
        Integer myPosition = null;
        String mySong = null;
        for (int i = 0; i < queue.size(); i++) {
            if (myIds.contains(queue.get(i).getId())) {
                myPosition = i + 1;
                mySong = queue.get(i).getSongName();
                break;
            }
        }
        boolean inOrder = partySettingsQueryService.getSettings(partyCode).getActiveProvider() != MusicProviderType.REQUESTS_ONLY;
        List<SongRequestEntity> shown = inOrder
                ? queue.subList(0, Math.min(UP_NEXT_SHOWN, queue.size()))
                : queue.reversed().subList(0, Math.min(UP_NEXT_SHOWN, queue.size()));   // sent lately: the newest first
        // A requests-only party's DJ plays from their own software: nothing is known about what plays now. The timeline may still
        // hold the last track of the party's player from minutes ago, when it was a YouTube party (the same party, switched).
        String nowPlaying = inOrder ? nowPlaying(partyCode) : null;
        return new GuestQueue(nowPlaying, shown, myIds, myPosition, mySong, mostWanted(queue), inOrder);
    }

    /** The waiting songs more than one guest asked for, the most votes first, the longer waiting first among equals. */
    static List<SongRequestEntity> mostWanted(List<SongRequestEntity> queue) {
        return queue.stream()
                .filter(song -> song.getVotes() > 1)
                .sorted(java.util.Comparator.comparingInt(SongRequestEntity::getVotes).reversed())   // stable: queue order among equals
                .limit(MOST_WANTED_SHOWN)
                .toList();
    }

    private String nowPlaying(String partyCode) {
        List<HistoryEntry> last = playHistoryService.getRecentlyPlayed(partyCode, 1);
        if (last.isEmpty() || last.getFirst().at() == null
                || last.getFirst().at().isBefore(Instant.now().minus(NOW_PLAYING_FOR))) {
            return null;
        }
        return last.getFirst().title();
    }
}
