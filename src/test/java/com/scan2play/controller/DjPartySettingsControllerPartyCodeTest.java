package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartyCodeService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.Plans;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "Zmień kod imprezy" (V49): the organiser's alone; the page "Ustawienia imprezy" of the new code, its card saying it, and this
 * browser's panel following the new code at once.
 */
class DjPartySettingsControllerPartyCodeTest {

    private final DjSessionHelper helper = mock(DjSessionHelper.class);
    private final PartyCodeService codes = mock(PartyCodeService.class);
    private final DjPartySettingsController controller = new DjPartySettingsController(mock(PartySettingsCommandService.class),
            mock(AccountDeletionService.class), codes, helper, Plans.off(), com.scan2play.service.PartySchedules.of(mock(PartySettingsCommandService.class)));
    private final OAuth2AuthenticationToken ola = new OAuth2AuthenticationToken(new DefaultOAuth2User(
            AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "ola"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"),
            "google");

    @Test
    void theOrganiser_getsANewCode_theSettingsOfIt_andThePanelFollows() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(DjSessionHelper.SESSION_PARTY_CODE, "OLD01");
        when(helper.requireOwner(eq("OLD01"), eq(ola), any())).thenReturn(PartySettingsEntity.builder().partyCode("OLD01").build());
        when(codes.changeCode("OLD01")).thenReturn("NEW01");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.changePartyCode("OLD01", ola, session, redirect);

        assertThat(view).isEqualTo("redirect:/dj/settings?party=NEW01#partyCodeCard");
        assertThat(redirect.getFlashAttributes().get(ViewAttributes.CODE_CHANGED)).isEqualTo("NEW01");
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isEqualTo("NEW01");
    }

    @Test
    void theStaff_changeNothing() {
        when(helper.requireOwner(eq("OLD01"), eq(ola), any())).thenThrow(new AccessDeniedException("Only the party's owner may do this"));

        assertThatThrownBy(() -> controller.changePartyCode("OLD01", ola, new MockHttpSession(), new RedirectAttributesModelMap()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(codes);
    }
}
