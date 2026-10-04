package com.scan2play.service;

import com.scan2play.entity.FallbackPlayEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.model.HistoryFilter;
import com.scan2play.repository.FallbackPlayRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.YouTubeUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * One timeline of what played at a party (Phase 4, stage 2): the guests' songs that played (or were rejected) and the
 * tracks the player took from the background playlist, newest event first. They live in different tables
 * ({@code song_requests}, {@code fallback_play}), so each side is read with its own bounded query and the two are
 * merged here. The timeline serves the DJ's history page and the "previous track" button.
 * <p>
 * The background side is the <em>play log</em> ({@code fallback_play}), not the queue ({@code fallback_track}): the
 * queue forgets what has played when the playlist starts a new round, the log does not, so a short playlist that loops
 * during a party keeps its whole history (until the 30-day retention of YouTube API data deletes it). Every play is its
 * own entry with its own id — the same video in two rounds is two entries.
 * <p>
 * A background track counts as played when the player <em>takes</em> it (that is when the log row is written), so a
 * track that was handed out but never sounded — the answer arrived after the DJ had picked something by hand, or the
 * video could not be played — is in the history too.
 */
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    /** Newest event first; ties (the same millisecond) by id, then by source, so the order is always the same. */
    private static final Comparator<HistoryEntry> NEWEST_FIRST = Comparator
            .comparing(HistoryEntry::at, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(HistoryEntry::id, Comparator.<Long>reverseOrder())
            .thenComparing(HistoryEntry::source);

    /**
     * A page of the history: the most recent entries, and whether older ones exist.
     *
     * @param entries at most the requested number of entries, newest first
     * @param hasMore {@code true} when there are older ones than the last of {@code entries}
     */
    public record Page(List<HistoryEntry> entries, boolean hasMore) {
    }

    private final SongRequestRepository songRequestRepository;
    private final FallbackPlayRepository fallbackPlayRepository;

    /**
     * The last {@code limit} things that played or were rejected. The caller decides how far back the DJ may look
     * (see {@code DjDashboardController}); one entry more than asked for is read, to tell whether there is anything
     * older. Every query is bounded by that number.
     *
     * @param limit how many entries to return at most; at least 1
     */
    @Transactional(readOnly = true)
    public Page getHistory(String partyCode, int limit) {
        return getHistory(partyCode, limit, HistoryFilter.ALL);
    }

    /**
     * The last {@code limit} entries of the kind {@code filter} says. The filter is applied in the queries, not on the
     * page: only the tables the filter needs are read (guests' requests, the background playlist, or both), each with
     * its own bound, so the limit counts the entries of that kind however many others lie between them.
     */
    @Transactional(readOnly = true)
    public Page getHistory(String partyCode, int limit, HistoryFilter filter) {
        List<String> decisions = new ArrayList<>(2);
        if (filter.includesGuestsPlayed()) {
            decisions.add(DECISION_PLAYED);
        }
        if (filter.includesGuestsRejected()) {
            decisions.add(DECISION_REJECTED);
        }
        List<HistoryEntry> timeline = timeline(partyCode, decisions, filter.includesBackgroundTracks(), limit + 1);
        boolean hasMore = timeline.size() > limit;
        return new Page(hasMore ? timeline.subList(0, limit) : timeline, hasMore);
    }

    /**
     * The tracks that played most recently and that the embedded player can play again (they have a YouTube video ID),
     * newest first — what the "previous track" button walks back along. Requests whose link is a YouTube search
     * page are left out.
     *
     * @param limit how many entries to read from each side (fewer may come back once the unplayable ones are dropped)
     */
    @Transactional(readOnly = true)
    public List<HistoryEntry> getRecentlyPlayed(String partyCode, int limit) {
        return timeline(partyCode, List.of(DECISION_PLAYED), true, limit).stream()
                .filter(entry -> entry.videoId() != null)
                .toList();
    }

    /**
     * @param decisions         the decisions of the guests' requests to read; none: that table is not read at all
     * @param backgroundTracks  whether to read the tracks of the background playlist
     */
    private List<HistoryEntry> timeline(String partyCode, List<String> decisions, boolean backgroundTracks, int n) {
        PageRequest firstN = PageRequest.of(0, n);
        List<HistoryEntry> entries = new ArrayList<>();
        if (!decisions.isEmpty()) {
            songRequestRepository.findHistory(partyCode, decisions, firstN).forEach(song -> entries.add(toEntry(song)));
        }
        if (backgroundTracks) {
            fallbackPlayRepository.findRecent(partyCode, firstN).forEach(play -> entries.add(toEntry(play)));
        }
        // The n newest of the union are among the n newest of each side, so n rows from each are enough.
        entries.sort(NEWEST_FIRST);
        return entries.size() > n ? new ArrayList<>(entries.subList(0, n)) : entries;
    }

    private static HistoryEntry toEntry(SongRequestEntity song) {
        Instant at = song.getPlayedAt() != null ? song.getPlayedAt() : song.getRequestedAt();
        return new HistoryEntry(Source.GUEST, song.getId(), at, song.getSongName(), song.getTrackUrl(),
                YouTubeUrls.extractVideoId(song.getTrackUrl()).orElse(null),
                song.getStyle(), song.getDecision(), song.getDjComment(), song.getEnergyLevel(), song.getGuestText(),
                song.getVotes());
    }

    private static HistoryEntry toEntry(FallbackPlayEntity play) {
        String title = play.getTitle() != null && !play.getTitle().isBlank()
                ? play.getTitle() : "youtu.be/" + play.getVideoId();
        return new HistoryEntry(Source.BACKGROUND, play.getId(), play.getPlayedAt(), title,
                "https://www.youtube.com/watch?v=" + play.getVideoId(), play.getVideoId(),
                null, DECISION_PLAYED, null, null);
    }
}
