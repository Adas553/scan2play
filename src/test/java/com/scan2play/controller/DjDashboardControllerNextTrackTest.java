package com.scan2play.controller;

import com.scan2play.model.NextTrackResponse;
import com.scan2play.model.NextTrackResponse.Source;
import com.scan2play.service.DjService;
import com.scan2play.service.NextTrackService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.YouTubeSearchBudget;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PlayerLeaseService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@code POST /dj/dashboard/next-track} — the combined "guest song first, else background track"
 * answer for YouTube Auto-Pilot (PROJECT_CONTEXT.md Section 14, Phase 2 stage 3).
 */
class DjDashboardControllerNextTrackTest {

    private static final String PARTY = "ABC12";

    private static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private NextTrackService nextTrackService;
    private PlayerLeaseService playerLeaseService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        nextTrackService = mock(NextTrackService.class);
        playerLeaseService = mock(PlayerLeaseService.class);
        when(playerLeaseService.mayPlay(any(), any())).thenReturn(true);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjDashboardController(
                mock(DjService.class), mock(PartySettingsQueryService.class), mock(QrCodeService.class),
                sessionHelper, nextTrackService, playerLeaseService, mock(PlayHistoryService.class),
                mock(GuestRequestLimiter.class), mock(YouTubeSearchBudget.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    @Test
    @DisplayName("200 with source GUEST, the song id and the video ID")
    void shouldReturnGuestTrack() throws Exception {
        when(nextTrackService.findNextTrack(PARTY, Set.of()))
                .thenReturn(Optional.of(new NextTrackResponse(Source.GUEST, 42L, "hTWKbfoikeg")));

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("GUEST"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.videoId").value("hTWKbfoikeg"))
                .andExpect(jsonPath("$.playlistId").doesNotExist());
    }

    @Test
    @DisplayName("200 with source BACKGROUND when only the fallback playlist has something to play")
    void shouldReturnBackgroundTrack() throws Exception {
        when(nextTrackService.findNextTrack(PARTY, Set.of()))
                .thenReturn(Optional.of(new NextTrackResponse(Source.BACKGROUND, 7L, "dQw4w9WgXcQ", "PLtestPlaylist01")));

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BACKGROUND"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.videoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.playlistId").value("PLtestPlaylist01"));
    }

    @Test
    @DisplayName("204 No Content with an empty body when there is nothing to play")
    void shouldReturnNoContent_whenNothingToPlay() throws Exception {
        when(nextTrackService.findNextTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("exclude is parsed into guest song IDs; blank and malformed entries are ignored")
    void shouldParseExcludeParameter() throws Exception {
        when(nextTrackService.findNextTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY)
                .param("exclude", "5, 7,abc,,9").principal(token).session(session));

        verify(nextTrackService).findNextTrack(PARTY, Set.of(5L, 7L, 9L));
    }

    @Test
    @DisplayName("the asking window's id is checked against the player lease; when it may play, the track is handed out")
    void shouldAsk_theLease_withTheDeviceId() throws Exception {
        when(nextTrackService.findNextTrack(PARTY, Set.of()))
                .thenReturn(Optional.of(new NextTrackResponse(Source.BACKGROUND, 7L, "dQw4w9WgXcQ")));

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .principal(token).session(session))
                .andExpect(status().isOk());

        verify(playerLeaseService).mayPlay(PARTY, DEVICE);
    }

    @Test
    @DisplayName("409 Conflict and nothing handed out or marked played when another window holds the player lease")
    void shouldReturnConflict_whenAnotherWindowPlays() throws Exception {
        when(playerLeaseService.mayPlay(PARTY, DEVICE)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .principal(token).session(session))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));

        verifyNoInteractions(nextTrackService);
    }

    @Test
    @DisplayName("a request without a window id is checked too — it counts as another window when a lease is live")
    void shouldCheckTheLease_evenWithoutADeviceId() throws Exception {
        when(playerLeaseService.mayPlay(PARTY, null)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isConflict());

        verifyNoInteractions(nextTrackService);
    }

    @Test
    @DisplayName("it changes state (marks a background track played), so it is POST-only — GET is rejected")
    void shouldRejectGet() throws Exception {
        mockMvc.perform(get("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(nextTrackService);
    }

    @Test
    @DisplayName("validates that the party belongs to the logged-in DJ before answering (IDOR protection)")
    void shouldValidateOwnership() throws Exception {
        when(nextTrackService.findNextTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).principal(token).session(session));

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("a party owned by someone else is rejected and nothing is handed out or marked played")
    void shouldNotHandOutAnything_whenPartyBelongsToSomeoneElse() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/next-track")
                        .param("partyCode", "OTHER").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(nextTrackService);
    }
}
