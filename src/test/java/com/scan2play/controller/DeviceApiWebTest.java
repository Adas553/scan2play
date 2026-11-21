package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.service.DeckHistoryService;
import com.scan2play.service.DjDeviceService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.SiteCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The program on the DJ's computer (V78) through the real security: its two calls need no login and no CSRF token, and make no
 * session; the panel's side ({@code /dj/devices}) still needs the login.
 */
@WebMvcTest({DeviceApiController.class, DjDeviceController.class})
@Import(SecurityConfig.class)
class DeviceApiWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DjDeviceService devices;
    @MockitoBean
    private DeckHistoryService deckHistory;
    @MockitoBean
    private GuestRequestLimiter limiter;
    @MockitoBean
    private DjSessionHelper sessionHelper;
    @MockitoBean
    private SiteCounter siteCounter;

    private static void noSession(MvcResult result) {
        assertThat(result.getRequest().getSession(false)).as("a session was made").isNull();
    }

    @Test
    void thePairingAndTheSongs_needNoLoginNorCsrf_andMakeNoSession() throws Exception {
        when(devices.pair(anyString(), anyString(), any(), any())).thenReturn(new DjDeviceService.Pairing("tok", null));
        when(devices.device(eq("tok"), any())).thenReturn(Optional.of(new DjDeviceService.Paired(1, "AB123")));
        when(devices.mayCall(1)).thenReturn(true);
        when(deckHistory.markPlayed(anyString(), anyList(), any())).thenReturn(List.of());

        noSession(mockMvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\",\"name\":\"PC\"}")).andExpect(status().isOk()).andReturn());
        noSession(mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer tok").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plays\":[]}")).andExpect(status().isOk()).andReturn());
    }

    @Test
    void aBodyOfMegabytes_isRefusedBeforeItIsRead_toThePairingAndToTheSongsAlike() throws Exception {
        String big = "{\"plays\":[],\"x\":\"" + "a".repeat(200_000) + "\"}";
        mockMvc.perform(post("/api/device/plays").header("Authorization", "Bearer tok").contentType(MediaType.APPLICATION_JSON)
                .content(big)).andExpect(status().isPayloadTooLarge());
        mockMvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
        org.mockito.Mockito.verifyNoInteractions(devices, deckHistory);
    }

    @Test
    void thePanelsSide_needsTheLogin() throws Exception {
        int status = mockMvc.perform(post("/dj/devices/code").param("partyCode", "AB123")).andReturn().getResponse().getStatus();
        assertThat(status).as("refused without a login").isGreaterThanOrEqualTo(300);
    }
}
