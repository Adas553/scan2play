package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.SpotifyAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Spotify playback login: its OAuth {@code state} is a random value kept in the DJ's session and used once — not the party
 * code, which anyone can read from the QR code.
 */
@ExtendWith(MockitoExtension.class)
class SpotifyAuthControllerTest {

    private static final String PARTY = "ABC12";

    @Mock
    private SpotifyAuthService spotifyAuthService;
    @Mock
    private DjSessionHelper sessionHelper;
    @Mock
    private OAuth2AuthenticationToken authentication;

    @InjectMocks
    private SpotifyAuthController controller;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    /** Starts a login and returns the state it sent to Spotify. */
    private String startLogin() {
        when(spotifyAuthService.getAuthorizationUrl(anyString())).thenReturn("https://accounts.spotify.com/authorize?x");
        controller.spotifyLogin(PARTY, authentication, session);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(spotifyAuthService).getAuthorizationUrl(state.capture());
        return state.getValue();
    }

    @Test
    void login_sendsARandomStateThatIsNotThePartyCode_andKeepsItInTheSession() {
        String state = startLogin();

        verify(sessionHelper).validateOwnership(PARTY, authentication, session);
        assertThat(state).isNotEqualTo(PARTY).hasSize(64);
        assertThat(session.getAttribute(SpotifyAuthController.SESSION_OAUTH_STATE)).isEqualTo(state);
    }

    @Test
    void callback_withTheStateOfThisSession_storesTheTokensForTheDjsOwnParty_once() throws Exception {
        String state = startLogin();
        when(sessionHelper.getPartySettings(authentication, session))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).build());

        assertThat(controller.spotifyCallback("code-1", state, authentication, session)).isEqualTo(ViewAttributes.REDIRECT_DASHBOARD);
        verify(spotifyAuthService).exchangeCodeForToken("code-1", PARTY);

        // the same state a second time (a replayed link) is refused
        assertThatThrownBy(() -> controller.spotifyCallback("code-2", state, authentication, session))
                .isInstanceOf(AccessDeniedException.class);
        verify(spotifyAuthService, never()).exchangeCodeForToken("code-2", PARTY);
    }

    @Test
    void callback_withThePartyCodeAsState_isRefused() {
        startLogin();

        assertThatThrownBy(() -> controller.spotifyCallback("attacker-code", PARTY, authentication, session))
                .isInstanceOf(AccessDeniedException.class);
        verify(spotifyAuthService, never()).exchangeCodeForToken(any(), any());
    }

    @Test
    void callback_withoutALoginStartedInThisSession_isRefused() {
        assertThatThrownBy(() -> controller.spotifyCallback("code", "whatever", authentication, session))
                .isInstanceOf(AccessDeniedException.class);
        verify(spotifyAuthService, never()).exchangeCodeForToken(any(), any());
    }
}
