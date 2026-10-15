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

    /**
     * The guest's page was open longer than the session lives (30 minutes): the form's CSRF token is gone with it. The request comes
     * back to the party's page with what the guest typed and a note, not to the error page "403" with the song lost (review
     * 2026-10-14).
     */
    @Test
    void aRequestFromAPageOpenTooLong_comesBackToTheParty_withItsText() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/p/ABC12/request")
                        .param("songName", "Wilki - Baśka").param("_csrf", "a-token-of-an-old-session")
                        .header("Accept-Language", "pl-PL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/p/ABC12"))
                .andReturn();

        // what the party's page gets on its next load (the flash of the new session)
        @SuppressWarnings("unchecked")
        java.util.List<org.springframework.web.servlet.FlashMap> flashes = (java.util.List<org.springframework.web.servlet.FlashMap>)
                result.getRequest().getSession().getAttribute(
                        org.springframework.web.servlet.support.SessionFlashMapManager.class.getName() + ".FLASH_MAPS");
        assertThat(flashes).singleElement().satisfies(flash -> {
            assertThat(flash.getTargetRequestPath()).isEqualTo("/p/ABC12");
            assertThat(flash.get("lastRequest")).isEqualTo("Wilki - Baśka");
            assertThat((String) flash.get("errorMessage")).contains("wyślij prośbę jeszcze raz");
        });

        // anything else without its token is refused as before
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/p/ABC12/vote")
                .param("id", "7")).andExpect(status().isForbidden());
    }

    @Test
    void aPartyThatHasEnded_saysSo_withoutASession() throws Exception {
        noSession(mockMvc.perform(get("/p/ENDED")).andExpect(status().isOk()).andReturn());
    }
}
