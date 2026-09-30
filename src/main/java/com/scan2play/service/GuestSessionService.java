package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

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
     * Counts one song request against the guest's limit ({@code requestLimit} per {@code cooldownMinutes}), if it is under it.
     * <p>
     * The check and the record are one step under the session's mutex, and the request is counted <b>before</b> the evaluation
     * (2–4 s of AI): before, parallel requests of one session all passed the check while the first was still being evaluated.
     *
     * @param session   the user's HTTP session
     * @param partyCode the unique code of the party
     * @param settings  the current settings of the party
     * @return empty if the request may go on (it has been counted), otherwise the wait time in seconds
     */
    public Optional<Long> tryAcquire(HttpSession session, String partyCode, PartySettingsEntity settings) {
        String sessionKey = "requests_" + partyCode;
        synchronized (WebUtils.getSessionMutex(session)) {
            @SuppressWarnings("unchecked")
            List<Instant> requestTimestamps = (List<Instant>) session.getAttribute(sessionKey);
            if (requestTimestamps == null) {
                requestTimestamps = new ArrayList<>();
            }

            Instant now = Instant.now();
            Instant cutoffTime = now.minus(settings.getCooldownMinutes(), ChronoUnit.MINUTES);
            requestTimestamps.removeIf(t -> t.isBefore(cutoffTime));

            if (!requestTimestamps.isEmpty() && requestTimestamps.size() >= settings.getRequestLimit()) {
                Instant oldestRequest = requestTimestamps.getFirst();
                long secondsToWait = ChronoUnit.SECONDS.between(now, oldestRequest.plus(settings.getCooldownMinutes(), ChronoUnit.MINUTES));
                return Optional.of(Math.max(0, secondsToWait));
            }

            requestTimestamps.add(now);
            session.setAttribute(sessionKey, requestTimestamps);
            return Optional.empty();
        }
    }
}
