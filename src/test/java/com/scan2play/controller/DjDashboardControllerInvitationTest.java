package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.StaffInvitationEntity;
import com.scan2play.model.StaffRole;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.StaffInvitationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An invitation by e-mail (V34) is asked before any panel: whoever logs in — the app, a browser, the landing page's login, all end on
 * the panel — with a Google address an organiser invited is sent to "Dołączyć?" first, and no DJ's party is made for them meanwhile.
 */
class DjDashboardControllerInvitationTest {

    private final DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
    private final StaffInvitationService invitations = mock(StaffInvitationService.class);
    private final DjDashboardController controller = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
            mock(QrCodeService.class), sessionHelper, mock(PlayHistoryService.class), mock(GuestRequestLimiter.class),
            mock(PushNotificationService.class), mock(PartyStaffService.class), invitations);

    private static OAuth2AuthenticationToken kasia(boolean verified) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "kasia", "email", "kasia@gmail.com", "email_verified", verified), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    void anInvitationWaiting_isAskedBeforeAnyPanel() {
        when(invitations.waitingFor("kasia@gmail.com")).thenReturn(Optional.of(new StaffInvitationService.Waiting(
                StaffInvitationEntity.builder().id(3L).partyCode("PUB01").permissions(StaffRole.QUEUE.permissions())
                        .invitedAt(Instant.now()).build(), PartySettingsEntity.builder().partyCode("PUB01").build())));

        String view = controller.dashboard(null, new ExtendedModelMap(), kasia(true), new MockHttpSession());

        assertThat(view).isEqualTo("redirect:/dj/invitation");
        verify(sessionHelper, never()).panel(any(), any(), any());
    }

    /** Google did not verify the address: it could be anyone's — no invitation is matched against it. */
    @Test
    void anUnverifiedAddress_isNotAsked() {
        controller.dashboard(null, new ExtendedModelMap(), kasia(false), new MockHttpSession());

        verify(invitations).waitingFor(null);
        verify(sessionHelper).panel(any(), any(), any());
    }
}
