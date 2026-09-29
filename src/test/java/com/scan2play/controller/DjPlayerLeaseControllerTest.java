package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlayerCommand;
import com.scan2play.model.PlayerLeaseMode;
import com.scan2play.service.FallbackQueueService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayerLeaseService;
import com.scan2play.service.PlayerLeaseService.Status;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * Tests {@code POST /dj/dashboard/player-lease} (and {@code .../release}) — which dashboard window plays.
 */
class DjPlayerLeaseControllerTest {

    private static final String PARTY = "ABC12";
    private static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";

    private PlayerLeaseService leaseService;
    private PartySettingsQueryService settingsService;
    private FallbackQueueService queueService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        leaseService = mock(PlayerLeaseService.class);
        settingsService = mock(PartySettingsQueryService.class);
        givenFallbackPlaylistUrl("https://www.youtube.com/playlist?list=" + PLAYLIST);
        queueService = mock(FallbackQueueService.class);
        when(queueService.getVersion(PARTY)).thenReturn("1a2b3c");
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DjPlayerLeaseController(leaseService, sessionHelper, settingsService, queueService)).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    private void givenFallbackPlaylistUrl(String url) {
        when(settingsService.getSettings(PARTY))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).fallbackPlaylistUrl(url).build());
    }

    @Test
    @DisplayName("200 with holder and free as JSON; the mode and the window id reach the service")
    void shouldReturnTheLeaseState() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM)).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holder").value(true))
                .andExpect(jsonPath("$.free").value(false));
    }

    @Test
    @DisplayName("a window that is told another one plays gets holder=false; free=true when nobody holds the lease")
    void shouldReturnNotHolderAndFree() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH)).thenReturn(new Status(false, true, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holder").value(false))
                .andExpect(jsonPath("$.free").value(true));
    }

    @Test
    @DisplayName("the answer names the party's current fallback playlist, so the window that plays can tell that it was replaced")
    void shouldNameTheCurrentFallbackPlaylist() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM)).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.fallbackPlaylistId").value(PLAYLIST));
    }

    @Test
    @DisplayName("a single video is named V:<id> — the id the tracks of it carry")
    void shouldNameASingleVideoPlaylist() throws Exception {
        givenFallbackPlaylistUrl("https://youtu.be/dQw4w9WgXcQ");
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM)).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.fallbackPlaylistId").value("V:dQw4w9WgXcQ"));
    }

    @Test
    @DisplayName("no fallback playlist (never set, or cleared by the DJ): the playlist id is null")
    void shouldReturnNullPlaylist_whenThereIsNone() throws Exception {
        givenFallbackPlaylistUrl(null);
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM)).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackPlaylistId").value((Object) null));
    }

    @Test
    @DisplayName("the answer carries the version of the up-next list, so that every window can tell when it changed elsewhere")
    void shouldCarryTheQueueVersion() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH)).thenReturn(new Status(false, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.queueVersion").value("1a2b3c"));
    }

    @Test
    @DisplayName("the command waiting for the window that plays is in its answer; for everyone else it is null")
    void shouldCarryTheCommand() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM)).thenReturn(new Status(true, false, PlayerCommand.NEXT));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("NEXT"));

        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH)).thenReturn(new Status(false, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.command").value((Object) null));
    }

    @Test
    @DisplayName("player-command: 204 when the command is waiting for the window that plays")
    void shouldAcceptACommand() throws Exception {
        when(leaseService.sendCommand(PARTY, PlayerCommand.NEXT)).thenReturn(true);

        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "NEXT")
                        .principal(token).session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(leaseService).sendCommand(PARTY, PlayerCommand.NEXT);
    }

    @Test
    @DisplayName("player-command: 409 when no window plays — nobody would carry it out")
    void shouldRefuseACommand_whenNobodyPlays() throws Exception {
        when(leaseService.sendCommand(PARTY, PlayerCommand.NEXT)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "NEXT")
                        .principal(token).session(session))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("player-command: 400 for an unknown command, POST-only, and a party owned by someone else is rejected")
    void shouldGuardTheCommandEndpoint() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "EXPLODE")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "NEXT")
                        .principal(token).session(session))
                .andExpect(status().isMethodNotAllowed());

        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/player-command")
                        .param("partyCode", "OTHER").param("command", "NEXT").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(leaseService);
    }

    @Test
    @DisplayName("TAKE_OVER is passed on as it is")
    void shouldPassTakeOverOn() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.TAKE_OVER)).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "TAKE_OVER").principal(token).session(session))
                .andExpect(status().isOk());

        verify(leaseService).report(PARTY, DEVICE, PlayerLeaseMode.TAKE_OVER);
    }

    @Test
    @DisplayName("400 for an unknown mode — nothing is changed")
    void shouldRejectAnUnknownMode() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "STEAL").principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(leaseService);
    }

    @Test
    @DisplayName("400 for a window id that is not a well-formed random id — nothing is changed")
    void shouldRejectAMalformedDeviceId() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", "<b>x</b>")
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(leaseService);
    }

    @Test
    @DisplayName("it changes state, so it is POST-only — GET is rejected")
    void shouldRejectGet() throws Exception {
        mockMvc.perform(get("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(leaseService);
    }

    @Test
    @DisplayName("validates that the party belongs to the logged-in DJ before answering (IDOR protection)")
    void shouldValidateOwnership() throws Exception {
        when(leaseService.report(any(), any(), any())).thenReturn(new Status(true, false, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                .param("mode", "CLAIM").principal(token).session(session));

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("a party owned by someone else is rejected: no lease is touched and no settings are read")
    void shouldNotTouchTheLease_whenPartyBelongsToSomeoneElse() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/player-lease")
                        .param("partyCode", "OTHER").param("deviceId", DEVICE).param("mode", "TAKE_OVER")
                        .principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(leaseService);
        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("release: 204 with an empty body, and the service is told which window let go")
    void shouldReleaseTheLease() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-lease/release").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .principal(token).session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(leaseService).release(PARTY, DEVICE);
    }

    @Test
    @DisplayName("release: 400 for a malformed window id, and a party owned by someone else is rejected")
    void shouldRefuseABadRelease() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-lease/release").param("partyCode", PARTY).param("deviceId", "x")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());

        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/player-lease/release")
                        .param("partyCode", "OTHER").param("deviceId", DEVICE).principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(leaseService);
    }
}
