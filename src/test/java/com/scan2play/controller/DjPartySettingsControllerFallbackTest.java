package com.scan2play.controller;

import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.FallbackImportException;
import com.scan2play.service.FallbackImportException.Reason;
import com.scan2play.service.FallbackPlaylistService;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@code POST /dj/dashboard/fallback-playlist}: the setting is always saved, and the
 * server-side import of the playlist is reported through response headers.
 */
class DjPartySettingsControllerFallbackTest {

    private static final String PARTY = "ABC12";
    private static final String PLAYLIST_ID = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";

    private PartySettingsCommandService settingsService;
    private DjSessionHelper sessionHelper;
    private FallbackPlaylistService fallbackPlaylistService;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        sessionHelper = mock(DjSessionHelper.class);
        fallbackPlaylistService = mock(FallbackPlaylistService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(
                settingsService, mock(AccountDeletionService.class), sessionHelper, fallbackPlaylistService)).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    private org.springframework.test.web.servlet.ResultActions setPlaylist(String url) throws Exception {
        var request = post("/dj/dashboard/fallback-playlist").param("partyCode", PARTY).principal(token).session(session);
        if (url != null) {
            request.param("fallbackPlaylistUrl", url);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("a playlist URL is saved, imported, and the result is reported in headers")
    void shouldImportPlaylistAndReportInHeaders() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST_ID)).thenReturn(42);

        setPlaylist("https://www.youtube.com/playlist?list=" + PLAYLIST_ID)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Id", PLAYLIST_ID))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "42"));

        verify(settingsService).updateSettings(eq(PARTY), any());
    }

    @Test
    @DisplayName("a failed import still saves the setting and answers 200 (playback does not depend on it yet)")
    void shouldStillSaveSetting_whenImportFails() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST_ID))
                .thenThrow(new FallbackImportException(Reason.NO_API_KEY, "no key"));

        setPlaylist("https://www.youtube.com/playlist?list=" + PLAYLIST_ID)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Id", PLAYLIST_ID))
                .andExpect(header().string("X-Fallback-Import", "failed"))
                .andExpect(header().string("X-Fallback-Import-Reason", "NO_API_KEY"))
                .andExpect(header().doesNotExist("X-Fallback-Tracks"));

        verify(settingsService).updateSettings(eq(PARTY), any());
    }

    @Test
    @DisplayName("a single-video URL is passed on as V:<id>")
    void shouldPassSingleVideoAsPrefixedId() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, "V:dQw4w9WgXcQ")).thenReturn(1);

        setPlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .andExpect(header().string("X-Fallback-Id", "V:dQw4w9WgXcQ"))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "1"));
    }

    @Test
    @DisplayName("clearing the playlist (blank URL) clears the server-side tracks too")
    void shouldClearTracks_whenUrlIsBlank() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, null)).thenReturn(0);

        setPlaylist("   ")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Id", ""))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "0"));

        verify(fallbackPlaylistService).syncFallbackTracks(PARTY, null);
    }

    @Test
    @DisplayName("party ownership is validated before anything is saved or imported")
    void shouldValidateOwnership() throws Exception {
        setPlaylist(null);

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }
}
