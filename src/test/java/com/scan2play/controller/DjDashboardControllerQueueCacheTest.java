package com.scan2play.controller;

import com.scan2play.config.AppConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.Plans;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.SongRequestCommandService;
import com.scan2play.service.StaffInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * The DJ's queue poll with the real 3 s cache of the waiting requests ({@code dashboardQueue}, {@link AppConfig}) — review
 * 2026-10-14 K1: the guest page fills that cache too, and a guest's request committed within its 3 s must not be answered with
 * the new version's ETag over the old list. That froze the DJ's queue: every later poll matched the ETag and got a 304, and the
 * request stayed invisible until something else changed the queue.
 */
@SpringJUnitConfig(DjDashboardControllerQueueCacheTest.Config.class)
class DjDashboardControllerQueueCacheTest {

    private static final String PARTY = "ABC12";

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new AppConfig().cacheManager();
        }

        @Bean
        SongRequestRepository songRequestRepository() {
            return mock(SongRequestRepository.class);
        }

        @Bean
        DjService djService(SongRequestRepository songRequestRepository, CacheManager cacheManager) {
            return new DjService(songRequestRepository, mock(com.scan2play.repository.SongRequestDedicationRepository.class), cacheManager,
                mock(com.scan2play.service.DjLibraryService.class));
        }

        @Bean
        SongRequestCommandService songRequestCommandService(SongRequestRepository songRequestRepository, DjService djService) {
            PartySettingsRepository parties = mock(PartySettingsRepository.class);
            when(parties.existsByPartyCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            return new SongRequestCommandService(songRequestRepository, mock(com.scan2play.repository.SongRequestDedicationRepository.class), parties, djService);
        }
    }

    @Autowired
    private SongRequestRepository songRequestRepository;
    @Autowired
    private DjService djService;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private SongRequestCommandService commands;

    private DjDashboardController controller;

    @BeforeEach
    void setUp() {
        reset(songRequestRepository);
        cacheManager.getCache(DjService.QUEUE_CACHE).clear();
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).active(true).build();
        when(sessionHelper.access(eq(PARTY), any(), any())).thenReturn(new PartyStaffService.Access(party, true, StaffPermission.all()));
        controller = new DjDashboardController(djService, mock(PartySettingsCommandService.class), mock(QrCodeService.class),
                sessionHelper, mock(PlayHistoryService.class), mock(GuestRequestLimiter.class), mock(PushNotificationService.class),
                mock(PartyStaffService.class), mock(StaffInvitationService.class), Plans.off(), new com.scan2play.service.AiHealthMonitor(), com.scan2play.service.PartySchedules.texts());
    }

    private static SongRequestEntity waiting(long id, String name) {
        return SongRequestEntity.builder().id(id).partyCode(PARTY).songName(name).decision(DjService.DECISION_ACCEPTED)
                .requestedAt(Instant.now()).build();
    }

    /** The queue in the database is now {@code songs}, with the fingerprint {@code fingerprint}. */
    private void queueIs(String fingerprint, SongRequestEntity... songs) {
        when(songRequestRepository.computeFingerprint(eq(PARTY), anyCollection())).thenReturn(fingerprint);
        when(songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(eq(PARTY), anyCollection()))
                .thenReturn(List.of(songs));
    }

    private record Answer(int status, String etag, List<SongRequestEntity> queue) {
    }

    @SuppressWarnings("unchecked")
    private Answer poll(String etag) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (etag != null) request.addHeader("If-None-Match", etag);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ExtendedModelMap model = new ExtendedModelMap();
        controller.getDashboardUpdates(PARTY, model, request, response, null, new MockHttpSession());
        return new Answer(response.getStatus(), response.getHeader("ETag"), (List<SongRequestEntity>) model.get(ViewAttributes.HISTORY));
    }

    @Test
    void aRequestCommittedWhileTheQueueIsCached_reachesTheDj_andThePollDoesNotFreeze() {
        SongRequestEntity first = waiting(12, "Wilki - Baśka");
        queueIs("1-12-a", first);
        Answer before = poll(null);
        assertThat(before.queue()).extracting(SongRequestEntity::getId).containsExactly(12L);

        // a guest opens the party page: the cache holds the queue of one song
        assertThat(djService.getDashboardQueue(PARTY)).hasSize(1);
        // …and another guest's request commits within the cache's 3 s
        queueIs("2-13-b", first, waiting(13, "sanah - Szampan"));

        Answer changed = poll(before.etag());
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.queue()).as("the list the new ETag stands for").extracting(SongRequestEntity::getId)
                .containsExactly(12L, 13L);
        // the next poll with that ETag: nothing new, and nothing was hidden behind it
        assertThat(poll(changed.etag()).status()).isEqualTo(304);
    }

    /** The guests' list (the same cache) shows a new request, or one more vote, with its next read — not up to 3 s later. */
    @Test
    void aGuestsRequestOrVote_isInTheGuestsListAtOnce() {
        SongRequestEntity first = waiting(12, "Wilki - Baśka");
        queueIs("1-12-a", first);
        assertThat(djService.getDashboardQueue(PARTY)).hasSize(1);

        SongRequestEntity request = waiting(0, "sanah - Szampan");
        request.setId(null);
        when(songRequestRepository.save(request)).thenReturn(waiting(13, "sanah - Szampan"));
        assertThat(commands.saveOrVote(request, Set.of()).outcome()).isEqualTo(SongRequestCommandService.Outcome.NEW);
        queueIs("2-13-b", first, waiting(13, "sanah - Szampan"));   // committed
        assertThat(djService.getDashboardQueue(PARTY)).extracting(SongRequestEntity::getId).containsExactly(12L, 13L);

        // the same song again: a vote on the waiting one
        when(songRequestRepository.addVote(12L)).thenReturn(1);
        assertThat(commands.saveOrVote(waiting(0, "wilki baśka"), Set.of()).outcome()).isEqualTo(SongRequestCommandService.Outcome.VOTE);
        SongRequestEntity voted = waiting(12, "Wilki - Baśka");
        voted.setVotes(2);
        queueIs("2-13-c", voted, waiting(13, "sanah - Szampan"));   // committed
        assertThat(djService.getDashboardQueue(PARTY)).extracting(SongRequestEntity::getVotes).containsExactly(2, 1);
    }
}
