package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.YouTubeUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * One timeline of what played at a party (Phase 4, stage 2): the guests' songs that played (or were rejected) and the
 * tracks the player took from the background playlist, newest event first. They live in different tables
 * ({@code song_requests}, {@code fallback_track}), so each side is read with its own bounded query and the two are
 * merged here. The timeline serves the DJ's history page and the "previous track" button.
 * <p>
 * A background track counts as played when the player <em>takes</em> it (that is when {@code played_at} is set), so a
 * track that was handed out but never sounded — the answer arrived after the DJ had picked something by hand, or the
 * video could not be played — is in the history too.
 */
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    /** Newest event first; ties (the same millisecond) by id, then by source, so the order is always the same. */
    private static final Comparator<HistoryEntry> NEWEST_FIRST = Comparator
            .comparing(HistoryEntry::at, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
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
    private final FallbackTrackRepository fallbackTrackRepository;

    /**
     * The last {@code limit} things that played or were rejected. The caller decides how far back the DJ may look
     * (see {@code DjDashboardController}); one entry more than asked for is read, to tell whether there is anything
     * older. Every query is bounded by that number.
     *
     * @param limit how many entries to return at most; at least 1
     */
    @Transactional(readOnly = true)
    public Page getHistory(String partyCode, int limit) {
        List<HistoryEntry> timeline = timeline(partyCode, List.of(DECISION_PLAYED, DECISION_REJECTED), limit + 1);
        boolean hasMore = timeline.size() > limit;
        return new Page(hasMore ? timeline.subList(0, limit) : timeline, hasMore);
    }

    /**
     * The tracks that played most recently and that the embedded player can play again (they have a YouTube video ID),
     * newest first — what the "previous track" button walks back along. Songs of Spotify parties and requests whose
     * link is a YouTube search page are left out.
     *
     * @param limit how many entries to read from each side (fewer may come back once the unplayable ones are dropped)
     */
    @Transactional(readOnly = true)
    public List<HistoryEntry> getRecentlyPlayed(String partyCode, int limit) {
        return timeline(partyCode, List.of(DECISION_PLAYED), limit).stream()
                .filter(entry -> entry.videoId() != null)
                .toList();
    }

    private List<HistoryEntry> timeline(String partyCode, List<String> decisions, int n) {
        PageRequest firstN = PageRequest.of(0, n);
        List<HistoryEntry> entries = new ArrayList<>();
        songRequestRepository.findHistory(partyCode, decisions, firstN).forEach(song -> entries.add(toEntry(song)));
        if (decisions.contains(DECISION_PLAYED)) {
            fallbackTrackRepository.findPlayedTracks(partyCode, FallbackTrackStatus.PLAYED, firstN)
                    .forEach(track -> entries.add(toEntry(track)));
        }
        // The n newest of the union are among the n newest of each side, so n rows from each are enough.
        entries.sort(NEWEST_FIRST);
        return entries.size() > n ? new ArrayList<>(entries.subList(0, n)) : entries;
    }

    private static HistoryEntry toEntry(SongRequestEntity song) {
        LocalDateTime at = song.getPlayedAt() != null ? song.getPlayedAt() : song.getRequestedAt();
        return new HistoryEntry(Source.GUEST, song.getId(), at, song.getSongName(), song.getTrackUrl(),
                YouTubeUrls.extractVideoId(song.getTrackUrl()).orElse(null),
                song.getStyle(), song.getDecision(), song.getDjComment(), song.getEnergyLevel());
    }

    private static HistoryEntry toEntry(FallbackTrackEntity track) {
        String title = track.getTitle() != null && !track.getTitle().isBlank()
                ? track.getTitle() : "youtu.be/" + track.getVideoId();
        return new HistoryEntry(Source.BACKGROUND, track.getId(), track.getPlayedAt(), title,
                "https://www.youtube.com/watch?v=" + track.getVideoId(), track.getVideoId(),
                null, DECISION_PLAYED, null, null);
    }
}
