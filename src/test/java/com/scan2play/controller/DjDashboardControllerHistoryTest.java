package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.DjService;
import com.scan2play.service.DjService.HistoryPage;
import com.scan2play.service.NextTrackService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayerLeaseService;
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

import java.util.List;
import java.util.Map;

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
 * Tests the history of played and rejected songs — how far back it goes and the "Show more" button.
 */
class DjDashboardControllerHistoryTest {

    private static final String PARTY = "ABC12";

    private DjService djService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        djService = mock(DjService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjDashboardController(
                djService, mock(PartySettingsQueryService.class), mock(QrCodeService.class), sessionHelper,
                mock(NextTrackService.class), mock(PlayerLeaseService.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
        when(sessionHelper.getPartySettings(token, session))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).build());
    }

    private static List<SongRequestEntity> rows(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> SongRequestEntity.builder().id((long) i).partyCode(PARTY).songName("Song " + i)
                        .decision("played").build())
                .toList();
    }

    /** Sets up what the service returns and gives back that very list (the entities have no equals). */
    private List<SongRequestEntity> givenHistory(int limit, int rowCount, boolean hasMore) {
        List<SongRequestEntity> rows = rows(rowCount);
        when(djService.getHistory(PARTY, limit)).thenReturn(new HistoryPage(rows, hasMore));
        return rows;
    }

    @Test
    @DisplayName("the fragment shows the last 50 requests by default, and offers 100 when there are older ones")
    void shouldShowTheFirstPage_andOfferMore() throws Exception {
        List<SongRequestEntity> rows = givenHistory(50, 50, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("history :: historyTableContent"))
                .andExpect(model().attribute("history", rows))
                .andExpect(model().attribute("historyHasMore", true))
                .andExpect(model().attribute("historyNextLimit", 100));

        verify(djService).getHistory(PARTY, 50);
    }

    @Test
    @DisplayName("when everything fits there is no \"Show more\"")
    void shouldNotOfferMore_whenThereAreNoOlderRequests() throws Exception {
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

        verify(djService).getHistory(PARTY, 150);
    }

    @Test
    @DisplayName("the history never goes further back than 300 requests, and then offers no more")
    void shouldStopAtTheMaximum() throws Exception {
        givenHistory(300, 300, true);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "100000")
                        .principal(token).session(session))
                .andExpect(model().attribute("historyHasMore", false))
                .andExpect(model().attribute("historyNextLimit", 300));

        verify(djService).getHistory(PARTY, 300);
    }

    @Test
    @DisplayName("a limit below one page is raised to one page, and a negative one too — the query stays sensible")
    void shouldRaiseATooSmallLimit() throws Exception {
        givenHistory(50, 3, false);

        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "1")
                .principal(token).session(session));
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "-7")
                .principal(token).session(session));

        verify(djService, times(2)).getHistory(PARTY, 50);
    }

    @Test
    @DisplayName("a limit that is not a number is a bad request — nothing is read")
    void shouldRejectALimitThatIsNotANumber() throws Exception {
        mockMvc.perform(get("/dj/history-view/fragment").param("partyCode", PARTY).param("limit", "many")
                        .principal(token).session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(djService);
    }

    @Test
    @DisplayName("the standalone history page takes the same limit and puts the same things in the model")
    void shouldServeTheStandalonePage() throws Exception {
        List<SongRequestEntity> rows = givenHistory(100, 100, true);

        mockMvc.perform(get("/dj/history-view").param("limit", "100").principal(token).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(model().attribute("partyCode", PARTY))
                .andExpect(model().attribute("history", rows))
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

        verifyNoInteractions(djService);
    }
}
