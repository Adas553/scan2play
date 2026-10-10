package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.DjService;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every endpoint of the panel refuses a person of the staff who was not given its permission — on the server: a hidden button is only
 * the look (V32). The real {@link DjSessionHelper} over a person whose row has every permission but the one tested (refused), then
 * only that one (let through); what is never handed over is refused even with every permission. A new endpoint belongs in this list.
 */
class StaffPermissionEndpointsTest {

    private static final String PUB = "PUB01";

    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId("pub-owner").active(true).build();
    private PartyStaffService staff;
    private MockMvc mockMvc;
    private final OAuth2AuthenticationToken kasia = new OAuth2AuthenticationToken(new DefaultOAuth2User(
            AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "kasia"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"), "google");

    @BeforeEach
    void setUp() {
        PartySettingsQueryService query = mock(PartySettingsQueryService.class);
        when(query.getSettings(PUB)).thenReturn(pub);
        staff = mock(PartyStaffService.class);
        when(staff.partiesOf(anyString())).thenReturn(List.of(pub));
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByOwnerId(anyString())).thenReturn(Optional.empty());
        DjSessionHelper helper = new DjSessionHelper(query, mock(PartySettingsCommandService.class), parties, staff);

        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(new PlayHistoryService.Page(List.of(), false));
        DjDashboardController dashboard = new DjDashboardController(mock(DjService.class), mock(PartySettingsCommandService.class),
                mock(QrCodeService.class), helper, history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class), staff, mock(com.scan2play.service.StaffInvitationService.class));
        ReflectionTestUtils.setField(dashboard, "rawBaseUrl", "http://localhost:8080");
        dashboard.init();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DjSongController(mock(DjService.class), helper),
                        new DjPartySettingsController(mock(PartySettingsCommandService.class), mock(AccountDeletionService.class), helper),
                        dashboard,
                        new DjSummaryController(mock(EveningSummaryService.class), helper, new StaticMessageSource()),
                        new StaffController(staff, mock(PartySettingsCommandService.class), helper,
                                mock(com.scan2play.service.StaffInvitationService.class)))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html")).build();
    }

    private void kasiaMay(Set<StaffPermission> permissions) {
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.of(new PartyStaffService.Access(pub, false, permissions)));
    }

    private void perform(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.principal(kasia).session(new MockHttpSession()));
    }

    static Stream<Arguments> delegated() {
        return Stream.of(
                Arguments.of(StaffPermission.QUEUE, post("/dj/dashboard/play").param("id", "1").param("partyCode", PUB)),
                Arguments.of(StaffPermission.QUEUE, post("/dj/dashboard/dismiss").param("id", "1").param("partyCode", PUB)),
                Arguments.of(StaffPermission.QUEUE, post("/dj/dashboard/restore").param("id", "1").param("partyCode", PUB)),
                Arguments.of(StaffPermission.TIPS, post("/dj/dashboard/tip-count").param("id", "1").param("partyCode", PUB)),
                Arguments.of(StaffPermission.CLEAR_QUEUE, post("/dj/dashboard/clear-queue").param("partyCode", PUB)),
                Arguments.of(StaffPermission.OPEN_CLOSE, post("/dj/end-party").param("partyCode", PUB)),
                Arguments.of(StaffPermission.OPEN_CLOSE, post("/dj/start-party").param("partyCode", PUB)),
                Arguments.of(StaffPermission.HISTORY, get("/dj/history-view/fragment").param("partyCode", PUB)),
                Arguments.of(StaffPermission.HISTORY, get("/dj/history-view").param("party", PUB)),
                Arguments.of(StaffPermission.VIBE, post("/dj/dashboard/vibe").param("partyCode", PUB).param("newVibe", "ANY")),
                Arguments.of(StaffPermission.VIBE, post("/dj/dashboard/comment-style").param("partyCode", PUB).param("commentStyle", "FUNNY")),
                Arguments.of(StaffPermission.VIBE, post("/dj/dashboard/party-words").param("partyCode", PUB).param("vibeNote", "x")
                        .param("djName", "x")),
                Arguments.of(StaffPermission.LIMITS, post("/dj/dashboard/limits").param("partyCode", PUB).param("requestLimit", "2")
                        .param("cooldownMinutes", "3").param("duplicateCheckWindow", "5")),
                Arguments.of(StaffPermission.HOST_LISTS, post("/dj/dashboard/host-lists").param("partyCode", PUB).param("blocked", "x")),
                Arguments.of(StaffPermission.HOST_LISTS, post("/dj/dashboard/host-link").param("partyCode", PUB).param("link", "off")),
                Arguments.of(StaffPermission.SUMMARY, get("/dj/summary").param("party", PUB)),
                Arguments.of(StaffPermission.SUMMARY, get("/dj/summary/csv").param("party", PUB)));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("delegated")
    void anEndpoint_isRefusedWithoutItsPermission_andLetThroughWithIt(StaffPermission needed, MockHttpServletRequestBuilder request) {
        EnumSet<StaffPermission> allBut = EnumSet.allOf(StaffPermission.class);
        allBut.remove(needed);
        kasiaMay(allBut);
        assertThatThrownBy(() -> perform(request)).hasRootCauseInstanceOf(AccessDeniedException.class);

        kasiaMay(EnumSet.of(needed));
        assertThatCode(() -> perform(request)).doesNotThrowAnyException();
    }

    static Stream<Arguments> neverHandedOver() {
        return Stream.of(
                Arguments.of(post("/dj/dashboard/dj-links").param("partyCode", PUB).param("instagram", "@x")),
                Arguments.of(post("/dj/dashboard/tip-link").param("partyCode", PUB).param("tip", "")),
                Arguments.of(post("/dj/dashboard/clear-history").param("partyCode", PUB)),
                // the staff and its invitations (V32, V33, V34)
                Arguments.of(get("/dj/staff").param("party", PUB)),
                Arguments.of(post("/dj/staff/permissions").param("partyCode", PUB).param("id", "7").param("role", "VIEWER")),
                Arguments.of(post("/dj/staff/remove").param("partyCode", PUB).param("id", "7")),
                Arguments.of(post("/dj/staff/link").param("partyCode", PUB).param("link", "new").param("role", "CO_ORGANISER")),
                Arguments.of(post("/dj/staff/invite").param("partyCode", PUB).param("email", "ola@gmail.com").param("role", "CO_ORGANISER")),
                Arguments.of(post("/dj/staff/invitation/cancel").param("partyCode", PUB).param("id", "3")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("neverHandedOver")
    void whatIsNeverHandedOver_isRefusedEvenWithEveryPermission(MockHttpServletRequestBuilder request) {
        kasiaMay(StaffPermission.all());
        assertThatThrownBy(() -> perform(request)).hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    /**
     * The page "Ustawienia imprezy" (2026-10-10) opens for anyone on the staff — "To urządzenie" is everyone's; each card is shown by
     * its permission — and never for another party.
     */
    @org.junit.jupiter.api.Test
    void theSettingsPage_opensForTheStaff_withoutAPermission_andNeverForAnotherParty() {
        kasiaMay(EnumSet.noneOf(StaffPermission.class));
        assertThatCode(() -> perform(get("/dj/settings").param("party", PUB))).doesNotThrowAnyException();

        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> perform(get("/dj/settings").param("party", PUB))).hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    /** Another party than one the person works at: refused whatever the code says, nothing falls back to another party. */
    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("delegated")
    void anotherParty_isRefused(StaffPermission ignored, MockHttpServletRequestBuilder request) {
        kasiaMay(StaffPermission.all());
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> perform(request)).hasRootCauseInstanceOf(AccessDeniedException.class);
    }
}
