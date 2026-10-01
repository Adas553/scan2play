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
     * Counts one song request against the guest's limit ({@code requestLimit} per {@code cooldownMinutes}), if it is under it.
     * <p>
     * The check and the record are one atomic step ({@code compute} of the map), and the request is counted <b>before</b> the
     * evaluation (2–4 s of AI): before, parallel requests of one session all passed the check while the first was still being
     * evaluated (review 4.1).
     *
     * @param session   the guest's HTTP session (only its id is used)
     * @param partyCode the unique code of the party
     * @param settings  the current settings of the party
     * @return empty if the request may go on (it has been counted), otherwise the wait time in seconds
     */
    public Optional<Long> tryAcquire(HttpSession session, String partyCode, PartySettingsEntity settings) {
        Instant now = Instant.now();
        Instant cutoffTime = now.minus(settings.getCooldownMinutes(), ChronoUnit.MINUTES);
        Long[] wait = {null};
        requestTimes.asMap().compute(key(session.getId(), partyCode), (key, times) -> {
            List<Instant> kept = times == null ? new ArrayList<>() : new ArrayList<>(times);
            kept.removeIf(t -> t.isBefore(cutoffTime));
            if (!kept.isEmpty() && kept.size() >= settings.getRequestLimit()) {
                Instant oldestRequest = kept.getFirst();
                wait[0] = Math.max(0, ChronoUnit.SECONDS.between(now,
                        oldestRequest.plus(settings.getCooldownMinutes(), ChronoUnit.MINUTES)));
                return kept;
            }
            kept.add(now);
            return kept;
        });
        return Optional.ofNullable(wait[0]);
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
