package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.AiHealthMonitor;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.Plans;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.StaffInvitationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The organiser's address (V48): kept with the party when they open their panel — Google's verified one only, never the staff's,
 * written only when it changed — and the welcome card: shown to the organiser until they close it, closed once on the server.
 */
class DjDashboardControllerOwnerContactTest {

    private final DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
    private final PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
    private final DjDashboardController controller = new DjDashboardController(mock(DjService.class), commands,
            mock(QrCodeService.class), sessionHelper, mock(PlayHistoryService.class), mock(GuestRequestLimiter.class),
            mock(PushNotificationService.class), mock(PartyStaffService.class), mock(StaffInvitationService.class), Plans.off(),
            new AiHealthMonitor(), com.scan2play.service.PartySchedules.texts());

    DjDashboardControllerOwnerContactTest() {
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "http://localhost:8080");
        controller.init();
    }

    private static OAuth2AuthenticationToken ola(String email, Object verified) {
        Map<String, Object> attributes = new HashMap<>(Map.of("sub", "ola", "name", "Ola Kowalska"));
        if (email != null) {
            attributes.put("email", email);
        }
        if (verified != null) {
            attributes.put("email_verified", verified);
        }
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    private static PartySettingsEntity.PartySettingsEntityBuilder party() {
        return PartySettingsEntity.builder().partyCode("WED01").ownerId("ola").active(true);
    }

    private ExtendedModelMap openPanel(PartySettingsEntity party, boolean owner, OAuth2AuthenticationToken who) {
        when(sessionHelper.panel(any(), any(), any())).thenReturn(Optional.of(
                new PartyStaffService.Access(party, owner, owner ? Set.of() : Set.of(StaffPermission.QUEUE))));
        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(controller.dashboard(null, model, who, new MockHttpSession())).isEqualTo("dashboard");
        return model;
    }

    /** The one write of the panel's opening, applied to a party as it was. */
    @SuppressWarnings("unchecked")
    private PartySettingsEntity written(PartySettingsEntity before) {
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(commands).updateSettings(eq("WED01"), updater.capture());
        PartySettingsEntity after = before.toBuilder().build();
        updater.getValue().accept(after);
        return after;
    }

    @Test
    void theOrganisersFirstPanel_keepsTheirVerifiedAddress_andName() {
        PartySettingsEntity before = party().build();

        openPanel(before, true, ola("ola@gmail.com", true));

        PartySettingsEntity after = written(before);
        assertThat(after.getOwnerEmail()).isEqualTo("ola@gmail.com");
        assertThat(after.getOwnerName()).isEqualTo("Ola Kowalska");
    }

    /** Google's "email_verified" comes as a boolean or as the text "true" — either counts. */
    @Test
    void anAddressVerifiedAsText_isKeptToo() {
        openPanel(party().build(), true, ola("ola@gmail.com", "true"));

        assertThat(written(party().build()).getOwnerEmail()).isEqualTo("ola@gmail.com");
    }

    @Test
    void nothingChanged_writesNothing() {
        openPanel(party().ownerEmail("ola@gmail.com").ownerName("Ola Kowalska").build(), true, ola("ola@gmail.com", true));

        verify(commands, never()).updateSettings(any(), any());
    }

    /** The organiser changed the address of their Google account: the next panel keeps the new one. */
    @Test
    void aNewAddress_replacesTheOld() {
        PartySettingsEntity before = party().ownerEmail("ola.old@gmail.com").ownerName("Ola Kowalska").build();

        openPanel(before, true, ola("ola@gmail.com", true));

        assertThat(written(before).getOwnerEmail()).isEqualTo("ola@gmail.com");
    }

    /** Google did not verify it, or gave none: it could be anyone's — not kept, and one kept before is not trusted any more. */
    @Test
    void anUnverifiedAddress_orNone_isNotKept() {
        PartySettingsEntity before = party().ownerEmail("ola@gmail.com").ownerName("Ola Kowalska").build();
        openPanel(before, true, ola("ola@gmail.com", false));
        assertThat(written(before).getOwnerEmail()).isNull();

        assertThat(DjDashboardController.ownerEmail(ola("ola@gmail.com", null))).isNull();
        assertThat(DjDashboardController.ownerEmail(ola(null, true))).isNull();
        assertThat(DjDashboardController.ownerEmail(ola("x".repeat(311) + "@gmail.com", true))).as("longer than the column").isNull();
    }

    /** A person of the staff opening the organiser's panel: nothing of theirs is kept with the party, and no welcome card. */
    @Test
    void theStaff_areNeverKept_andSeeNoWelcome() {
        ExtendedModelMap model = openPanel(party().build(), false, ola("kasia@gmail.com", true));

        verify(commands, never()).updateSettings(any(), any());
        assertThat(model.get(ViewAttributes.WELCOME)).isEqualTo(false);
    }

    @Test
    void theWelcomeCard_isShownToTheOrganiser_untilClosed() {
        assertThat(openPanel(party().build(), true, ola("ola@gmail.com", true)).get(ViewAttributes.WELCOME)).isEqualTo(true);
        assertThat(openPanel(party().ownerEmail("ola@gmail.com").ownerName("Ola Kowalska").welcomeDismissedAt(Instant.now()).build(),
                true, ola("ola@gmail.com", true)).get(ViewAttributes.WELCOME)).isEqualTo(false);
    }

    @Test
    void closingTheWelcome_keepsWhen_once() {
        PartySettingsEntity before = party().build();
        OAuth2AuthenticationToken who = ola("ola@gmail.com", true);
        when(sessionHelper.requireOwner(eq("WED01"), eq(who), any())).thenReturn(before);

        assertThat(controller.dismissWelcome("WED01", who, new MockHttpSession()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Instant closed = written(before).getWelcomeDismissedAt();
        assertThat(closed).isNotNull();
        Instant earlier = Instant.parse("2026-10-01T10:00:00Z");
        PartySettingsEntity again = party().welcomeDismissedAt(earlier).build();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(commands).updateSettings(eq("WED01"), updater.capture());
        updater.getValue().accept(again);
        assertThat(again.getWelcomeDismissedAt()).as("closed meanwhile in another tab: the first time stays").isEqualTo(earlier);
    }

    @Test
    void closingTheWelcomeAgain_writesNothing() {
        OAuth2AuthenticationToken who = ola("ola@gmail.com", true);
        when(sessionHelper.requireOwner(eq("WED01"), eq(who), any())).thenReturn(party().welcomeDismissedAt(Instant.now()).build());

        controller.dismissWelcome("WED01", who, new MockHttpSession());

        verify(commands, never()).updateSettings(any(), any());
    }
}
