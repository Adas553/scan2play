package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.repository.FallbackTrackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackTrackCommandServiceTest {

    private static final String PARTY = "ABC12";

    @Mock
    private FallbackTrackRepository repository;

    @InjectMocks
    private FallbackTrackCommandService service;

    @Test
    @DisplayName("replaceTracks cancels the still-queued tracks first, then inserts the new ones as QUEUED")
    void replaceTracks_shouldSoftInvalidateThenInsert() {
        int inserted = service.replaceTracks(PARTY, "PLx", List.of("a", "b", "c"));

        assertThat(inserted).isEqualTo(3);

        InOrder order = inOrder(repository);
        order.verify(repository).updateStatus(PARTY, FallbackTrackStatus.QUEUED, FallbackTrackStatus.CANCELLED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FallbackTrackEntity>> captor = ArgumentCaptor.forClass(List.class);
        order.verify(repository).saveAll(captor.capture());

        List<FallbackTrackEntity> saved = captor.getValue();
        assertThat(saved).extracting(FallbackTrackEntity::getVideoId).containsExactly("a", "b", "c");
        assertThat(saved).extracting(FallbackTrackEntity::getPlaylistPosition).containsExactly(0, 1, 2);
        assertThat(saved).allSatisfy(t -> {
            assertThat(t.getPartyCode()).isEqualTo(PARTY);
            assertThat(t.getPlaylistId()).isEqualTo("PLx");
            assertThat(t.getStatus()).isEqualTo(FallbackTrackStatus.QUEUED);
            assertThat(t.getPlayedAt()).isNull();
        });
        // one import = one fetch timestamp (basis of the 30-day retention)
        assertThat(saved).extracting(FallbackTrackEntity::getFetchedAt).doesNotContainNull().containsOnly(saved.get(0).getFetchedAt());
    }

    @Test
    @DisplayName("replaceTracks never deletes rows — PLAYED history is kept")
    void replaceTracks_shouldNotDeleteAnything() {
        service.replaceTracks(PARTY, "PLx", List.of("a"));

        verify(repository, never()).deleteByPartyCode(any());
        verify(repository, never()).deleteFetchedBefore(any());
    }

    @Test
    @DisplayName("cancelQueuedTracks flips QUEUED to CANCELLED and inserts nothing")
    void cancelQueuedTracks_shouldOnlyCancel() {
        service.cancelQueuedTracks(PARTY);

        verify(repository).updateStatus(PARTY, FallbackTrackStatus.QUEUED, FallbackTrackStatus.CANCELLED);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("purgeStaleTracks deletes tracks fetched more than 30 days ago")
    void purgeStaleTracks_shouldUseThirtyDayCutoff() {
        when(repository.deleteFetchedBefore(any())).thenReturn(4);
        LocalDateTime before = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);

        service.purgeStaleTracks();

        LocalDateTime after = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteFetchedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, after);
        assertThat(FallbackTrackEntity.MAX_AGE_DAYS).isEqualTo(30);
    }
}
