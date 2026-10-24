package com.scan2play.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiHealthMonitorTest {

    private final AiHealthMonitor monitor = new AiHealthMonitor();

    @Test
    void whileTheAiAnswersEveryRequest_nothingIsReported() {
        monitor.recordAnswered();
        monitor.recordAnswered();

        assertThat(monitor.takeSummary()).isNull();
    }

    @Test
    void aPeriodWithUncheckedRequests_saysHowManyOfAll_andTheNextPeriodStartsFromZero() {
        monitor.recordAnswered();
        monitor.recordUnchecked();
        monitor.recordUnchecked();

        assertThat(monitor.takeSummary()).startsWith("AI check: 2 of 3 guest requests in the last 5 min went to the DJ unchecked");
        assertThat(monitor.takeSummary()).as("counted once").isNull();

        monitor.recordUnchecked();
        assertThat(monitor.takeSummary()).startsWith("AI check: 1 of 1 ");
    }

    // ---- The alarm (the review of 2026-10-21, O2; the owner, 2026-10-24: a push to the owner and a bar in the panel, after 3) ----

    private Instant now = Instant.parse("2026-10-24T20:00:00Z");
    private final PushNotificationService push = mock(PushNotificationService.class);
    private final AiHealthMonitor alarmed = new AiHealthMonitor(push, "owner-sub", new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    });

    private void unchecked(int times) {
        for (int i = 0; i < times; i++) {
            alarmed.recordUnchecked();
        }
    }

    @Test
    void threeUncheckedInARow_meanTheAiIsDown_andTheOwnerIsToldOnce() {
        unchecked(2);
        assertThat(alarmed.isAiDown()).as("one or two: a timeout, not an outage").isFalse();
        verify(push, never()).notifyAiDown("owner-sub");

        unchecked(1);
        assertThat(alarmed.isAiDown()).isTrue();
        verify(push).notifyAiDown("owner-sub");

        unchecked(5);
        verify(push).notifyAiDown("owner-sub");   // still once: every request of an outage is unchecked
    }

    @Test
    void anAnswer_endsTheOutage_andBreaksTheRow() {
        unchecked(3);
        alarmed.recordAnswered();
        assertThat(alarmed.isAiDown()).isFalse();

        unchecked(2);
        alarmed.recordAnswered();
        unchecked(2);
        assertThat(alarmed.isAiDown()).as("no three in a row").isFalse();
    }

    @Test
    void theNextOutage_isToldAgain_onlyAfterSixHours() {
        unchecked(3);
        alarmed.recordAnswered();
        now = now.plus(Duration.ofHours(5));
        unchecked(3);
        verify(push, times(1)).notifyAiDown("owner-sub");

        alarmed.recordAnswered();
        now = now.plus(Duration.ofHours(2));
        unchecked(3);
        verify(push, times(2)).notifyAiDown("owner-sub");
    }

    @Test
    void anHourWithoutAnUncheckedRequest_takesTheBarAway() {
        unchecked(3);
        now = now.plus(Duration.ofMinutes(59));
        assertThat(alarmed.isAiDown()).isTrue();
        now = now.plus(Duration.ofMinutes(2));
        assertThat(alarmed.isAiDown()).as("no request since: nothing says it is still down").isFalse();
    }

    @Test
    void withoutTheOwnersAccount_theBarStillShows_andNoPushIsSent() {
        AiHealthMonitor noOwner = new AiHealthMonitor(push, "", Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            noOwner.recordUnchecked();
        }
        assertThat(noOwner.isAiDown()).isTrue();
        verify(push, never()).notifyAiDown(org.mockito.ArgumentMatchers.anyString());
    }
}
