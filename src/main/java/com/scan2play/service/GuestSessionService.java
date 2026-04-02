package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service responsible for managing guest session state, specifically handling rate limiting for song requests.
 */
@Service
public class GuestSessionService {

    /**
     * Checks if the guest is rate-limited based on their session history and party settings.
     *
     * @param session   the user's HTTP session
     * @param partyCode the unique code of the party
     * @param settings  the current settings of the party
     * @return an Optional containing the wait time in seconds if rate-limited, empty otherwise
     */
    public Optional<Long> getRateLimitWaitTimeSeconds(HttpSession session, String partyCode, PartySettingsEntity settings) {
        String sessionKey = "requests_" + partyCode;
        @SuppressWarnings("unchecked")
        List<Instant> requestTimestamps = (List<Instant>) session.getAttribute(sessionKey);
        
        if (requestTimestamps == null || requestTimestamps.isEmpty()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant cutoffTime = now.minus(settings.getCooldownMinutes(), ChronoUnit.MINUTES);
        
        // Remove expired entries
        requestTimestamps.removeIf(t -> t.isBefore(cutoffTime));

        // After cleanup, if no active requests remain, guest is not rate-limited
        if (requestTimestamps.isEmpty()) {
            return Optional.empty();
        }

        // Check if the limit has been exceeded
        if (requestTimestamps.size() >= settings.getRequestLimit()) {
            Instant oldestRequest = requestTimestamps.getFirst();
            long secondsToWait = ChronoUnit.SECONDS.between(now, oldestRequest.plus(settings.getCooldownMinutes(), ChronoUnit.MINUTES));
            return Optional.of(Math.max(0, secondsToWait));
        }
        return Optional.empty();
    }

    /**
     * Records a successful song request timestamp in the user's session.
     *
     * @param session   the user's HTTP session
     * @param partyCode the unique code of the party
     */
    public void recordSuccessfulRequest(HttpSession session, String partyCode) {
        String sessionKey = "requests_" + partyCode;
        @SuppressWarnings("unchecked")
        List<Instant> requestTimestamps = (List<Instant>) session.getAttribute(sessionKey);
        if (requestTimestamps == null) {
            requestTimestamps = new ArrayList<>();
        }
        requestTimestamps.add(Instant.now());
        session.setAttribute(sessionKey, requestTimestamps);
    }
}
