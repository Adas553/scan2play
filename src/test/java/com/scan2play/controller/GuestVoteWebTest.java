package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SongEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The guest's 👍 through the real web layer (security, CSRF, the fragment): a guest needs no login, the list's forms carry the
 * CSRF token, and a vote without it is refused before anything is counted.
 */
@WebMvcTest(GuestController.class)
@Import(SecurityConfig.class)
class GuestVoteWebTest {

    private static final String PARTY = "ABC12";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SongEvaluationService songEvaluationService;
    @MockitoBean
    private GuestQueueService guestQueueService;
    @MockitoBean
    private PartySettingsQueryService partySettingsQueryService;
    @MockitoBean
    private GuestSessionService guestSessionService;
    @MockitoBean
    private GuestRequestLimiter guestRequestLimiter;
    @MockitoBean
    private GuestVoteService guestVoteService;

    @BeforeEach
    void setUp() {
        when(partySettingsQueryService.getSettings(PARTY))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).active(true).build());
        SongRequestEntity song = SongRequestEntity.builder().id(7L).partyCode(PARTY).songName("sanah - Szampan").votes(2).build();
        when(guestQueueService.view(eq(PARTY), any(), any()))
                .thenReturn(new GuestQueueService.GuestQueue(List.of(song), List.of(song), Set.of(), null, 0, Set.of()));
        when(guestRequestLimiter.clientIp(any())).thenReturn("203.0.113.7");
        when(guestRequestLimiter.tryAcquireVote(anyString(), anyString())).thenReturn(Optional.empty());
        when(guestVoteService.vote(any(), eq(PARTY), eq(7L))).thenReturn(GuestVoteService.Result.COUNTED);
    }

    @Test
    void theListsVoteForms_carryTheCsrfToken() throws Exception {
        mockMvc.perform(get("/p/" + PARTY + "/queue"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/p/ABC12/vote\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    /** The token of the list's form, as the browser sends it back (the guest's session). */
    private String csrfToken(MockHttpSession session) throws Exception {
        String html = mockMvc.perform(get("/p/" + PARTY + "/queue").session(session)).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        org.assertj.core.api.Assertions.assertThat(token.find()).as("the form's CSRF field").isTrue();
        return token.group(1);
    }

    @Test
    void aVote_needsNoLogin_andComesBackAsTheList() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/p/" + PARTY + "/vote").session(session).param("id", "7").param("on", "true")
                        .param("_csrf", csrfToken(session)).header("X-Requested-With", "fetch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sanah - Szampan")))
                .andExpect(content().string(containsString("s2p-vote-btn")));
        verify(guestVoteService).vote(any(), eq(PARTY), eq(7L));
    }

    @Test
    void theRestOfTheList_isServedWithoutLogin() throws Exception {
        mockMvc.perform(get("/p/" + PARTY + "/queue/more"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-song-id=\"7\"")));
    }

    @Test
    void aVoteWithoutTheCsrfToken_isRefused_andNothingIsCounted() throws Exception {
        mockMvc.perform(post("/p/" + PARTY + "/vote").param("id", "7").header("X-Requested-With", "fetch"))
                .andExpect(status().isForbidden());
        verify(guestVoteService, never()).vote(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
