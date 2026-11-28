package com.scan2play.controller;

import com.scan2play.service.DeckHistoryService;
import com.scan2play.service.DjDeviceService;
import com.scan2play.service.GuestRequestLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The program on the DJ's computer (V78): its pairing and its songs, by its own token. */
class DeviceApiControllerTest {

    private final DjDeviceService devices = mock(DjDeviceService.class);
    private final DeckHistoryService deckHistory = mock(DeckHistoryService.class);
    private final com.scan2play.service.DeckSetService deckSet = mock(com.scan2play.service.DeckSetService.class);
    private final com.scan2play.service.PartySettingsQueryService parties = mock(com.scan2play.service.PartySettingsQueryService.class);
    private MockMvc mockMvc;

    private MockMvc mvc(com.scan2play.service.PlanService plans) {
        return MockMvcBuilders.standaloneSetup(new DeviceApiController(devices, deckHistory, deckSet, new GuestRequestLimiter(5, 10, 100, ""),
                parties, plans)).build();
    }

    @BeforeEach
    void setUp() {
        when(parties.getSettings("AB123")).thenReturn(com.scan2play.entity.PartySettingsEntity.builder().partyCode("AB123")
                .ownerId("dj-ab").active(true).build());
        mockMvc = mvc(com.scan2play.service.Plans.enforced("dj-ab"));
        when(devices.device(eq("good"), any())).thenReturn(Optional.of(new DjDeviceService.Paired(7, "AB123")));
        when(devices.device(eq("revoked"), any())).thenReturn(Optional.empty());
        when(devices.mayCall(7)).thenReturn(true);
    }

