package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GuestSessionService} rate limiting logic.
 * Rate limiting is a core mechanism that protects the platform from spam.
 */
class GuestSessionServiceTest {

    private GuestSessionService service;
    private HttpSession session;
    private PartySettingsEntity settings;

    private static final String PARTY_CODE = "ABC12";
    private static final String SESSION_KEY = "requests_" + PARTY_CODE;

    @BeforeEach
    void setUp() {
        service = new GuestSessionService();
        session = mock(HttpSession.class);
        settings = PartySettingsEntity.builder()
                .partyCode(PARTY_CODE)
                .requestLimit(2)
                .cooldownMinutes(3)
                .build();
    }

    // --- getRateLimitWaitTimeSeconds ---

    @Test
    void getRateLimitWaitTime_shouldReturnEmpty_whenNoRequestsInSession() {
        when(session.getAttribute(SESSION_KEY)).thenReturn(null);

        Optional<Long> result = service.getRateLimitWaitTimeSeconds(session, PARTY_CODE, settings);

        assertThat(result).isEmpty();
    }

    @Test
    void getRateLimitWaitTime_shouldReturnEmpty_whenEmptyRequestList() {
        when(session.getAttribute(SESSION_KEY)).thenReturn(new ArrayList<>());

        Optional<Long> result = service.getRateLimitWaitTimeSeconds(session, PARTY_CODE, settings);

        assertThat(result).isEmpty();
    }

    @Test
    void getRateLimitWaitTime_shouldReturnEmpty_whenUnderLimit() {
        List<Instant> timestamps = new ArrayList<>();
        timestamps.add(Instant.now().minus(1, ChronoUnit.MINUTES)); // 1 request within window

        when(session.getAttribute(SESSION_KEY)).thenReturn(timestamps);

        Optional<Long> result = service.getRateLimitWaitTimeSeconds(session, PARTY_CODE, settings);

        assertThat(result).isEmpty();
    }

    @Test
    void getRateLimitWaitTime_shouldReturnWaitTime_whenLimitExceeded() {
        List<Instant> timestamps = new ArrayList<>();
        timestamps.add(Instant.now().minus(1, ChronoUnit.MINUTES));
        timestamps.add(Instant.now().minus(30, ChronoUnit.SECONDS));

        when(session.getAttribute(SESSION_KEY)).thenReturn(timestamps);

        Optional<Long> result = service.getRateLimitWaitTimeSeconds(session, PARTY_CODE, settings);

        assertThat(result).isPresent();
        assertThat(result.get()).isPositive();
    }

    @Test
    void getRateLimitWaitTime_shouldReturnEmpty_whenAllRequestsExpired() {
        List<Instant> timestamps = new ArrayList<>();
        timestamps.add(Instant.now().minus(10, ChronoUnit.MINUTES)); // older than 3-min window
        timestamps.add(Instant.now().minus(5, ChronoUnit.MINUTES));

        when(session.getAttribute(SESSION_KEY)).thenReturn(timestamps);

        Optional<Long> result = service.getRateLimitWaitTimeSeconds(session, PARTY_CODE, settings);

        assertThat(result).isEmpty();
    }

    // --- recordSuccessfulRequest ---

    @Test
    void recordSuccessfulRequest_shouldCreateNewListIfNone() {
        when(session.getAttribute(SESSION_KEY)).thenReturn(null);

        service.recordSuccessfulRequest(session, PARTY_CODE);

        verify(session).setAttribute(eq(SESSION_KEY), argThat(arg -> {
            @SuppressWarnings("unchecked")
            List<Instant> list = (List<Instant>) arg;
            return list.size() == 1;
        }));
    }

    @Test
    void recordSuccessfulRequest_shouldAppendToExistingList() {
        List<Instant> existing = new ArrayList<>();
        existing.add(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(session.getAttribute(SESSION_KEY)).thenReturn(existing);

        service.recordSuccessfulRequest(session, PARTY_CODE);

        assertThat(existing).hasSize(2);
    }
}

