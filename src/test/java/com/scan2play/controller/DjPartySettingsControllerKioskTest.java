package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySchedules;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlanRequiredException;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The venue's kiosk link (V56, Pro): made, made again (a new secret — the old tablet's link dead) or taken away by whoever has the
 * permission KIOSK; without Pro neither the link is made nor a kiosk opens.
 */
class DjPartySettingsControllerKioskTest {

    private static final String PARTY = "ABC12";
    private static final OAuth2AuthenticationToken DJ = new OAuth2AuthenticationToken(
            new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
            AuthorityUtils.createAuthorityList("ROLE_USER"), "google");

    private final PartySettingsEntity row = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).build();

    @SuppressWarnings("unchecked")
    private DjPartySettingsController controller(DjSessionHelper helper, PlanService plans) {
        PartySettingsCommandService settings = mock(PartySettingsCommandService.class);
        when(settings.updateSettings(eq(PARTY), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(row);
            return row;
        });
        return new DjPartySettingsController(settings, mock(AccountDeletionService.class), mock(com.scan2play.service.PartyCodeService.class),
                helper, plans, PartySchedules.of(settings));
    }

    private DjSessionHelper helperAllowing() {
        DjSessionHelper helper = mock(DjSessionHelper.class);
        when(helper.require(eq(PARTY), eq(StaffPermission.KIOSK), any(), any())).thenReturn(row);
        return helper;
    }

    @Test
    void theLink_isMade_madeAgain_andTakenAway() {
        DjPartySettingsController controller = controller(helperAllowing(), Plans.off());

        assertThat(controller.updateKioskLink(PARTY, "new", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap())).isEqualTo("redirect:/dj/settings?party=ABC12#kioskCard");
        String first = row.getKioskToken();
        assertThat(first).hasSizeGreaterThanOrEqualTo(20);
        controller.updateKioskLink(PARTY, "new", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap());
        assertThat(row.getKioskToken()).as("a new secret: the old tablet's link is dead").isNotEqualTo(first);
        controller.updateKioskLink(PARTY, "off", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap());
        assertThat(row.getKioskToken()).isNull();
        assertThatThrownBy(() -> controller.updateKioskLink(PARTY, "both", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void withoutThePermission_orWithoutPro_noLink() {
        DjSessionHelper refusing = mock(DjSessionHelper.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(refusing).require(eq(PARTY), eq(StaffPermission.KIOSK), any(), any());
        assertThatThrownBy(() -> controller(refusing, Plans.off()).updateKioskLink(PARTY, "new", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);

        assertThatThrownBy(() -> controller(helperAllowing(), Plans.enforced()).updateKioskLink(PARTY, "new", null, DJ, new MockHttpSession(), new org.springframework.ui.ExtendedModelMap()))
                .isInstanceOf(PlanRequiredException.class);
        assertThat(row.getKioskToken()).isNull();
    }

    /** A kiosk of a party without Pro opens nothing (a 404); with Pro it does. */
    @Test
    void aKiosk_opensOnlyWithPro() {
        row.setKioskToken("Kiosk_123-xyzKiosk_123-x");
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByKioskToken("Kiosk_123-xyzKiosk_123-x")).thenReturn(Optional.of(row));
        KioskController free = kiosk(parties, Plans.enforced());
        assertThatThrownBy(() -> free.list("Kiosk_123-xyzKiosk_123-x", new org.springframework.ui.ExtendedModelMap(), new MockHttpSession()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

        KioskController pro = kiosk(parties, Plans.enforced("owner"));
        assertThat(pro.list("Kiosk_123-xyzKiosk_123-x", new org.springframework.ui.ExtendedModelMap(), new MockHttpSession()))
                .isEqualTo("kiosk :: kioskList");
        verify(parties, never()).findByPartyCode(any());
    }

    private static KioskController kiosk(PartySettingsRepository parties, PlanService plans) {
        return new KioskController(parties, plans, mock(GuestController.class), mock(com.scan2play.service.GuestQueueService.class),
                mock(com.scan2play.service.GuestSessionService.class), mock(com.scan2play.service.GuestVoteService.class),
                mock(com.scan2play.service.GuestRequestLimiter.class), PartySchedules.texts(), PartySchedules.messages());
    }
}
