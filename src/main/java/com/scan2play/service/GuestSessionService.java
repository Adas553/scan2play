package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import jakarta.servlet.http.HttpSession;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The guest's own limit of song requests (counted in memory by session id) and the guest's requests remembered in the session.
 */
@Service
public class GuestSessionService {

    /**
     * The requests each guest has sent per party in the cooldown window, by session id and party code. In memory, not in the
     * session (review 2.3): with Spring Session JDBC every request reads its own copy of the session from the database and writes
     * it back when it ends — seconds later, after the AI's evaluation — so parallel requests of one guest could not see each
     * other's count there. A restart forgets the counts, as it did when the sessions were in memory; the server's own limits
     * ({@link GuestRequestLimiter}) hold anyway.
     */
    private final Cache<String, List<Instant>> requestTimes = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(24))
            .maximumSize(100_000)
            .build();

    /**
     * Counts one song request against the guest's limit, if it is under it: up to {@code requestLimit} requests, then a wait of
     * {@code cooldownMinutes} from the last of them, after which the whole limit is there again (what the dashboard's fields say:
     * "max requests per guest", "cooldown").
     * <p>
     * The check and the record are one atomic step ({@code compute} of the map), and the request is counted <b>before</b> the
     * evaluation (2–4 s of AI): otherwise parallel requests of one session would all pass the check while the first is still
     * being evaluated.
     *
     * @param session   the guest's HTTP session (only its id is used)
     * @param partyCode the unique code of the party
     * @param settings  the current settings of the party
     * @return empty if the request may go on (it has been counted), otherwise the wait time in seconds
     */
    public Optional<Long> tryAcquire(HttpSession session, String partyCode, PartySettingsEntity settings) {
        Instant now = Instant.now();
        Long[] wait = {null};
        requestTimes.asMap().compute(key(session.getId(), partyCode), (key, times) -> {
            List<Instant> kept = times == null ? new ArrayList<>() : new ArrayList<>(times);
            // The wait runs from the LAST request: once the cooldown has passed since it, the guest has the whole limit again
            if (!kept.isEmpty() && !kept.getLast().plus(settings.getCooldownMinutes(), ChronoUnit.MINUTES).isAfter(now)) {
                kept.clear();
            }
            if (!kept.isEmpty() && kept.size() >= settings.getRequestLimit()) {
                wait[0] = Math.max(0, ChronoUnit.SECONDS.between(now,
                        kept.getLast().plus(settings.getCooldownMinutes(), ChronoUnit.MINUTES)));
                return kept;
            }
            kept.add(now);
            return kept;
        });
        return Optional.ofNullable(wait[0]);
    }

    /**
     * Gives the newest counted request of this guest back: a request that came to nothing for the guest — nothing for the DJ to
     * play (a mood sent back to the form, a rejected song, the guest's own waiting song asked for again) — does not use the
     * guest's limit up.
     */
    public void giveBack(HttpSession session, String partyCode) {
        requestTimes.asMap().computeIfPresent(key(session.getId(), partyCode), (key, times) -> {
            List<Instant> kept = new ArrayList<>(times);
            if (!kept.isEmpty()) {
                kept.removeLast();
            }
            return kept;
        });
    }

    /** What {@link #tryAcquire} has counted for this session and party (tests). */
    List<Instant> countedRequests(String sessionId, String partyCode) {
        List<Instant> times = requestTimes.getIfPresent(key(sessionId, partyCode));
        return times == null ? List.of() : List.copyOf(times);
    }

    /** Earlier requests of a guest, as if counted then (tests). */
    void countEarlierRequests(String sessionId, String partyCode, List<Instant> times) {
        requestTimes.put(key(sessionId, partyCode), new ArrayList<>(times));
    }

    private static String key(String sessionId, String partyCode) {
        return sessionId + ':' + partyCode;
    }

    /** How many of a guest's own requests the session remembers per party (the newest ones). */
    static final int MY_REQUESTS_KEPT = 20;

    /** Remembers that this guest sent the song request {@code requestId}, so the party page can say where it waits. */
    public void rememberRequest(HttpSession session, String partyCode, Long requestId) {
        if (requestId == null) {
            return;
        }
        String key = "myRequests_" + partyCode;
        synchronized (WebUtils.getSessionMutex(session)) {
            @SuppressWarnings("unchecked")
            List<Long> ids = (List<Long>) session.getAttribute(key);
            List<Long> kept = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
            kept.add(requestId);
            if (kept.size() > MY_REQUESTS_KEPT) {
                kept = new ArrayList<>(kept.subList(kept.size() - MY_REQUESTS_KEPT, kept.size()));
            }
            session.setAttribute(key, kept);
        }
    }

    /** The ids of the song requests this guest sent to the party (as far as the session remembers). */
    public Set<Long> myRequestIds(HttpSession session, String partyCode) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) session.getAttribute("myRequests_" + partyCode);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }
}
