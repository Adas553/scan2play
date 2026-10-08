package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.repository.SongRequestRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A guest's 👍 on a waiting song in the list of the party page (the owner, 2026-10-08): one vote per song, as many songs as the guest
 * likes, taken back with a second tap. The guest's own request is already their vote (no 👍 on it). The votes go to the same
 * {@code votes} as a second request for the song — the DJ's "Głosy" column and the guests' "🔥 Najwięcej głosów".
 * <p>
 * Which songs a guest gave their 👍 is kept twice: in memory, by session id — the check and the vote are one atomic step there, so
 * two quick taps (or two tabs) count once — and in the session, so that it outlives a deploy (the sessions are in the database;
 * after a restart the memory starts from the session). Like the guest's own requests, a guest who drops the cookie is a new guest:
 * a vote is a hint for the DJ, not an election; {@link GuestRequestLimiter#tryAcquireVote} bounds what one network can do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestVoteService {

    /** How many of a guest's 👍 the session remembers per party (the newest ones). */
    static final int MY_VOTES_KEPT = 100;

    /** The session attribute of the guest's 👍 at a party, followed by the party code. */
    private static final String MY_VOTES_PREFIX = "myVotes_";

    public enum Result {
        /** The 👍 counts (now, or it did already). */
        COUNTED,
        /** The 👍 was taken back (or there was none). */
        TAKEN_BACK,
        /** The song no longer waits — the DJ played or skipped it: nothing changed. */
        GONE
    }

    private final SongRequestRepository songRequestRepository;
    private final DjService djService;

    /** The guest's 👍 per party, by session id and party code: the request ids. */
    private final Cache<String, Set<Long>> votes = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(24))
            .maximumSize(100_000)
            .build();

    /** The waiting songs this guest gave their 👍 at the party (as far as the server remembers). */
    public Set<Long> myVotes(HttpSession session, String partyCode) {
        Set<Long> kept = votes.getIfPresent(key(session.getId(), partyCode));
        return kept != null ? Set.copyOf(kept) : Set.copyOf(sessionVotes(session, partyCode));
    }

    /**
     * The guest's 👍 on the waiting song {@code requestId} of the party. A second 👍 on the same song changes nothing. The caller
     * leaves out the guest's own requests (they are their vote already).
     */
    public Result vote(HttpSession session, String partyCode, long requestId) {
        Result[] result = {Result.COUNTED};
        boolean[] added = {false};
        Set<Long> after = votes.asMap().compute(key(session.getId(), partyCode), (key, kept) -> {
            Set<Long> ids = new LinkedHashSet<>(kept != null ? kept : sessionVotes(session, partyCode));
            if (ids.contains(requestId)) {
                return ids;   // counted already
            }
            // in the atomic step: a second tap of the same guest waits for this one, and then finds the vote counted
            if (songRequestRepository.addGuestVote(requestId, partyCode) == 0) {
                result[0] = Result.GONE;
                return ids;
            }
            ids.add(requestId);
            added[0] = true;
            return trim(ids);
        });
        if (added[0]) {
            remember(session, partyCode, after);
            djService.refreshQueue(partyCode);
            log.info("Party [{}]: a guest's 👍 on request {}", partyCode, requestId);
        }
        return result[0];
    }

    /** The guest takes their 👍 on {@code requestId} back. Nothing to take back (no 👍 of theirs) changes nothing. */
    public Result takeBack(HttpSession session, String partyCode, long requestId) {
        Result[] result = {Result.TAKEN_BACK};
        boolean[] removed = {false};
        Set<Long> after = votes.asMap().compute(key(session.getId(), partyCode), (key, kept) -> {
            Set<Long> ids = new LinkedHashSet<>(kept != null ? kept : sessionVotes(session, partyCode));
            if (!ids.remove(requestId)) {
                return ids;
            }
            removed[0] = true;
            if (songRequestRepository.removeGuestVote(requestId, partyCode) == 0) {
                result[0] = Result.GONE;   // played or skipped meanwhile: the 👍 is forgotten with it
            }
            return ids;
        });
        if (removed[0]) {
            remember(session, partyCode, after);
            if (result[0] == Result.TAKEN_BACK) {
                djService.refreshQueue(partyCode);
            }
        }
        return result[0];
    }

    /** The newest {@link #MY_VOTES_KEPT}: an old 👍 forgotten can only be given again. */
    private static Set<Long> trim(Set<Long> ids) {
        if (ids.size() <= MY_VOTES_KEPT) {
            return ids;
        }
        List<Long> list = new ArrayList<>(ids);
        return new LinkedHashSet<>(list.subList(list.size() - MY_VOTES_KEPT, list.size()));
    }

    /**
     * In the session too, so that it outlives a deploy. No lock there: with Spring Session JDBC each request writes its own copy back;
     * the memory above decides, the session only seeds it after a restart.
     */
    private static void remember(HttpSession session, String partyCode, Set<Long> ids) {
        session.setAttribute(MY_VOTES_PREFIX + partyCode, new ArrayList<>(ids));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> sessionVotes(HttpSession session, String partyCode) {
        List<Long> ids = (List<Long>) session.getAttribute(MY_VOTES_PREFIX + partyCode);
        return ids == null ? List.of() : ids;
    }

    private static String key(String sessionId, String partyCode) {
        return sessionId + ':' + partyCode;
    }
}
