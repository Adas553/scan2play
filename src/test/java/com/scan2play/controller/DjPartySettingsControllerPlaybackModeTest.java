package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlaybackMode;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.FallbackPlaylistService;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@code POST /dj/dashboard/playback-mode} (the Auto-Pilot switch): the mode the switch sends is set as it is — clicking
 * the switch of a window that showed an old state must not invert the setting — and without a mode the setting is toggled, as
 * before (a page opened before this change).
 */
class DjPartySettingsControllerPlaybackModeTest {

    private static final String PARTY = "ABC12";

    private PartySettingsCommandService settingsService;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(
                settingsService, mock(AccountDeletionService.class), mock(DjSessionHelper.class),
                mock(FallbackPlaylistService.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    /** Posts the switch and applies what the controller asked the settings service to change to a party in {@code before}. */
    @SuppressWarnings("unchecked")
    private PlaybackMode send(PlaybackMode before, String mode) throws Exception {
        var request = post("/dj/dashboard/playback-mode").param("partyCode", PARTY).principal(token).session(session);
        if (mode != null) {
            request.param("mode", mode);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).playbackMode(before).build();
        updater.getValue().accept(party);
        return party.getPlaybackMode();
    }

    @ParameterizedTest(name = "setting {1} on a party in {0} gives {2}")
    @CsvSource({"AUTO, AUTO, AUTO", "AUTO, MANUAL, MANUAL", "MANUAL, AUTO, AUTO", "MANUAL, MANUAL, MANUAL"})
    @DisplayName("the mode the switch sends is set as it is — also when the party is in it already")
    void shouldSetTheModeTheSwitchSends(PlaybackMode before, String sent, PlaybackMode after) throws Exception {
        assertThat(send(before, sent)).isEqualTo(after);
    }

    @ParameterizedTest(name = "without a mode, {0} becomes {1}")
    @CsvSource({"AUTO, MANUAL", "MANUAL, AUTO"})
    @DisplayName("without a mode the setting is toggled, as before")
    void shouldToggle_whenNoModeIsSent(PlaybackMode before, PlaybackMode after) throws Exception {
        assertThat(send(before, null)).isEqualTo(after);
    }
}
