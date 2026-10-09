package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PushSubscriptionRepository;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Account deletion is a Google API Services User Data Policy requirement — every table that holds
 * per-party data must be cleaned.
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
    private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock
    private com.scan2play.repository.PartyStaffRepository partyStaffRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache cache;

    @InjectMocks
    private AccountDeletionService service;

    @Test
    @DisplayName("deletes song requests, party settings, feedback and the devices of the notifications — settings after the requests")
    void shouldDeleteAllPartyData() {
        PartySettingsEntity party = PartySettingsEntity.builder().ownerId(OWNER).partyCode(PARTY).build();
        when(partySettingsRepository.findByOwnerId(OWNER)).thenReturn(Optional.of(party));

        service.deleteAllUserData(OWNER);

        InOrder order = inOrder(songRequestRepository, partySettingsRepository, feedbackRepository);
        order.verify(songRequestRepository).deleteByPartyCode(PARTY);
        order.verify(partySettingsRepository).delete(party);
        order.verify(feedbackRepository).deleteByOwnerId(OWNER);
        verify(pushSubscriptionRepository).deleteByOwnerId(OWNER);
        verify(partyStaffRepository).deleteByMember(OWNER);   // wherever they worked (V30)
    }

    @Test
    @DisplayName("the deleted party is evicted from every cache that holds it under its code (the settings, the queue)")
    void shouldEvictThePartyFromTheCaches() {
        PartySettingsEntity party = PartySettingsEntity.builder().ownerId(OWNER).partyCode(PARTY).build();
        when(partySettingsRepository.findByOwnerId(OWNER)).thenReturn(Optional.of(party));
        AccountDeletionService.PARTY_CACHES.forEach(name -> when(cacheManager.getCache(name)).thenReturn(cache));

        service.deleteAllUserData(OWNER);

        verify(cache, times(AccountDeletionService.PARTY_CACHES.size())).evict(PARTY);
        assertThat(AccountDeletionService.PARTY_CACHES).contains("partySettings");
    }

    @Test
    @DisplayName("an owner without a party only has their feedback deleted")
    void shouldOnlyDeleteFeedback_whenThereIsNoParty() {
        when(partySettingsRepository.findByOwnerId(OWNER)).thenReturn(Optional.empty());

        service.deleteAllUserData(OWNER);

        verify(feedbackRepository).deleteByOwnerId(OWNER);
        verify(pushSubscriptionRepository).deleteByOwnerId(OWNER);
        verify(partyStaffRepository).deleteByMember(OWNER);   // wherever they worked (V30)
        verifyNoInteractions(songRequestRepository, cacheManager);
    }
}
