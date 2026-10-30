package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private DjSessionHelper sessionHelper;
    private GuestRequestLimiter limiter;
    private DjDashboardController controller;
    private PartySettingsEntity party;
    private final com.scan2play.service.AiHealthMonitor aiHealth = new com.scan2play.service.AiHealthMonitor();

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        sessionHelper = mock(DjSessionHelper.class);
        limiter = mock(GuestRequestLimiter.class);
        controller = new DjDashboardController(djService, mock(PartySettingsCommandService.class), mock(QrCodeService.class), sessionHelper,
                mock(PlayHistoryService.class), limiter, mock(PushNotificationService.class), mock(com.scan2play.service.PartyStaffService.class), mock(com.scan2play.service.StaffInvitationService.class), com.scan2play.service.Plans.off(), aiHealth, com.scan2play.service.PartySchedules.texts());
        when(djService.getQueueFingerprint(PARTY)).thenReturn("3-42");
        when(djService.getWaiting(PARTY)).thenReturn(List.of());
        party = PartySettingsEntity.builder().partyCode(PARTY).active(true).build();
        when(sessionHelper.access(eq(PARTY), any(), any())).thenReturn(new PartyStaffService.Access(party, true, StaffPermission.all()));
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
        MockHttpServletResponse response = poll(true);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("none");
    }

    @Test
    void aReachedLimit_isOnA304_too() {
        when(limiter.isPartyLimitReached(PARTY)).thenReturn(true);

        MockHttpServletResponse response = poll(true);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("party-full");
    }

    @Test
    void aFullAnswer_carriesTheHeader() {
        when(limiter.isPartyLimitReached(PARTY)).thenReturn(true);

        MockHttpServletResponse response = poll(false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isEqualTo(ETAG);
        assertThat(response.getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("party-full");
    }

    /**
     * The review of 2026-10-21, O2 (the owner, 2026-10-24): three requests in a row the AI did not judge — the bar "AI chwilowo nie
     * ocenia próśb" above the queue of every panel, from the same header (a 304 too), alongside a reached limit; gone with an answer.
     */
    @Test
    void theAiDown_isAFlagOfEveryAnswer_untilTheAiAnswersAgain() {
        for (int i = 0; i < 3; i++) {
            aiHealth.recordUnchecked();
        }
        assertThat(poll(true).getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("ai-down");
        when(limiter.isPartyLimitReached(PARTY)).thenReturn(true);
        assertThat(poll(false).getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("party-full,ai-down");

        aiHealth.recordAnswered();
        assertThat(poll(true).getHeader(DjDashboardController.GUEST_LIMITS_HEADER)).isEqualTo("party-full");
    }

    @Test
    void theUseOfTheLimits_isOnEveryAnswer_a304Too() {
        when(limiter.busiestClientRequestsUsed(PARTY)).thenReturn(24);
        when(limiter.partyRequestsUsed(PARTY)).thenReturn(5);

        assertThat(poll(true).getHeader(DjDashboardController.GUEST_LIMITS_USE_HEADER)).isEqualTo("24,5");
        assertThat(poll(false).getHeader(DjDashboardController.GUEST_LIMITS_USE_HEADER)).isEqualTo("24,5");
    }

    /** The party ended in another window (the DJ's phone): every window learns it from its next poll, a 304 too. */
    @Test
    void whetherThePartyIsOpen_isOnEveryAnswer_a304Too() {
        assertThat(poll(true).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("true");

        party.setActive(false);
        assertThat(poll(true).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("false");
        assertThat(poll(false).getHeader(DjDashboardController.PARTY_ACTIVE_HEADER)).isEqualTo("false");
    }

    /** The hosts' wish list changed (V29): the queue is sent again, so a waiting song gets its ⭐ with nothing else changed. */
    @Test
    void aChangeOfTheHostsWishes_isANewVersionOfTheQueue() {
        party.setHostWanted("Hej sokoły");
        MockHttpServletResponse withWishes = poll(true);
        assertThat(withWishes.getStatus()).as("the old version is not this one any more").isEqualTo(200);
        String etag = withWishes.getHeader("ETag");

        party.setHostWanted("Hej sokoły\nAkcent");
        assertThat(poll(false).getHeader("ETag")).isNotEqualTo(etag).startsWith("\"q-3-42-w");
    }
    /**
     * The organiser's plan changes, the queue stays (V35): Free and Pro are two versions of the rows (without Pro no "💸" and no ⭐),
     * so the window that had Pro's rows gets the Free ones, not a 304.
     */
    @Test
    void aChangeOfThePlan_isANewVersionOfTheQueue() {
        party.setOwnerId("organiser");
        party.setHostWanted("Hej sokoły");
        String pro = etagWith(com.scan2play.service.Plans.enforced("organiser"));
        String free = etagWith(com.scan2play.service.Plans.enforced());

        assertThat(pro).isEqualTo("\"q-3-42-w" + Integer.toHexString("Hej sokoły".hashCode()) + "\"");
        assertThat(free).as("no hosts' wishes, no tips").isEqualTo("\"q-3-42-f\"").isNotEqualTo(pro);
    }

    private String etagWith(com.scan2play.service.PlanService plans) {
        controller = new DjDashboardController(djService, mock(PartySettingsCommandService.class), mock(QrCodeService.class), sessionHelper,
                mock(PlayHistoryService.class), limiter, mock(PushNotificationService.class), mock(com.scan2play.service.PartyStaffService.class),
                mock(com.scan2play.service.StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor(), com.scan2play.service.PartySchedules.texts());
        return poll(false).getHeader("ETag");
    }
}
