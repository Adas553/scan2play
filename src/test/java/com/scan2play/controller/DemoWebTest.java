package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.DemoParty;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SiteCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo through the real security chain: its pages are public for a GET — no login, no redirect —, nothing else is; its poll
 * answers as the panel's does (the same headers, an ETag, a 304 when nothing changed).
 */
@WebMvcTest(DemoController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class DemoWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DjDashboardController panel;
    @MockitoBean
    private PartySettingsQueryService partySettingsQueryService;
    @MockitoBean
    private DjService djService;
    @MockitoBean
    private GuestSessionService guestSessionService;
    @MockitoBean
    private SiteCounter siteCounter;

    @BeforeEach
    void setUp() {
        when(partySettingsQueryService.getSettings(DemoParty.CODE)).thenReturn(PartySettingsEntity.builder().partyCode(DemoParty.CODE)
                .ownerId("scan2play-demo").active(true).build());
        when(djService.getWaiting(DemoParty.CODE)).thenReturn(List.of());
        when(guestSessionService.myRequestIds(any(), any())).thenReturn(Set.of());
    }

    @Test
    void thePoll_isPublic_andAnswersAsThePanelsDoes() throws Exception {
        // nothing waiting: the ETag of the empty list — the poll that has it gets a 304, without a login
        mockMvc.perform(get("/demo/updates").param("partyCode", DemoParty.CODE).header("If-None-Match", "\"demo-0\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"demo-0\""))
                .andExpect(header().string("X-Panel-Access", "owner"))
                .andExpect(header().string("X-Party-Active", "true"))
                .andExpect(header().string("X-Guest-Limits", "none"));
        verify(panel, never()).demoQueue(any(), any(), anyList());
    }

    /** Another visitor's request (unchecked, not this session's) is not in what this visitor's poll renders. */
    @Test
    void thePoll_showsTheExamplesAndTheVisitorsOwn_only() throws Exception {
        SongRequestEntity example = SongRequestEntity.builder().id(1L).songName("Wilki - Baśka").checked(true).requestedAt(Instant.now()).build();
        SongRequestEntity theirs = SongRequestEntity.builder().id(98L).songName("someone else").checked(false).requestedAt(Instant.now()).build();
        when(djService.getWaiting(DemoParty.CODE)).thenReturn(List.of(example, theirs));

        mockMvc.perform(get("/demo/updates").param("partyCode", DemoParty.CODE));

        verify(panel).demoQueue(any(), any(), eq(List.of(example)));
    }

    @Test
    void nothingButAGet_isPublic() throws Exception {
        mockMvc.perform(post("/demo/updates")).andExpect(status().isForbidden());
        mockMvc.perform(post("/demo")).andExpect(status().isForbidden());
    }
}
