package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
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
import static org.mockito.Mockito.never;
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
    /** What the (mocked) settings service returns after an update: the party as saved, shuffle on by default. */
    private PartySettingsEntity savedSettings;
    private DjSessionHelper sessionHelper;
    private FallbackPlaylistService fallbackPlaylistService;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        savedSettings = PartySettingsEntity.builder().partyCode(PARTY).fallbackShuffle(true).build();
        when(settingsService.updateSettings(eq(PARTY), any())).thenAnswer(invocation -> savedSettings);
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
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST_ID, true)).thenReturn(42);

        setPlaylist("https://www.youtube.com/playlist?list=" + PLAYLIST_ID)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Id", PLAYLIST_ID))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "42"));

        verify(settingsService).updateSettings(eq(PARTY), any());
    }

    @Test
    @DisplayName("a YouTube Mix (list=RD…) is refused before anything is saved or imported, with its own reason")
    void shouldRefuseAYouTubeMix_withoutSavingIt() throws Exception {
        setPlaylist("https://www.youtube.com/watch?v=3z-jNRAwSHk&list=RD3z-jNRAwSHk")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Saved", "false"))
                .andExpect(header().string("X-Fallback-Import", "failed"))
                .andExpect(header().string("X-Fallback-Import-Reason", "YOUTUBE_MIX"))
                .andExpect(header().doesNotExist("X-Fallback-Id"));

        verify(settingsService, org.mockito.Mockito.never()).updateSettings(any(), any());
        org.mockito.Mockito.verifyNoInteractions(fallbackPlaylistService);
    }

    @Test
    @DisplayName("a failed import still saves the setting and answers 200 (playback does not depend on it yet)")
    void shouldStillSaveSetting_whenImportFails() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST_ID, true))
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
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, "V:dQw4w9WgXcQ", true)).thenReturn(1);

        setPlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .andExpect(header().string("X-Fallback-Id", "V:dQw4w9WgXcQ"))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "1"));
    }

    @Test
    @DisplayName("clearing the playlist (blank URL) clears the server-side tracks too")
    void shouldClearTracks_whenUrlIsBlank() throws Exception {
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, null, true)).thenReturn(0);

        setPlaylist("   ")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Id", ""))
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "0"));

        verify(fallbackPlaylistService).syncFallbackTracks(PARTY, null, true);
    }

    @Test
    @DisplayName("party ownership is validated before anything is saved or imported")
    void shouldValidateOwnership() throws Exception {
        setPlaylist(null);

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("the import uses the party's shuffle setting, so the imported tracks get the right order")
    void shouldImportWithTheShuffleSetting() throws Exception {
        savedSettings.setFallbackShuffle(false);
        when(fallbackPlaylistService.syncFallbackTracks(PARTY, PLAYLIST_ID, false)).thenReturn(5);

        setPlaylist("https://www.youtube.com/playlist?list=" + PLAYLIST_ID)
                .andExpect(header().string("X-Fallback-Import", "ok"))
                .andExpect(header().string("X-Fallback-Tracks", "5"));
    }

    // ---- shuffle switch ----

    private org.springframework.test.web.servlet.ResultActions toggleShuffle() throws Exception {
        return mockMvc.perform(post("/dj/dashboard/fallback-shuffle").param("partyCode", PARTY).principal(token).session(session));
    }

    @Test
    @DisplayName("shuffle switched on: the tracks still to play are re-ordered and the new state is reported")
    void shouldReorderTheQueue_whenShuffleIsSwitchedOn() throws Exception {
        savedSettings.setFallbackShuffle(true);
        savedSettings.setFallbackPlaylistUrl("https://www.youtube.com/playlist?list=" + PLAYLIST_ID);

        toggleShuffle()
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Shuffle", "true"));

        verify(fallbackPlaylistService).applyShuffleSetting(PARTY, PLAYLIST_ID, true);
    }

    @Test
    @DisplayName("shuffle switched off: the queue goes back to playlist order")
    void shouldReorderTheQueue_whenShuffleIsSwitchedOff() throws Exception {
        savedSettings.setFallbackShuffle(false);
        savedSettings.setFallbackPlaylistUrl("https://youtu.be/dQw4w9WgXcQ");

        toggleShuffle()
                .andExpect(header().string("X-Fallback-Shuffle", "false"));

        verify(fallbackPlaylistService).applyShuffleSetting(PARTY, "V:dQw4w9WgXcQ", false);
    }

    @Test
    @DisplayName("without a fallback playlist the switch only saves the setting — there is nothing to re-order")
    void shouldOnlySaveTheSetting_whenThereIsNoPlaylist() throws Exception {
        savedSettings.setFallbackPlaylistUrl(null);

        toggleShuffle().andExpect(status().isOk());

        verify(fallbackPlaylistService, never()).applyShuffleSetting(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("the shuffle switch validates party ownership first")
    void shouldValidateOwnership_whenTogglingShuffle() throws Exception {
        toggleShuffle();

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("a link longer than its column is refused before saving, like a Mix (it used to end in a 500 from the database)")
    void shouldRefuseALinkLongerThanItsColumn() throws Exception {
        setPlaylist("https://www.youtube.com/playlist?list=" + PLAYLIST_ID + "&x=" + "a".repeat(500))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Saved", "false"))
                .andExpect(header().string("X-Fallback-Import", "failed"))
                .andExpect(header().string("X-Fallback-Import-Reason", Reason.NOT_A_LINK.name()));

        verify(settingsService, never()).updateSettings(any(), any());
        verify(fallbackPlaylistService, never()).syncFallbackTracks(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("what is no YouTube link at all (\"Hahaha\") is refused before saving: the party's playlist plays on (the owner, 2026-10-03)")
    void shouldRefuseWhatIsNoYouTubeLink() throws Exception {
        setPlaylist("Hahaha")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fallback-Saved", "false"))
                .andExpect(header().string("X-Fallback-Import", "failed"))
                .andExpect(header().string("X-Fallback-Import-Reason", Reason.NOT_A_LINK.name()));

        verify(settingsService, never()).updateSettings(any(), any());
        verify(fallbackPlaylistService, never()).syncFallbackTracks(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("the guest limits and the duplicate window are kept within bounds (review item 5.4)")
    void shouldKeepTheLimitsWithinBounds() throws Exception {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).build();
        when(settingsService.updateSettings(eq(PARTY), any())).thenAnswer(invocation -> {
            invocation.<java.util.function.Consumer<PartySettingsEntity>>getArgument(1).accept(party);
            return party;
        });

        mockMvc.perform(post("/dj/dashboard/limits").param("partyCode", PARTY).principal(token).session(session)
                .param("requestLimit", "100000").param("cooldownMinutes", "0").param("duplicateCheckWindow", "100000"));

        org.assertj.core.api.Assertions.assertThat(party.getRequestLimit()).isEqualTo(DjPartySettingsController.MAX_REQUEST_LIMIT);
        org.assertj.core.api.Assertions.assertThat(party.getCooldownMinutes()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(party.getDuplicateCheckWindow())
                .isEqualTo(DjPartySettingsController.MAX_DUPLICATE_CHECK_WINDOW);
    }
}
