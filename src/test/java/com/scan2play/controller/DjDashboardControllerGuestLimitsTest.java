package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.YouTubeSearchBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The X-Guest-Limits header of the queue poll (review item 4.1): the dashboard shows a warning while a server limit stops guest
 * songs, and it learns that from every poll — the 304s too, since reaching a limit does not change the queue.
 */
class DjDashboardControllerGuestLimitsTest {

    private static final String PARTY = "ABC12";
    private static final String ETAG = "\"q-3-42\"";

    private DjService djService;
    private PartySettingsQueryService settingsService;
    private GuestRequestLimiter limiter;
    private YouTubeSearchBudget budget;
    private DjDashboardController controller;

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        settingsService = mock(PartySettingsQueryService.class);
        limiter = mock(GuestRequestLimiter.class);
        budget = mock(YouTubeSearchBudget.class);
        controller = new DjDashboardController(djService, settingsService, mock(QrCodeService.class), mock(DjSessionHelper.class),
                mock(PlayHistoryService.class), limiter, budget);
        when(djService.getQueueFingerprint(PARTY)).thenReturn("3-42");
        when(djService.getDashboardQueue(PARTY)).thenReturn(List.of());
    }

    private void givenProvider(MusicProviderType provider) {
        when(settingsService.getSettings(PARTY)).thenReturn(PartySettingsEntity.builder().partyCode(PARTY).activeProvider(provider).build());
    }

    /** Polls the queue; {@code unchanged} = the client already has this version (the answer is a 304). */
    private MockHttpServletResponse poll(boolean unchanged) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (unchanged) request.addHeader("If-None-Match", ETAG);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.getDashboardUpdates(PARTY, new ExtendedModelMap(), request, response, null, new MockHttpSession());
        return response;
    }

    @Test
    void noLimitReached_sayNone() {
        givenProvider(MusicProviderType.YOUTUBE);

        MockHttpServletResponse response = poll(true);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("none");
    }

    @Test
    void aReachedLimit_isOnA304_too() {
        givenProvider(MusicProviderType.YOUTUBE);
        when(budget.isSpent()).thenReturn(true);
        when(limiter.isPartyLimitReached(PARTY)).thenReturn(true);

        MockHttpServletResponse response = poll(true);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("search-spent,party-full");
    }

    @Test
    void aFullAnswer_carriesTheHeader() {
        givenProvider(MusicProviderType.YOUTUBE);
        when(budget.isSpent()).thenReturn(true);

        MockHttpServletResponse response = poll(false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isEqualTo(ETAG);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("search-spent");
    }

    @Test
    void theUseOfTheLimits_isOnEveryAnswer_a304Too() {
        givenProvider(MusicProviderType.YOUTUBE);
        when(limiter.busiestClientRequestsUsed(PARTY)).thenReturn(24);
        when(limiter.partyRequestsUsed(PARTY)).thenReturn(5);

        assertThat(poll(true).getHeader(DjDashboardController.GUEST_LIMITS_USE_HEADER)).isEqualTo("24,5");
        assertThat(poll(false).getHeader(DjDashboardController.GUEST_LIMITS_USE_HEADER)).isEqualTo("24,5");
    }

    /** The party ended in another window (the DJ's phone): every window learns it from its next poll, a 304 too. */
    @Test
    void whetherThePartyIsOpen_isOnEveryAnswer_a304Too() {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).activeProvider(MusicProviderType.YOUTUBE)
                .active(true).build();
        when(settingsService.getSettings(PARTY)).thenReturn(party);
        assertThat(poll(true).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("true");

        party.setActive(false);
        assertThat(poll(true).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("false");
        assertThat(poll(false).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("false");
    }

    @Test
    void spentYouTubeSearches_doNotConcernARequestsOnlyParty() {
        givenProvider(MusicProviderType.REQUESTS_ONLY);
        when(budget.isSpent()).thenReturn(true);

        assertThat(poll(true).getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("none");
    }
}
