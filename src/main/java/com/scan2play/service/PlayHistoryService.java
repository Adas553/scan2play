package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryFilter;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * The DJ's history of a party: the guests' songs that played or were rejected, newest event first — one bounded query of
 * {@code song_requests}.
 */
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    /**
     * A page of the history: the most recent entries, and whether older ones exist.
     *
     * @param entries at most the requested number of entries, newest first
     * @param hasMore {@code true} when there are older ones than the last of {@code entries}
     */
    public record Page(List<HistoryEntry> entries, boolean hasMore) {
    }

    private final SongRequestRepository songRequestRepository;

    /**
     * The last {@code limit} things that played or were rejected. The caller decides how far back the DJ may look
     * (see {@code DjDashboardController}); one entry more than asked for is read, to tell whether there is anything
     * older.
     *
     * @param limit how many entries to return at most; at least 1
     */
    @Transactional(readOnly = true)
    public Page getHistory(String partyCode, int limit) {
        return getHistory(partyCode, limit, HistoryFilter.ALL);
    }

    /**
     * The last {@code limit} entries of the kind {@code filter} says. The filter is applied in the query, not on the page, so
     * the limit counts the entries of that kind however many others lie between them.
     */
    @Transactional(readOnly = true)
    public Page getHistory(String partyCode, int limit, HistoryFilter filter) {
        List<String> decisions = new ArrayList<>(2);
        if (filter.includesPlayed()) {
            decisions.add(DECISION_PLAYED);
        }
        if (filter.includesRejected()) {
            decisions.add(DECISION_REJECTED);
        }
        List<HistoryEntry> entries = songRequestRepository.findHistory(partyCode, decisions, PageRequest.of(0, limit + 1)).stream()
                .map(PlayHistoryService::toEntry)
                .toList();
        boolean hasMore = entries.size() > limit;
        return new Page(hasMore ? entries.subList(0, limit) : entries, hasMore);
    }

    private static HistoryEntry toEntry(SongRequestEntity song) {
        Instant at = song.getPlayedAt() != null ? song.getPlayedAt() : song.getRequestedAt();
        return new HistoryEntry(song.getId(), at, song.getSongName(), song.getTrackUrl(), song.getStyle(), song.getDecision(),
                song.getDjComment(), song.getEnergyLevel(), song.getGuestText(), song.getVotes(), song.getSkippedAt());
    }
}