    @Test
    void aGoodCode_givesTheToken_aWrongOneA400_tooManyA429() throws Exception {
        when(devices.pair(eq("123456"), anyString(), anyString(), any())).thenReturn(new DjDeviceService.Pairing("tok", null));
        when(devices.pair(eq("000000"), anyString(), anyString(), any()))
                .thenReturn(new DjDeviceService.Pairing(null, DjDeviceService.PairRefusal.WRONG_CODE));
        when(devices.pair(eq("999999"), anyString(), anyString(), any()))
                .thenReturn(new DjDeviceService.Pairing(null, DjDeviceService.PairRefusal.TOO_MANY_TRIES));

        mockMvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\",\"name\":\"PC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("tok"));
        mockMvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\",\"name\":\"PC\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("code"));
        mockMvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"999999\",\"name\":\"PC\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theSongs_markTheTokensParty() throws Exception {
        when(deckHistory.markPlayed(eq("AB123"), anyList(), any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of("Sanah - Szampan"));

        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plays\":[{\"at\":1795300000,\"artist\":\"Sanah\",\"title\":\"Szampan\"}]}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"marked\":[\"Sanah - Szampan\"]}"));
    }

    /** V83: every song of the call goes to the night's set too, on the server's clock and with its program; a failure there is no 500. */
    @Test
    void everySong_isKeptForTheSet_withItsProgram_andAFailureThereFailsNothing() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        when(deckHistory.markPlayed(eq("AB123"), anyList(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of("Sanah - Szampan"));
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plays\":[{\"at\":" + (now - 480) + ",\"artist\":\"Sanah\",\"title\":\"Szampan\",\"program\":\"Serato\"},"
                                + "{\"at\":" + (now - 240) + ",\"artist\":\"Kult\",\"title\":\"Arahja\"}],\"sentAt\":" + (now - 60) + "}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.scan2play.service.DeckSetService.SetSong>> set = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(deckSet).keep(eq(7L), eq("AB123"), set.capture(), any());
        assertThat(set.getValue()).extracting(com.scan2play.service.DeckSetService.SetSong::name, com.scan2play.service.DeckSetService.SetSong::program)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Sanah - Szampan", "Serato"), org.assertj.core.groups.Tuple.tuple("Kult - Arahja", null));
        assertThat(set.getValue().getFirst().startedAt()).as("moved by the clock's minute").isBetween(
                java.time.Instant.ofEpochSecond(now - 422), java.time.Instant.ofEpochSecond(now - 418));

        when(deckSet.keep(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyList(), any())).thenThrow(new IllegalStateException("db"));
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plays\":[{\"artist\":\"Sanah\",\"title\":\"Szampan\"}]}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"marked\":[\"Sanah - Szampan\"]}"));
        org.mockito.Mockito.clearInvocations(deckSet);
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isOk());
        verify(deckSet, never()).keep(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyList(), any());
    }

    @Test
    void aClockEightMinutesSlow_isCorrectedBythe_sentAt_ofTheCall() {
        java.time.Instant now = java.time.Instant.parse("2026-11-21T21:00:00Z");
        long slow = now.getEpochSecond() - 480;
        List<DeviceApiController.DeckPlay> plays = List.of(new DeviceApiController.DeckPlay(slow - 60, "Sanah", "Szampan"),
                new DeviceApiController.DeckPlay(null, "A", "B"));

        List<DeckHistoryService.Play> songs = DeviceApiController.songsOf(plays, slow, now);

        assertThat(songs.get(0).at()).as("a minute before the call, on the server's clock").isEqualTo(now.minusSeconds(60));
        assertThat(songs.get(1).at()).as("no time stays none").isNull();
        assertThat(DeviceApiController.songsOf(plays, null, now).get(0).at()).as("no sentAt: as written").isEqualTo(java.time.Instant.ofEpochSecond(slow - 60));
        assertThat(DeviceApiController.songsOf(plays, -5L, now).get(0).at()).as("a nonsense sentAt: as written").isEqualTo(java.time.Instant.ofEpochSecond(slow - 60));
    }

    @Test
    void theSongsOfAPartyWithoutPro_are403Plan_andNothingIsMarked_theTokenStays() throws Exception {
        mvc(com.scan2play.service.Plans.enforced()).perform(post("/api/device/plays").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plays\":[{\"at\":1795300000,\"artist\":\"Sanah\",\"title\":\"Szampan\"}]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("plan"));
        verify(deckHistory, never()).markPlayed(anyString(), anyList(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(devices, never()).remove(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * V81: the newest song's start (on the server's clock) and its program noted for the panel's line — never the song —, with the
     * oldest one's for the run of songs (V82); an empty call ("still paired?") and a history of yesterday note nothing.
     */
    @Test
    void theNewestSong_isNotedWithItsProgram_anEmptyCallNotesNothing() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[{\"at\":" + (now - 300) + ",\"artist\":\"Sanah\",\"title\":\"Szampan\",\"program\":\"Serato\"},"
                        + "{\"at\":" + (now - 60) + ",\"artist\":\"Kult\",\"title\":\"Arahja\",\"program\":\"VirtualDJ\"},"
                        + "{\"at\":" + (now - 120) + ",\"artist\":\"A\",\"title\":\"B\"}],\"sentAt\":" + now + "}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<java.time.Instant> from = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.ArgumentCaptor<java.time.Instant> at = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(devices).notePlay(eq(7L), from.capture(), at.capture(), eq("VirtualDJ"));
        assertThat(at.getValue()).isBetween(java.time.Instant.ofEpochSecond(now - 62), java.time.Instant.ofEpochSecond(now - 58));
        assertThat(from.getValue()).isBetween(java.time.Instant.ofEpochSecond(now - 302), java.time.Instant.ofEpochSecond(now - 298));

        org.mockito.Mockito.clearInvocations(devices);
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[{\"at\":" + (now - 86_400) + ",\"artist\":\"Kult\",\"title\":\"Arahja\"}],\"sentAt\":" + now + "}"))
                .andExpect(status().isOk());
        verify(devices, never()).notePlay(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    /** A song without a time, or "later" than now (a computer's clock ahead), is taken as now; yesterday's left out of the span. */
    @Test
    void theSpan_takesASongWithoutATimeOrFromTheFutureAsNow_andLeavesYesterdayOut() {
        java.time.Instant now = java.time.Instant.parse("2026-11-28T21:00:00Z");
        var span = DeviceApiController.span(List.of(
                new com.scan2play.service.DeckHistoryService.Play(now.minusSeconds(86_400), "Kult", "Arahja"),
                new com.scan2play.service.DeckHistoryService.Play(now.minusSeconds(600), "Sanah", "Szampan"),
                new com.scan2play.service.DeckHistoryService.Play(now.plusSeconds(3600), "A", "B")), now).orElseThrow();
        assertThat(span.oldest()).isEqualTo(now.minusSeconds(600));
        assertThat(span.newest()).isEqualTo(now);
        assertThat(DeviceApiController.span(List.of(new com.scan2play.service.DeckHistoryService.Play(null, "A", "B")), now)
                .orElseThrow()).isEqualTo(new DeviceApiController.Span(now, now));
        assertThat(DeviceApiController.span(List.of(), now)).isEmpty();
    }

    @Test
    void aProgramNameTooLong_isRefusedAsAnyField() {
        assertThat(DeviceApiController.validPlays(List.of(new DeviceApiController.DeckPlay(1L, "A", "B", "x".repeat(501))))).isFalse();
        assertThat(DeviceApiController.validPlays(List.of(new DeviceApiController.DeckPlay(1L, "A", "B", "VirtualDJ")))).isTrue();
    }

    @Test
    void theSendersClock_givesTheShorterSlack() throws Exception {
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[{\"at\":1795300000,\"artist\":\"Sanah\",\"title\":\"Szampan\"}],\"sentAt\":1795300010}"))
                .andExpect(status().isOk());
        verify(deckHistory).markPlayed(eq("AB123"), anyList(), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void noToken_oraRevokedOne_is401_andNothingIsMarked() throws Exception {
        mockMvc.perform(post("/api/device/plays").contentType(MediaType.APPLICATION_JSON).content("{\"plays\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer revoked").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isUnauthorized());
        verify(deckHistory, never()).markPlayed(anyString(), anyList(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * The program's own "Odłącz ten komputer": its row goes by its token (204), in any plan — without Pro the panel shows no list to
     * take it away from; a token unknown 401. No plan is asked.
     */
    @Test
    void theProgramsOwnOdlacz_removesItsComputer_byItsToken_inAnyPlan() throws Exception {
        MockMvc free = mvc(com.scan2play.service.Plans.enforced());
        when(devices.removeByToken("good")).thenReturn(true);

        free.perform(post("/api/device/unpair").header("Authorization", "Bearer good")).andExpect(status().isNoContent());
        free.perform(post("/api/device/unpair").header("Authorization", "Bearer revoked")).andExpect(status().isUnauthorized());
        free.perform(post("/api/device/unpair")).andExpect(status().isUnauthorized());
        verify(devices).removeByToken("good");
    }

    @Test
    void tooManyCalls_is429_tooManySongs400() throws Exception {
        String many = "{\"plays\":[" + String.join(",", java.util.Collections.nCopies(21, "{\"artist\":\"A\",\"title\":\"B\"}")) + "]}";
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content(many)).andExpect(status().isBadRequest());

        when(devices.mayCall(7)).thenReturn(false);
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isTooManyRequests());
    }
}
