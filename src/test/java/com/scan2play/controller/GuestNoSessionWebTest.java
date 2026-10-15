package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The review of 2026-10-14, W3: every {@code GET /p/{anything}} made a session — a row in PostgreSQL ({@code spring_session}) for 30
 * minutes — because the controller asked for an {@code HttpSession}; a script without cookies could fill the database with
 * random codes. A code that is no party, and a party that has ended, now make none. The page of an open party still needs one: its
 * form carries the CSRF token, which lives in the session.
 */
@WebMvcTest(GuestController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class GuestNoSessionWebTest {

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
        doThrow(new IllegalArgumentException("no such party")).when(partySettingsQueryService).getSettings(anyString());
        doReturn(PartySettingsEntity.builder().partyCode("ENDED").active(false).build())
                .when(partySettingsQueryService).getSettings("ENDED");
    }

    private static void noSession(MvcResult result) {
        assertThat(result.getRequest().getSession(false)).as("a session was made").isNull();
    }

    @Test
    void aCodeThatIsNoParty_goesHome_withoutASession() throws Exception {
        noSession(mockMvc.perform(get("/p/X7Q2Z")).andExpect(status().is3xxRedirection()).andReturn());
    }

    @Test
    void theListOfACodeThatIsNoParty_isA404_withoutASession() throws Exception {
        noSession(mockMvc.perform(get("/p/X7Q2Z/queue")).andExpect(status().isNotFound()).andReturn());
        noSession(mockMvc.perform(get("/p/X7Q2Z/queue/more")).andExpect(status().isNotFound()).andReturn());
    }

    @Test
    void aPartyThatHasEnded_saysSo_withoutASession() throws Exception {
        noSession(mockMvc.perform(get("/p/ENDED")).andExpect(status().isOk()).andReturn());
    }
}
