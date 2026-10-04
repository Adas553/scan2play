package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * What a guest sees of the requests on the party page. The DJ plays the requests from their own software, in the order the DJ
 * likes, so there is no order to tell and nothing is known about what plays now: the guests see the requests sent lately (the
 * newest first), the ones more than one guest asked for, and that their own waits for the DJ.
 */
@Service
@RequiredArgsConstructor
public class GuestQueueService {

    /** How many of the waiting requests the page lists. */
    static final int RECENT_SHOWN = 5;

    /** How many of the most wanted songs the page lists. */
    static final int MOST_WANTED_SHOWN = 3;

    private final DjService djService;

    /**
     * @param recent     the requests sent lately, the newest first (at most {@value #RECENT_SHOWN})
     * @param myIds      the guest's own requests (to mark them in the list)
     * @param mySong     the name of the guest's first waiting song, or null
     * @param mostWanted the waiting songs more than one guest asked for, the most votes first (at most {@value #MOST_WANTED_SHOWN})
     */
    public record GuestQueue(List<SongRequestEntity> recent, Set<Long> myIds, String mySong, List<SongRequestEntity> mostWanted) {
    }

    public GuestQueue view(String partyCode, Set<Long> myIds) {
        List<SongRequestEntity> queue = djService.getDashboardQueue(partyCode); // oldest first (cached 3 s)
        String mySong = queue.stream()
                .filter(song -> myIds.contains(song.getId()))
                .map(SongRequestEntity::getSongName)
                .findFirst()
                .orElse(null);
        List<SongRequestEntity> recent = queue.reversed().subList(0, Math.min(RECENT_SHOWN, queue.size()));   // the newest first
        return new GuestQueue(recent, myIds, mySong, mostWanted(queue));
    }

    /** The waiting songs more than one guest asked for, the most votes first, the longer waiting first among equals. */
    static List<SongRequestEntity> mostWanted(List<SongRequestEntity> queue) {
        return queue.stream()
                .filter(song -> song.getVotes() > 1)
                .sorted(java.util.Comparator.comparingInt(SongRequestEntity::getVotes).reversed())   // stable: queue order among equals
                .limit(MOST_WANTED_SHOWN)
                .toList();
    }
}
