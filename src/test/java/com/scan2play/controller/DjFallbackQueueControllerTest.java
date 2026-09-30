package com.scan2play.controller;

import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.MoveDirection;
import com.scan2play.service.FallbackQueueService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tests {@code GET /dj/dashboard/fallback-queue} — the "up next" list of the DJ's fallback playlist.
 */
class DjFallbackQueueControllerTest {

    private static final String PARTY = "ABC12";

    private FallbackQueueService queueService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        queueService = mock(FallbackQueueService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjFallbackQueueController(queueService, sessionHelper)).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    @Test
    @DisplayName("renders the queue fragment with the party's upcoming tracks")
    void shouldRenderTheFragmentWithTheQueue() throws Exception {
        FallbackQueueView queue = new FallbackQueueView(true, true, false, 12,
                List.of(new FallbackQueueView.Track(1L, "dQw4w9WgXcQ", "Never Gonna Give You Up")));
        when(queueService.getUpcoming(PARTY)).thenReturn(queue);

        mockMvc.perform(get("/dj/dashboard/fallback-queue").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/fallback-queue :: queue"))
                .andExpect(model().attribute("queue", queue));
    }

    @Test
    @DisplayName("the list comes with its version, the same one the lease reports carry, so its window knows it is up to date")
    void shouldSendTheVersionOfTheList() throws Exception {
        FallbackQueueView queue = new FallbackQueueView(true, true, false, 12,
                List.of(new FallbackQueueView.Track(1L, "dQw4w9WgXcQ", "Never Gonna Give You Up")));
        when(queueService.getUpcoming(PARTY)).thenReturn(queue);

        mockMvc.perform(get("/dj/dashboard/fallback-queue").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(header().string("X-Queue-Version", FallbackQueueService.versionOf(queue)));
    }

    @Test
    @DisplayName("validates that the party belongs to the logged-in DJ before answering (IDOR protection)")
    void shouldValidateOwnership() throws Exception {
        when(queueService.getUpcoming(PARTY)).thenReturn(FallbackQueueView.noPlaylist(true));

        mockMvc.perform(get("/dj/dashboard/fallback-queue").param("partyCode", PARTY).principal(token).session(session));

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("a party owned by someone else is rejected and its queue is never read")
    void shouldNotRevealTheQueueOfSomeoneElsesParty() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(get("/dj/dashboard/fallback-queue")
                        .param("partyCode", "OTHER").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("it only reads, so it is GET-only — POST is rejected")
    void shouldRejectPost() throws Exception {
        mockMvc.perform(post("/dj/dashboard/fallback-queue").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(queueService);
    }

    // ---- moving a track ----

    private org.springframework.test.web.servlet.ResultActions move(String direction) throws Exception {
        var request = post("/dj/dashboard/fallback-queue/move").param("partyCode", PARTY).param("trackId", "42").principal(token).session(session);
        if (direction != null) {
            request.param("direction", direction);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("each direction is passed on to the service; 204 No Content when the track was moved")
    void shouldMoveTheTrack() throws Exception {
        when(queueService.moveTrack(eq(PARTY), eq(42L), any(MoveDirection.class))).thenReturn(true);

        for (MoveDirection direction : MoveDirection.values()) {
            move(direction.name()).andExpect(status().isNoContent());
            verify(queueService).moveTrack(PARTY, 42L, direction);
        }
    }

    @Test
    @DisplayName("409 Conflict when the track can no longer be moved (the player just took it, or it is not in this playlist)")
    void shouldAnswerConflict_whenTheTrackCannotBeMoved() throws Exception {
        when(queueService.moveTrack(PARTY, 42L, MoveDirection.UP)).thenReturn(false);

        move("UP").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an unknown direction or a missing parameter is a 400 and nothing is moved")
    void shouldRejectBadRequests() throws Exception {
        move("SIDEWAYS").andExpect(status().isBadRequest());
        move(null).andExpect(status().isBadRequest());
        mockMvc.perform(post("/dj/dashboard/fallback-queue/move").param("partyCode", PARTY).param("trackId", "abc")
                .param("direction", "UP").principal(token).session(session)).andExpect(status().isBadRequest());

        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("moving validates party ownership first; someone else's party is rejected and nothing is moved")
    void shouldValidateOwnership_whenMoving() throws Exception {
        move("TOP");
        verify(sessionHelper).validateOwnership(PARTY, token, session);

        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());
        org.mockito.Mockito.clearInvocations(queueService);

        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/fallback-queue/move")
                .param("partyCode", "OTHER").param("trackId", "42").param("direction", "TOP").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("moving needs POST — a GET is not accepted")
    void shouldRejectGetForMoving() throws Exception {
        mockMvc.perform(get("/dj/dashboard/fallback-queue/move").param("partyCode", PARTY).param("trackId", "42")
                .param("direction", "UP").principal(token).session(session)).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(queueService);
    }

    // ---- dragging a track to a new place ----

    private org.springframework.test.web.servlet.ResultActions place(String trackId, String beforeTrackId) throws Exception {
        var request = post("/dj/dashboard/fallback-queue/place").param("partyCode", PARTY).principal(token).session(session);
        if (trackId != null) {
            request.param("trackId", trackId);
        }
        if (beforeTrackId != null) {
            request.param("beforeTrackId", beforeTrackId);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("the dragged track and the one it now precedes are passed on; 204 No Content when it was placed")
    void shouldPlaceTheTrack() throws Exception {
        when(queueService.placeTrack(PARTY, 42L, 7L)).thenReturn(true);

        place("42", "7").andExpect(status().isNoContent());

        verify(queueService).placeTrack(PARTY, 42L, 7L);
    }

    @Test
    @DisplayName("without beforeTrackId the track is dropped at the end of the queue")
    void shouldPlaceTheTrackAtTheEnd_whenThereIsNoTarget() throws Exception {
        when(queueService.placeTrack(PARTY, 42L, null)).thenReturn(true);

        place("42", null).andExpect(status().isNoContent());

        verify(queueService).placeTrack(PARTY, 42L, null);
    }

    @Test
    @DisplayName("409 Conflict when a track can no longer be moved (the player just took it, or it is not in this playlist)")
    void shouldAnswerConflict_whenTheDropIsRefused() throws Exception {
        when(queueService.placeTrack(PARTY, 42L, 7L)).thenReturn(false);

        place("42", "7").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a missing or malformed track id is a 400 and nothing is moved")
    void shouldRejectBadPlaceRequests() throws Exception {
        place(null, "7").andExpect(status().isBadRequest());
        place("abc", "7").andExpect(status().isBadRequest());
        place("42", "xyz").andExpect(status().isBadRequest());

        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("a drop validates party ownership first; someone else's party is rejected and nothing is moved")
    void shouldValidateOwnership_whenPlacing() throws Exception {
        place("42", "7");
        verify(sessionHelper).validateOwnership(PARTY, token, session);

        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());
        org.mockito.Mockito.clearInvocations(queueService);

        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/fallback-queue/place")
                .param("partyCode", "OTHER").param("trackId", "42").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("dropping needs POST - a GET is not accepted")
    void shouldRejectGetForPlacing() throws Exception {
        mockMvc.perform(get("/dj/dashboard/fallback-queue/place").param("partyCode", PARTY).param("trackId", "42")
                .principal(token).session(session)).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(queueService);
    }

    // ---- skipping a track (for this round) ----

    @Test
    @DisplayName("skip: 204 when the track was skipped — the ownership of the party is checked first")
    void skip_shouldAnswerNoContent() throws Exception {
        when(queueService.skipTrack(PARTY, 42L)).thenReturn(true);

        mockMvc.perform(post("/dj/dashboard/fallback-queue/skip").param("partyCode", PARTY).param("trackId", "42")
                        .principal(token).session(session))
                .andExpect(status().isNoContent());

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("skip: 409 when the track can no longer be skipped (the player took it, or it is not of this party's current playlist)")
    void skip_shouldAnswerConflict_whenTheTrackCannotBeSkipped() throws Exception {
        when(queueService.skipTrack(PARTY, 42L)).thenReturn(false);

        mockMvc.perform(post("/dj/dashboard/fallback-queue/skip").param("partyCode", PARTY).param("trackId", "42")
                        .principal(token).session(session))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("skip: a missing or non-numeric track id is a 400, and nothing is skipped")
    void skip_shouldRejectABadTrackId() throws Exception {
        mockMvc.perform(post("/dj/dashboard/fallback-queue/skip").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/dj/dashboard/fallback-queue/skip").param("partyCode", PARTY).param("trackId", "abc")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("skip: a party owned by someone else is rejected and nothing is skipped (IDOR protection)")
    void skip_shouldRejectSomeoneElsesParty() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/fallback-queue/skip")
                        .param("partyCode", "OTHER").param("trackId", "42").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("skip is POST-only — a GET is rejected")
    void skip_shouldRejectGet() throws Exception {
        mockMvc.perform(get("/dj/dashboard/fallback-queue/skip").param("partyCode", PARTY).param("trackId", "42")
                        .principal(token).session(session))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(queueService);
    }
}
