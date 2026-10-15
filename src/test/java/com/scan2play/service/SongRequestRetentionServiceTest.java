package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.scan2play.service.SongRequestRetentionService.BATCH_SIZE;
import static com.scan2play.service.SongRequestRetentionService.MAX_BATCHES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SongRequestRetentionServiceTest {

    @Mock
    private SongRequestRepository repository;

    @InjectMocks
    private SongRequestRetentionService service;

    private static final Instant CUTOFF = java.time.LocalDateTime.of(2026, 8, 30, 4, 45).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant();

    @Test
    @DisplayName("requests are kept 30 days from requested_at (the privacy pages say the same)")
    void shouldKeepRequestsThirtyDays() {
        assertThat(SongRequestEntity.MAX_AGE_DAYS).isEqualTo(30);
    }

    @Test
    @DisplayName("the nightly run deletes what was requested more than 30 days ago — the cutoff is 30 days before now")
    void shouldPurgeWithACutoffThirtyDaysBack() {
        when(repository.deleteRequestedBefore(any(), eq(BATCH_SIZE))).thenReturn(0);
        Instant before = Instant.now();

        service.purgeStaleRequests();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteRequestedBefore(cutoff.capture(), eq(BATCH_SIZE));
        assertThat(Duration.between(cutoff.getValue(), before.minus(30, ChronoUnit.DAYS)).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("when nothing is old enough one statement is run, and nothing else")
    void shouldStopAtOnce_whenNothingIsOld() {
        when(repository.deleteRequestedBefore(CUTOFF, BATCH_SIZE)).thenReturn(0);

        assertThat(service.purgeRequestedBefore(CUTOFF)).isZero();

        verify(repository, times(1)).deleteRequestedBefore(CUTOFF, BATCH_SIZE);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("a backlog is deleted in batches, until one comes back short — every batch is a statement of its own")
    void shouldDeleteInBatchesUntilABatchIsShort() {
        when(repository.deleteRequestedBefore(CUTOFF, BATCH_SIZE)).thenReturn(BATCH_SIZE, BATCH_SIZE, 250);

        assertThat(service.purgeRequestedBefore(CUTOFF)).isEqualTo(2 * BATCH_SIZE + 250);

        verify(repository, times(3)).deleteRequestedBefore(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("exactly full last batch: one more statement finds nothing and ends the run")
    void shouldAskOnceMore_afterAFullBatch() {
        when(repository.deleteRequestedBefore(CUTOFF, BATCH_SIZE)).thenReturn(BATCH_SIZE, 0);

        assertThat(service.purgeRequestedBefore(CUTOFF)).isEqualTo(BATCH_SIZE);

        verify(repository, times(2)).deleteRequestedBefore(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("a run is bounded: at most MAX_BATCHES batches a night, the rest waits for the next night")
    void shouldStopAfterTheMaximumNumberOfBatches() {
        when(repository.deleteRequestedBefore(CUTOFF, BATCH_SIZE)).thenReturn(BATCH_SIZE);   // there is always more

        assertThat(service.purgeRequestedBefore(CUTOFF)).isEqualTo(MAX_BATCHES * BATCH_SIZE);

        verify(repository, times(MAX_BATCHES)).deleteRequestedBefore(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("the batches are small enough to be short transactions, and the bound is a couple of hundred thousand rows a night")
    void shouldHaveSensibleLimits() {
        assertThat(BATCH_SIZE).isBetween(100, 5000);
        assertThat((long) BATCH_SIZE * MAX_BATCHES).isBetween(50_000L, 1_000_000L);
    }

    /**
     * The review of 2026-10-14, W10: the queue is the party's 300 oldest waiting requests, and a DJ keeps one party code for every
     * event — without "Wyczyść kolejkę" last Saturday's requests hid tonight's. A request waiting more than a day is no longer the
     * evening's: the night clears it from the queue, as "Wyczyść kolejkę" would (it stays in the history, cleared).
     */
    @Test
    @DisplayName("the nightly run clears from the queue what has waited more than 24 hours")
    void shouldClearRequestsWaitingMoreThanADay() {
        Instant before = Instant.now();

        service.clearOldWaitingRequests();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> clearedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).clearWaitingRequestedBefore(cutoff.capture(), clearedAt.capture());
        assertThat(Duration.between(cutoff.getValue(), before.minus(24, ChronoUnit.HOURS)).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(Duration.between(clearedAt.getValue(), before).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("the clearing is a scheduled job of its own — every day at 04:40, before the purge")
    void shouldClearTheOldWaitingEveryNight() throws Exception {
        Scheduled scheduled = SongRequestRetentionService.class.getMethod("clearOldWaitingRequests").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 40 4 * * *");
        assertThat(scheduled.zone()).as("Polish time, not the server's UTC").isEqualTo("Europe/Warsaw");
    }

    @Test
    @DisplayName("it is a scheduled job — every day at 04:45")
    void shouldBeScheduledEveryNight() throws Exception {
        Scheduled scheduled = SongRequestRetentionService.class.getMethod("purgeStaleRequests").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 45 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Europe/Warsaw");
    }
}
