package com.scan2play.controller;

import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.service.DjService;
import com.scan2play.service.NextTrackService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@code GET /dj/dashboard/next-guest-track} — the endpoint YouTube Auto-Pilot asks
 * "what plays next?" (PROJECT_CONTEXT.md Section 14, Phase 1).
 */
class DjDashboardControllerNextGuestTrackTest {

    private static final String PARTY_CODE = "ABC12";

    private DjService djService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjDashboardController(
                djService, mock(PartySettingsQueryService.class), mock(QrCodeService.class), sessionHelper,
                mock(NextTrackService.class), mock(PlayerLeaseService.class), mock(PlayHistoryService.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    @Test
    @DisplayName("200 with songId and videoId when a guest song is waiting")
    void shouldReturnNextTrackAsJson() throws Exception {
        when(djService.findNextPlayableGuestTrack(PARTY_CODE, Set.of()))
                .thenReturn(Optional.of(new NextGuestTrackResponse(42L, "hTWKbfoikeg")));

        mockMvc.perform(get("/dj/dashboard/next-guest-track").param("partyCode", PARTY_CODE)
                        .principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.songId").value(42))
                .andExpect(jsonPath("$.videoId").value("hTWKbfoikeg"));
    }

    @Test
    @DisplayName("204 No Content with an empty body when nothing is waiting (client falls back to the playlist)")
    void shouldReturnNoContent_whenNothingIsWaiting() throws Exception {
        when(djService.findNextPlayableGuestTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/dj/dashboard/next-guest-track").param("partyCode", PARTY_CODE)
                        .principal(token).session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("exclude is parsed into song IDs; blank and malformed entries are ignored")
    void shouldParseExcludeParameter() throws Exception {
        when(djService.findNextPlayableGuestTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/dj/dashboard/next-guest-track").param("partyCode", PARTY_CODE)
                        .param("exclude", "5, 7,abc,,9,1.5").principal(token).session(session))
                .andExpect(status().isNoContent());

        verify(djService).findNextPlayableGuestTrack(PARTY_CODE, Set.of(5L, 7L, 9L));
    }

    @Test
    @DisplayName("no exclude parameter means an empty exclusion set")
    void shouldUseEmptyExclusionSet_whenExcludeIsMissing() throws Exception {
        when(djService.findNextPlayableGuestTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/dj/dashboard/next-guest-track").param("partyCode", PARTY_CODE)
                        .principal(token).session(session))
                .andExpect(status().isNoContent());

        verify(djService).findNextPlayableGuestTrack(PARTY_CODE, Set.of());
    }

    @Test
    @DisplayName("validates that the party belongs to the logged-in DJ before answering (IDOR protection)")
    void shouldValidateOwnership() throws Exception {
        when(djService.findNextPlayableGuestTrack(anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/dj/dashboard/next-guest-track").param("partyCode", PARTY_CODE)
                .principal(token).session(session));

        verify(sessionHelper).validateOwnership(PARTY_CODE, token, session);
    }

    @Test
    @DisplayName("a party owned by someone else is rejected and the queue is never queried")
    void shouldNotQueryQueue_whenPartyBelongsToSomeoneElse() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(get("/dj/dashboard/next-guest-track")
                        .param("partyCode", "OTHER").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(djService);
    }
}
