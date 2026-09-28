package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Account deletion is a Google API Services User Data Policy requirement — every table that holds
 * per-party data must be cleaned, including the server-side fallback tracks.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    private static final String OWNER = "owner-1";
    private static final String PARTY = "ABC12";

    @Mock
    private PartySettingsRepository partySettingsRepository;
    @Mock
    private SongRequestRepository songRequestRepository;
    @Mock
    private FeedbackRepository feedbackRepository;
    @Mock
    private FallbackTrackRepository fallbackTrackRepository;

    @InjectMocks
    private AccountDeletionService service;

    @Test
    @DisplayName("deletes song requests, fallback tracks, party settings and feedback — settings last")
    void shouldDeleteAllPartyData() {
        PartySettingsEntity party = PartySettingsEntity.builder().ownerId(OWNER).partyCode(PARTY).build();
        when(partySettingsRepository.findByOwnerId(OWNER)).thenReturn(Optional.of(party));

        service.deleteAllUserData(OWNER);

        InOrder order = inOrder(songRequestRepository, fallbackTrackRepository, partySettingsRepository, feedbackRepository);
        order.verify(songRequestRepository).deleteByPartyCode(PARTY);
        order.verify(fallbackTrackRepository).deleteByPartyCode(PARTY);
        order.verify(partySettingsRepository).delete(party);
        order.verify(feedbackRepository).deleteByOwnerId(OWNER);
    }

    @Test
    @DisplayName("an owner without a party only has their feedback deleted")
    void shouldOnlyDeleteFeedback_whenThereIsNoParty() {
        when(partySettingsRepository.findByOwnerId(OWNER)).thenReturn(Optional.empty());

        service.deleteAllUserData(OWNER);

        verify(feedbackRepository).deleteByOwnerId(OWNER);
        verifyNoInteractions(songRequestRepository, fallbackTrackRepository);
    }
}
