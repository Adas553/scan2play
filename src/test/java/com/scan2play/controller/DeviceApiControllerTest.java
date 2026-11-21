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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceApiController(devices, deckHistory, new GuestRequestLimiter(5, 10, 100, "")))
                .build();
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
        when(deckHistory.markPlayed(eq("AB123"), anyList(), any())).thenReturn(List.of("Sanah - Szampan"));

        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plays\":[{\"at\":1795300000,\"artist\":\"Sanah\",\"title\":\"Szampan\"}]}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"marked\":[\"Sanah - Szampan\"]}"));
    }

    @Test
    void noToken_oraRevokedOne_is401_andNothingIsMarked() throws Exception {
        mockMvc.perform(post("/api/device/plays").contentType(MediaType.APPLICATION_JSON).content("{\"plays\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer revoked").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isUnauthorized());
        verify(deckHistory, never()).markPlayed(anyString(), anyList(), any());
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
