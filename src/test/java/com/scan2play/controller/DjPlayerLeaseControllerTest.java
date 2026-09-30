package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
import com.scan2play.model.PlayerCommand;
import com.scan2play.model.PlayerLeaseMode;
import com.scan2play.service.FallbackQueueService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    private PlayHistoryService historyService;
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
        historyService = mock(PlayHistoryService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DjPlayerLeaseController(leaseService, sessionHelper, settingsService, queueService, historyService))
                .build();
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
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holder").value(true))
                .andExpect(jsonPath("$.free").value(false));
    }

    @Test
    @DisplayName("a window that is told another one plays gets holder=false; free=true when nobody holds the lease")
    void shouldReturnNotHolderAndFree() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH, null)).thenReturn(new Status(false, true, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holder").value(false))
                .andExpect(jsonPath("$.free").value(true));
    }

    @Test
    @DisplayName("the answer names the party's current fallback playlist, so the window that plays can tell that it was replaced")
    void shouldNameTheCurrentFallbackPlaylist() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.fallbackPlaylistId").value(PLAYLIST));
    }

    @Test
    @DisplayName("a single video is named V:<id> — the id the tracks of it carry")
    void shouldNameASingleVideoPlaylist() throws Exception {
        givenFallbackPlaylistUrl("https://youtu.be/dQw4w9WgXcQ");
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.fallbackPlaylistId").value("V:dQw4w9WgXcQ"));
    }

    @Test
    @DisplayName("no fallback playlist (never set, or cleared by the DJ): the playlist id is null")
    void shouldReturnNullPlaylist_whenThereIsNone() throws Exception {
        givenFallbackPlaylistUrl(null);
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackPlaylistId").value((Object) null));
    }

    @Test
    @DisplayName("the answer carries the version of the up-next list, so that every window can tell when it changed elsewhere")
    void shouldCarryTheQueueVersion() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH, null)).thenReturn(new Status(false, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.queueVersion").value("1a2b3c"));
    }

    @Test
    @DisplayName("the command waiting for the window that plays is in its answer; for everyone else it is null")
    void shouldCarryTheCommand() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.NEXT, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("NEXT"));

        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH, null)).thenReturn(new Status(false, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.command").value((Object) null));
    }

    @Test
    @DisplayName("the PREVIOUS command reaches the window that plays in its answer, like NEXT")
    void shouldCarryThePreviousCommand() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.PREVIOUS, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("PREVIOUS"));
    }

    // ---- pause / resume: the window that plays says whether its player makes sound ----

    @Test
    @DisplayName("what the window that plays says about its player (playing=true) reaches the service, and every answer tells it back")
    void shouldPassOnAndReturnWhetherThePlayerMakesSound() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, true)).thenReturn(new Status(true, false, null, true));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").param("playing", "true").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playing").value(true));

        verify(leaseService).report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, true);
    }

    @Test
    @DisplayName("a paused player is reported as playing=false and comes back as false")
    void shouldReturnAPausedPlayer() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH, null)).thenReturn(new Status(false, false, null, false));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.playing").value(false));
    }

    @Test
    @DisplayName("nothing said yet (or nobody plays): playing is null, not false")
    void shouldReturnNullWhenNothingIsKnownAboutThePlayer() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.WATCH, null)).thenReturn(new Status(false, true, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "WATCH").principal(token).session(session))
                .andExpect(jsonPath("$.playing").value((Object) null));
    }

    @Test
    @DisplayName("a playing value that is not true or false is a bad request — nothing is changed")
    void shouldRejectAPlayingValueThatIsNotABoolean() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").param("playing", "maybe").principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(leaseService);
    }

    @Test
    @DisplayName("the PAUSE and RESUME commands reach the window that plays in its answer")
    void shouldCarryThePauseAndResumeCommands() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.PAUSE, null));
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("PAUSE"));

        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.RESUME, null));
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("RESUME"));
    }

    @Test
    @DisplayName("player-command accepts PAUSE and RESUME: 204 when they are waiting for the window that plays, 409 when nobody plays")
    void shouldAcceptThePauseAndResumeCommands() throws Exception {
        when(leaseService.sendCommand(PARTY, PlayerCommand.PAUSE)).thenReturn(true);
        when(leaseService.sendCommand(PARTY, PlayerCommand.RESUME)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "PAUSE")
                        .principal(token).session(session))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "RESUME")
                        .principal(token).session(session))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("player-command accepts PREVIOUS: 204 when it is waiting for the window that plays")
    void shouldAcceptThePreviousCommand() throws Exception {
        when(leaseService.sendCommand(PARTY, PlayerCommand.PREVIOUS)).thenReturn(true);

        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "PREVIOUS")
                        .principal(token).session(session))
                .andExpect(status().isNoContent());

        verify(leaseService).sendCommand(PARTY, PlayerCommand.PREVIOUS);
    }

    @Test
    @DisplayName("player-command accepts PREVIOUS_TRACK and RESTART — the two buttons of a window that does not play: 204 when waiting, 409 when nobody plays")
    void shouldAcceptThePreviousTrackAndRestartCommands() throws Exception {
        when(leaseService.sendCommand(PARTY, PlayerCommand.PREVIOUS_TRACK)).thenReturn(true);
        when(leaseService.sendCommand(PARTY, PlayerCommand.RESTART)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "PREVIOUS_TRACK")
                        .principal(token).session(session))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "RESTART")
                        .principal(token).session(session))
                .andExpect(status().isConflict());

        verify(leaseService).sendCommand(PARTY, PlayerCommand.PREVIOUS_TRACK);
        verify(leaseService).sendCommand(PARTY, PlayerCommand.RESTART);
    }

    @Test
    @DisplayName("the PREVIOUS_TRACK and RESTART commands reach the window that plays in its answer, by their names — the script looks for them")
    void shouldCarryThePreviousTrackAndRestartCommands() throws Exception {
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.PREVIOUS_TRACK, null));
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("PREVIOUS_TRACK"));

        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.CLAIM, null)).thenReturn(new Status(true, false, PlayerCommand.RESTART, null));
        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "CLAIM").principal(token).session(session))
                .andExpect(jsonPath("$.command").value("RESTART"));
    }

    @Test
    @DisplayName("a command that does not exist is a bad request, and nothing is sent to the window that plays")
    void shouldRejectAnUnknownCommand() throws Exception {
        mockMvc.perform(post("/dj/dashboard/player-command").param("partyCode", PARTY).param("command", "REWIND")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());

        verify(leaseService, org.mockito.Mockito.never()).sendCommand(any(), any());
    }

    // ---- recent-tracks: what "previous" walks back along ----

    private static HistoryEntry entry(Source source, long id, String videoId, String title) {
        return new HistoryEntry(source, id, LocalDateTime.of(2026, 9, 29, 20, 0), title,
                "https://www.youtube.com/watch?v=" + videoId, videoId, null, "played", null, null);
    }

    @Test
    @DisplayName("recent-tracks: the tracks that played, newest first, as JSON with the key the player script looks for")
    void shouldListTheRecentTracks() throws Exception {
        when(historyService.getRecentlyPlayed(PARTY, 30)).thenReturn(List.of(
                entry(Source.BACKGROUND, 7, "dQw4w9WgXcQ", "Never Gonna Give You Up"),
                entry(Source.GUEST, 42, "hTWKbfoikeg", "Guest song")));

        mockMvc.perform(get("/dj/dashboard/recent-tracks").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("B:7"))
                .andExpect(jsonPath("$[0].source").value("BACKGROUND"))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].videoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$[0].title").value("Never Gonna Give You Up"))
                .andExpect(jsonPath("$[1].key").value("G:42"))
                .andExpect(jsonPath("$[1].source").value("GUEST"))
                .andExpect(jsonPath("$[1].videoId").value("hTWKbfoikeg"));
    }

    @Test
    @DisplayName("recent-tracks: each entry says how many seconds ago it started, by the server's clock (for the resume after a reload)")
    void shouldSayHowLongAgoEachRecentTrackStarted() throws Exception {
        HistoryEntry justNow = new HistoryEntry(Source.BACKGROUND, 7L, LocalDateTime.now().minusSeconds(90), "Now",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", null, "played", null, null);
        when(historyService.getRecentlyPlayed(PARTY, 30)).thenReturn(List.of(justNow));

        String json = mockMvc.perform(get("/dj/dashboard/recent-tracks").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long secondsAgo = com.jayway.jsonpath.JsonPath.<Number>read(json, "$[0].secondsAgo").longValue();
        assertThat(secondsAgo).isBetween(90L, 120L);
    }

    @Test
    @DisplayName("recent-tracks: nothing played yet is an empty list, not an error")
    void shouldReturnAnEmptyListOfRecentTracks() throws Exception {
        when(historyService.getRecentlyPlayed(PARTY, 30)).thenReturn(List.of());

        mockMvc.perform(get("/dj/dashboard/recent-tracks").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("recent-tracks validates that the party belongs to the logged-in DJ; someone else's party reads nothing")
    void shouldGuardTheRecentTracks() throws Exception {
        when(historyService.getRecentlyPlayed(PARTY, 30)).thenReturn(List.of());
        mockMvc.perform(get("/dj/dashboard/recent-tracks").param("partyCode", PARTY).principal(token).session(session));
        verify(sessionHelper).validateOwnership(PARTY, token, session);

        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(get("/dj/dashboard/recent-tracks")
                        .param("partyCode", "OTHER").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verify(historyService, org.mockito.Mockito.never()).getRecentlyPlayed(org.mockito.ArgumentMatchers.eq("OTHER"), org.mockito.ArgumentMatchers.anyInt());
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
        when(leaseService.report(PARTY, DEVICE, PlayerLeaseMode.TAKE_OVER, null)).thenReturn(new Status(true, false, null, null));

        mockMvc.perform(post("/dj/dashboard/player-lease").param("partyCode", PARTY).param("deviceId", DEVICE)
                        .param("mode", "TAKE_OVER").principal(token).session(session))
                .andExpect(status().isOk());

        verify(leaseService).report(PARTY, DEVICE, PlayerLeaseMode.TAKE_OVER, null);
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
        when(leaseService.report(any(), any(), any(), any())).thenReturn(new Status(true, false, null, null));

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
