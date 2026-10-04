package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryFilter;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PlayHistoryService.Page;
import com.scan2play.service.QrCodeService;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tests the history of what played — how far back it goes and the "Show more" button.
 */
class DjDashboardControllerHistoryTest {

    private static final String PARTY = "ABC12";

    private PlayHistoryService historyService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        historyService = mock(PlayHistoryService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjDashboardController(
                mock(DjService.class), mock(PartySettingsQueryService.class), mock(QrCodeService.class), sessionHelper,
                historyService,
                mock(GuestRequestLimiter.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
        when(sessionHelper.getPartySettings(token, session))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).build());
    }

    private static List<HistoryEntry> entries(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new HistoryEntry((long) i, java.time.LocalDateTime.of(2026, 9, 29, 20, 0).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant().minus(i, ChronoUnit.MINUTES),
                        "Song " + i, null, "Pop", "played", "ok", 5, null))
                .toList();
    }

    /** Sets up what the service returns for every entry of the history (no filter) and gives back that very list. */
    private List<HistoryEntry> givenHistory(int limit, int rowCount, boolean hasMore) {
        return givenHistory(limit, HistoryFilter.ALL, rowCount, hasMore);
    }

    private List<HistoryEntry> givenHistory(int limit, HistoryFilter filter, int rowCount, boolean hasMore) {
        List<HistoryEntry> entries = entries(rowCount);
        when(historyService.getHistory(PARTY, limit, filter)).thenReturn(new Page(entries, hasMore));
        return entries;
    }

    @Test
    @DisplayName("the fragment shows the last 50 entries by default, and offers 100 when there are older ones")
    void shouldShowTheFirstPage_andOfferMore() throws Exception {
        List<HistoryEntry> entries = givenHistory(50, 50, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("history :: historyTableContent"))
                .andExpect(model().attribute("history", entries))
                .andExpect(model().attribute("historyHasMore", true))
                .andExpect(model().attribute("historyNextLimit", 100));

        verify(historyService).getHistory(PARTY, 50, HistoryFilter.ALL);
    }

    @Test
    @DisplayName("the fragment asks for a heading of its own (it lands under the dashboard's other panels); the standalone page does not")
    void shouldAskForAHeadingOnlyInTheFragment() throws Exception {
        givenHistory(50, 3, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(model().attribute("historyHeading", true));
        mockMvc.perform(get("/dj/history-view").principal(token).session(session))
                .andExpect(model().attributeDoesNotExist("historyHeading"));
    }

    @Test
    @DisplayName("when everything fits there is no \"Show more\"")
    void shouldNotOfferMore_whenThereAreNoOlderEntries() throws Exception {
        givenHistory(50, 12, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(model().attribute("historyHasMore", false));
    }

    @Test
    @DisplayName("\"Show more\" asks for the next page: the limit is passed on")
    void shouldPassTheLimitOn() throws Exception {
        givenHistory(150, 150, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "150")
                        .principal(token).session(session))
                .andExpect(model().attribute("historyHasMore", true))
                .andExpect(model().attribute("historyNextLimit", 200));

        verify(historyService).getHistory(PARTY, 150, HistoryFilter.ALL);
    }

    @Test
    @DisplayName("the history never goes further back than 300 entries, and then offers no more")
    void shouldStopAtTheMaximum() throws Exception {
        givenHistory(300, 300, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "100000")
                        .principal(token).session(session))
                .andExpect(model().attribute("historyHasMore", false))
                .andExpect(model().attribute("historyNextLimit", 300));

        verify(historyService).getHistory(PARTY, 300, HistoryFilter.ALL);
    }

    @Test
    @DisplayName("a limit below one page is raised to one page, and a negative one too — the query stays sensible")
    void shouldRaiseATooSmallLimit() throws Exception {
        givenHistory(50, 3, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "1")
                .principal(token).session(session));
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "-7")
                .principal(token).session(session));

        verify(historyService, times(2)).getHistory(PARTY, 50, HistoryFilter.ALL);
    }

    @Test
    @DisplayName("a filter button asks for entries of that kind: the filter is passed on to the service, and its button is the lit one")
    void shouldPassTheFilterOn() throws Exception {
        for (HistoryFilter filter : HistoryFilter.values()) {
            List<HistoryEntry> entries = givenHistory(50, filter, 5, false);

            mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("filter", filter.param())
                            .principal(token).session(session))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("history", entries))
                    .andExpect(model().attribute("historyFilter", filter.param()));

            verify(historyService).getHistory(PARTY, 50, filter);
        }
    }

    @Test
    @DisplayName("the filter and the limit work together: \"Show more\" of a filtered list asks for that filter with the next limit")
    void shouldCombineTheFilterWithTheLimit() throws Exception {
        givenHistory(150, HistoryFilter.REJECTED, 150, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "150")
                        .param("filter", "rejected").principal(token).session(session))
                .andExpect(model().attribute("historyFilter", "rejected"))
                .andExpect(model().attribute("historyHasMore", true))
                .andExpect(model().attribute("historyNextLimit", 200));

        verify(historyService).getHistory(PARTY, 150, HistoryFilter.REJECTED);
    }

    @Test
    @DisplayName("no filter, or one nobody knows, is \"all\" — a bad link does not break the page (and the value is case-blind)")
    void shouldTreatAMissingOrUnknownFilterAsAll() throws Exception {
        givenHistory(50, 3, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("filter", "nonsense")
                        .principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("historyFilter", "all"));
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("filter", "background")
                .principal(token).session(session)).andExpect(model().attribute("historyFilter", "all"));   // an old link
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY)
                .principal(token).session(session)).andExpect(model().attribute("historyFilter", "all"));

        givenHistory(50, HistoryFilter.PLAYED, 3, false);
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("filter", "PLAYED")
                .principal(token).session(session)).andExpect(model().attribute("historyFilter", "played"));

        verify(historyService, times(3)).getHistory(PARTY, 50, HistoryFilter.ALL);
    }

    @Test
    @DisplayName("the standalone page takes the filter too")
    void shouldTakeTheFilterOnTheStandalonePage() throws Exception {
        List<HistoryEntry> entries = givenHistory(50, HistoryFilter.PLAYED, 4, false);

        mockMvc.perform(get("/dj/history-view").param("filter", "played").principal(token).session(session))
                .andExpect(view().name("history"))
                .andExpect(model().attribute("history", entries))
                .andExpect(model().attribute("historyFilter", "played"));
    }

    @Test
    @DisplayName("a limit that is not a number is a bad request — nothing is read")
    void shouldRejectALimitThatIsNotANumber() throws Exception {
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "many")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("the standalone history page takes the same limit and puts the same things in the model")
    void shouldServeTheStandalonePage() throws Exception {
        List<HistoryEntry> entries = givenHistory(100, 100, true);

        mockMvc.perform(get("/dj/history-view").param("limit", "100").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(model().attribute("partyCode", PARTY))
                .andExpect(model().attribute("history", entries))
                .andExpect(model().attribute("historyHasMore", true))
                .andExpect(model().attribute("historyNextLimit", 150));
    }

    @Test
    @DisplayName("the fragment validates that the party belongs to the logged-in DJ (IDOR protection)")
    void shouldValidateOwnership() throws Exception {
        givenHistory(50, 1, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).principal(token).session(session));

        verify(sessionHelper).validateOwnership(PARTY, token, session);
    }

    @Test
    @DisplayName("a party owned by someone else is rejected and no history is read")
    void shouldNotReadTheHistory_whenPartyBelongsToSomeoneElse() {
        doThrow(new AccessDeniedException("You do not own party: OTHER"))
                .when(sessionHelper).validateOwnership(anyString(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(get("/dj/history-view/fragment")
                        .param("partyCode", "OTHER").principal(token).session(session)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(historyService);
    }
}
