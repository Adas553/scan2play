package com.scan2play.controller;

import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.Plans;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * "Usuń konto" ({@code POST /dj/delete-account}, Google's user data policy): the logged-in person's data — theirs, by their Google
 * subject, never a party the form names —, then the session ends and the home page opens.
 */
class DjPartySettingsControllerDeleteAccountTest {

    @Test
    void usunKonto_deletesThePersonsOwnData_endsTheSession_andGoesHome() {
        AccountDeletionService deletion = mock(AccountDeletionService.class);
        DjSessionHelper helper = mock(DjSessionHelper.class);
        DjPartySettingsController controller = new DjPartySettingsController(mock(PartySettingsCommandService.class), deletion,
                mock(com.scan2play.service.PartyCodeService.class), helper,
                Plans.off());
        OAuth2AuthenticationToken dj = new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "dj-42"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        MockHttpSession session = new MockHttpSession();

        String view = controller.deleteAccount(dj, session);

        verify(deletion).deleteAllUserData("dj-42");
        assertThat(session.isInvalid()).isTrue();
        assertThat(view).isEqualTo("redirect:/");
        verifyNoInteractions(helper);
    }
}
