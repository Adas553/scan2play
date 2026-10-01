package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * What a guest sees of the music on the party page (the owner's decision, 2026-09-30): what plays now, the guest songs that come
 * next in the order they will play, and where the guest's own song waits. Before, the page listed the five newest accepted
 * requests, newest first — while they play oldest first, so a guest saw their song on top although it waited at the end.
 * <p>
 * The tracks of the background playlist are left out of "next": they play only when no guest song waits, and listing them would
 * make a guest think their song comes after them.
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

    /**
     * @param nowPlaying the title of what plays now, or null when nothing started lately (or the party's player is not the
     *                   embedded one — the timeline knows only what that player played)
     * @param upNext     the next guest songs, in the order they will play
     * @param myIds      the guest's own requests (to mark them in the list)
     * @param myPosition where the guest's first waiting song is in the whole queue (1 = next), or null
     * @param mySong     the name of that song, or null
     */
    public record GuestQueue(String nowPlaying, List<SongRequestEntity> upNext, Set<Long> myIds, Integer myPosition,
                             String mySong) {
    }

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
        return new GuestQueue(nowPlaying(partyCode), queue.subList(0, Math.min(UP_NEXT_SHOWN, queue.size())), myIds,
                myPosition, mySong);
    }

    private String nowPlaying(String partyCode) {
        List<HistoryEntry> last = playHistoryService.getRecentlyPlayed(partyCode, 1);
        if (last.isEmpty() || last.getFirst().at() == null
                || last.getFirst().at().isBefore(LocalDateTime.now().minus(NOW_PLAYING_FOR))) {
            return null;
        }
        return last.getFirst().title();
    }
}
