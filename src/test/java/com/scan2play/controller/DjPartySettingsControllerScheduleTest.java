package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySchedules;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Godziny imprezy" (V55) and the manual open and close against them: whoever may open and close the party (OPEN_CLOSE) sets a day and
 * a time for each — back to the card with what was saved or why not —, a "✕" takes one away; "Zakończ" / "Wznów" leave the moment they
 * did not make pointless. On Friday 2026-10-30 at noon.
 */
class DjPartySettingsControllerScheduleTest {

    private static final String PARTY = "ABC12";
    private static final String BACK = "/dj/settings?party=ABC12#partyHoursCard";

    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private PartySettingsEntity row;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PartySettingsCommandService settingsService = mock(PartySettingsCommandService.class);
        row = PartySettingsEntity.builder().partyCode(PARTY).active(true).build();
        when(settingsService.updateSettings(eq(PARTY), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(row);
            return row;
        });
        sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(eq(PARTY), eq(StaffPermission.OPEN_CLOSE), any(), any())).thenReturn(row);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(settingsService, mock(AccountDeletionService.class),
                mock(com.scan2play.service.PartyCodeService.class), sessionHelper, com.scan2play.service.Plans.off(),
                PartySchedules.of(settingsService, PartySchedules.FRIDAY_NOON))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.param("partyCode", PARTY).principal(token).session(new MockHttpSession())).andReturn();
    }

    @Test
    void aPlan_isSaved_inPolishTime_andTheCardSaysSo() throws Exception {
        row.setActive(false);
        mockMvc.perform(post("/dj/dashboard/schedule").param("partyCode", PARTY).param("openDate", "2026-10-30").param("openTime", "20:00")
                        .param("closeDate", "2026-10-31").param("closeTime", "03:00").principal(token).session(new MockHttpSession()))
                .andExpect(redirectedUrl(BACK)).andExpect(flash().attribute("scheduleSaved", true));
        verify(sessionHelper).require(eq(PARTY), eq(StaffPermission.OPEN_CLOSE), any(), any());
        assertThat(row.getNextOpenAt()).isEqualTo(Instant.parse("2026-10-30T19:00:00Z"));
        assertThat(row.getNextCloseAt()).isEqualTo(Instant.parse("2026-10-31T02:00:00Z"));
    }

    /** A moment past: not saved, and the card says why with the fields as typed. */
    @Test
    void aMomentPast_isRefused_withWhy_andTheFieldsAsTyped() throws Exception {
        row.setNextCloseAt(Instant.parse("2026-10-31T02:00:00Z"));
        mockMvc.perform(post("/dj/dashboard/schedule").param("partyCode", PARTY).param("closeDate", "2026-10-30").param("closeTime", "11:59")
                        .principal(token).session(new MockHttpSession()))
                .andExpect(redirectedUrl(BACK)).andExpect(flash().attribute("scheduleError", "schedule.error.past"))
                .andExpect(flash().attribute("scheduleTyped", new String[]{"", "", "2026-10-30", "11:59"}));
        assertThat(row.getNextCloseAt()).as("as it was").isEqualTo(Instant.parse("2026-10-31T02:00:00Z"));

        mockMvc.perform(post("/dj/dashboard/schedule").param("partyCode", PARTY).param("openDate", "2026-10-30")
                        .principal(token).session(new MockHttpSession()))
                .andExpect(flash().attribute("scheduleError", "schedule.error.incomplete"));
        mockMvc.perform(post("/dj/dashboard/schedule").param("partyCode", PARTY).param("openDate", "2026-10-31").param("openTime", "20:00")
                        .principal(token).session(new MockHttpSession()))
                .andExpect(flash().attribute("scheduleError", "schedule.error.open_while_open"));
    }

    @Test
    void theCardsCross_takesOneMomentAway() throws Exception {
        row.setNextCloseAt(Instant.parse("2026-10-31T02:00:00Z"));
        row.setNextOpenAt(Instant.parse("2026-10-31T19:00:00Z"));
        mockMvc.perform(post("/dj/dashboard/schedule").param("partyCode", PARTY).param("cancel", "close")
                        .param("openDate", "ignored").principal(token).session(new MockHttpSession()))
                .andExpect(redirectedUrl(BACK)).andExpect(flash().attribute("scheduleSaved", true));
        assertThat(row.getNextCloseAt()).isNull();
        assertThat(row.getNextOpenAt()).as("an open of an open party changes nothing: gone too").isNull();
    }

    @Test
    void thePanelsCross_isSentInTheBackground() throws Exception {
        row.setNextCloseAt(Instant.parse("2026-10-31T02:00:00Z"));
        assertThat(send(post("/dj/dashboard/schedule/cancel").param("event", "close")).getResponse().getRedirectedUrl())
                .isEqualTo("/dj/dashboard");
        assertThat(row.getNextCloseAt()).isNull();
        assertThat(send(post("/dj/dashboard/schedule/cancel").param("event", "both")).getResolvedException())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Without OPEN_CLOSE (the helper refuses): nothing saved. */
    @Test
    void withoutOpenClose_nothingIsSaved() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(sessionHelper)
                .require(eq(PARTY), eq(StaffPermission.OPEN_CLOSE), any(), any());
        send(post("/dj/dashboard/schedule").param("closeDate", "2026-10-31").param("closeTime", "03:00"));
        send(post("/dj/dashboard/schedule/cancel").param("event", "close"));
        assertThat(row.getNextCloseAt()).isNull();
    }

    /** "Zakończ imprezę" at 1:00 of a party that closes at 3:00 and opens at 20:00: closed, the 3:00 gone, the 20:00 kept. */
    @Test
    void endParty_dropsThePlannedClose_keepsTheOpen() throws Exception {
        row.setNextCloseAt(Instant.parse("2026-10-31T02:00:00Z"));
        row.setNextOpenAt(Instant.parse("2026-10-31T19:00:00Z"));
        assertThat(send(post("/dj/end-party")).getResponse().getRedirectedUrl()).isEqualTo("/dj/dashboard");
        assertThat(row.isActive()).isFalse();
        assertThat(row.getNextCloseAt()).isNull();
        assertThat(row.getNextOpenAt()).isEqualTo(Instant.parse("2026-10-31T19:00:00Z"));

        send(post("/dj/start-party"));
        assertThat(row.isActive()).isTrue();
        assertThat(row.getNextOpenAt()).isNull();
    }
}
