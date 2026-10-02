package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.SongNames;
import com.scan2play.util.YouTubeUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;

/**
 * Saves a guest's request — or, when the same song already waits in the queue, counts the guest's vote on it instead of a second
 * row: the DJ sees how many guests want a song ("×3"), and the queue does not fill with repeats.
 * <p>
 * <b>Concurrency:</b> the party's requests are saved under a transaction-scoped advisory lock, so two guests who ask for the same
 * song at the same moment make one row with two votes ({@code SongRequestVotesIT}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongRequestCommandService {

    /** What happened to a request: a new row, a vote on a waiting song, or nothing (the guest's own song already waits). */
    public enum Outcome { NEW, VOTE, ALREADY_YOURS }

    /**
     * @param request the row the request is in now: the new one, or the waiting song it was counted on (with its votes)
     */
    public record Saved(Outcome outcome, SongRequestEntity request) {
    }

    /** The upper 32 bits of every lock key of guest requests: "S2PR", apart from the fallback queue's "S2PQ". */
    private static final long REQUESTS_LOCK_SPACE = 0x5332_5052L << 32;

    private final SongRequestRepository songRequestRepository;

    /**
     * Saves {@code request}, unless it is accepted and the same song already waits in the party's queue: then the waiting song
     * gets one vote more (and keeps its name, its link and the words of the guest who asked first). A guest's own waiting song is
     * not counted again ({@link Outcome#ALREADY_YOURS}). A rejected request is always a row of its own — the history shows it.
     *
     * @param request     the request as the AI judged it, not saved yet
     * @param guestsOwnIds the guest's earlier requests at this party (their session), to tell their own song
     */
    @Transactional
    public Saved saveOrVote(SongRequestEntity request, Set<Long> guestsOwnIds) {
        if (!DECISION_ACCEPTED.equalsIgnoreCase(request.getDecision())) {
            return new Saved(Outcome.NEW, songRequestRepository.save(request));
        }
        songRequestRepository.lockRequests(lockKey(request.getPartyCode()));
        Optional<SongRequestEntity> waiting = sameSongWaiting(request);
        if (waiting.isEmpty()) {
            return new Saved(Outcome.NEW, songRequestRepository.save(request));
        }
        SongRequestEntity song = waiting.get();
        if (guestsOwnIds.contains(song.getId())) {
            return new Saved(Outcome.ALREADY_YOURS, song);
        }
        // The DJ may have played or skipped it since it was read (that does not take this lock): then the request is a new row
        if (songRequestRepository.addVote(song.getId()) == 0) {
            return new Saved(Outcome.NEW, songRequestRepository.save(request));
        }
        song.setVotes(song.getVotes() + 1);
        log.info("Party [{}]: one more vote for '{}' ({} now)", request.getPartyCode(), song.getSongName(), song.getVotes());
        return new Saved(Outcome.VOTE, song);
    }

    /** The waiting song of the party that is the same as the request: the same YouTube video, or else the same name. */
    private Optional<SongRequestEntity> sameSongWaiting(SongRequestEntity request) {
        List<SongRequestEntity> waiting = songRequestRepository.findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(
                request.getPartyCode(), List.of(DECISION_ACCEPTED));
        Optional<String> video = YouTubeUrls.extractVideoId(request.getTrackUrl());
        return waiting.stream()
                .filter(song -> (video.isPresent() && video.equals(YouTubeUrls.extractVideoId(song.getTrackUrl())))
                        || SongNames.same(song.getSongName(), request.getSongName()))
                .findFirst();
    }

    /** The advisory-lock key of a party's guest requests (a hash of the code: a rare collision only makes two parties take turns). */
    static long lockKey(String partyCode) {
        return REQUESTS_LOCK_SPACE | (Objects.hashCode(partyCode) & 0xFFFF_FFFFL);
    }
}
