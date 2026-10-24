package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.DjService;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanRequiredException;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.StaffInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every endpoint of a feature of the Pro plan (V35) refuses an organiser without Pro once the plans are enforced — on the server: the
 * page shows no form for it, but a request may come anyway. Let through while the plans are not enforced (production today), for an
 * organiser with Pro, and for the staff of an organiser with Pro (the party's plan, never the person's). What is everyone's stays
 * everyone's. A new endpoint of a Pro feature belongs in this list — as in {@link StaffPermissionEndpointsTest}.
 */
class PlanFeatureEndpointsTest {

    private static final String PUB = "PUB01";
    private static final String ORGANISER = "pub-owner";

    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId(ORGANISER).active(true).build();
    private PartyStaffService staff;
    private PartySettingsCommandService settings;

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffService.class);
        settings = mock(PartySettingsCommandService.class);
        when(staff.partiesOf(anyString())).thenReturn(List.of(pub));
        when(staff.accessOf(pub, ORGANISER)).thenReturn(Optional.of(new PartyStaffService.Access(pub, true, StaffPermission.all())));
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.of(new PartyStaffService.Access(pub, false, StaffPermission.all())));
    }

    /** The panel's controllers with the real {@link DjSessionHelper} and the plans as {@code plans} decides them. */
    private MockMvc mvc(PlanService plans) {
        PartySettingsQueryService query = mock(PartySettingsQueryService.class);
        when(query.getSettings(PUB)).thenReturn(pub);
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByOwnerId(anyString())).thenReturn(Optional.empty());
        DjSessionHelper helper = new DjSessionHelper(query, settings, parties, staff);
        PlayHistoryService history = mock(PlayHistoryService.class);
        when(history.getHistory(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(new PlayHistoryService.Page(List.of(), false));
        DjDashboardController dashboard = new DjDashboardController(mock(DjService.class), settings, mock(QrCodeService.class), helper,
                history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class), staff, mock(StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor());
        ReflectionTestUtils.setField(dashboard, "rawBaseUrl", "http://localhost:8080");
        dashboard.init();
        return MockMvcBuilders.standaloneSetup(
                        new DjSongController(mock(DjService.class), helper, plans),
                        new DjPartySettingsController(settings, mock(AccountDeletionService.class), helper, plans),
                        dashboard,
                        new DjSummaryController(oneEvening(), helper, codesAsTexts(), plans))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html")).build();
    }

    /** The answer's status: a request let through is answered (below 400), not only "nothing thrown" (the review of 2026-10-14). */
    private static int perform(MockMvc mvc, String who, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.principal(user(who)).session(new MockHttpSession())).andReturn().getResponse().getStatus();
    }

    static Stream<Arguments> proFeatures() {
        return Stream.of(
                Arguments.of(PlanFeature.HOST_LISTS, true, post("/dj/dashboard/host-lists").param("partyCode", PUB).param("blocked", "Akcent")),
                Arguments.of(PlanFeature.HOST_LISTS, true, post("/dj/dashboard/host-link").param("partyCode", PUB).param("link", "new")),
                Arguments.of(PlanFeature.PROFILES, false, post("/dj/dashboard/dj-links").param("partyCode", PUB).param("instagram", "@dj")),
                Arguments.of(PlanFeature.TIPS, false, post("/dj/dashboard/tip-link").param("partyCode", PUB).param("tip", "")),
                Arguments.of(PlanFeature.TIPS, true, post("/dj/dashboard/tip-count").param("partyCode", PUB).param("id", "1")),
                Arguments.of(PlanFeature.COMMENT_STYLES, true, post("/dj/dashboard/comment-style").param("partyCode", PUB).param("commentStyle", "FUNNY")),
                Arguments.of(PlanFeature.GUEST_LIMITS, true, post("/dj/dashboard/limits").param("partyCode", PUB).param("requestLimit", "5")
                        .param("cooldownMinutes", "1").param("duplicateCheckWindow", "30")),
                Arguments.of(PlanFeature.SUMMARY, true, get("/dj/summary").param("party", PUB)),
                Arguments.of(PlanFeature.SUMMARY, true, get("/dj/summary/csv").param("party", PUB)));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("proFeatures")
    void aProFeature_isRefused_forAnOrganiserWithoutPro_andNothingIsSaved(PlanFeature feature, boolean delegated,
                                                                         MockHttpServletRequestBuilder request) {
        assertThatThrownBy(() -> perform(mvc(Plans.enforced()), ORGANISER, request))
                .hasRootCauseInstanceOf(PlanRequiredException.class).rootCause().extracting("feature").isEqualTo(feature);
        verify(settings, never()).updateSettings(anyString(), any());
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("proFeatures")
    void aProFeature_isLetThrough_whileThePlansAreNotEnforced_andForAnOrganiserWithPro(PlanFeature feature, boolean delegated,
                                                                                      MockHttpServletRequestBuilder request) throws Exception {
        assertThat(perform(mvc(Plans.off()), ORGANISER, request)).isLessThan(400);
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), ORGANISER, request)).isLessThan(400);
    }

    /** The staff work the organiser's party with the organiser's plan: let through under Pro, refused without it. */
    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("proFeatures")
    void theStaff_haveTheOrganisersPlan(PlanFeature feature, boolean delegated, MockHttpServletRequestBuilder request) throws Exception {
        if (!delegated) {
            return;   // the profiles and the tip link are the organiser's alone (StaffPermissionEndpointsTest)
        }
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), "kasia", request)).isLessThan(400);
        assertThatThrownBy(() -> perform(mvc(Plans.enforced("kasia")), "kasia", request))
                .hasRootCauseInstanceOf(PlanRequiredException.class);
    }

    /** The core of a party is everyone's: an organiser without Pro, the plans enforced, does all of it. */
    static Stream<Arguments> everyones() {
        return Stream.of(
                Arguments.of(post("/dj/dashboard/comment-style").param("partyCode", PUB).param("commentStyle", "CLASSIC")),
                Arguments.of(post("/dj/dashboard/vibe").param("partyCode", PUB).param("newVibe", "ANY")),
                Arguments.of(post("/dj/dashboard/party-words").param("partyCode", PUB).param("vibeNote", "wesele").param("djName", "DJ")),
                Arguments.of(post("/dj/dashboard/play").param("partyCode", PUB).param("id", "1")),
                Arguments.of(post("/dj/dashboard/dismiss").param("partyCode", PUB).param("id", "1")),
                Arguments.of(post("/dj/dashboard/clear-queue").param("partyCode", PUB)),
                Arguments.of(post("/dj/end-party").param("partyCode", PUB)),
                Arguments.of(get("/dj/history-view/fragment").param("partyCode", PUB)),
                Arguments.of(get("/dj/history-view").param("party", PUB)),
                Arguments.of(get("/dj/settings").param("party", PUB)),
                Arguments.of(get("/dj/qr-print").param("party", PUB)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyones")
    void theCoreOfAParty_isEveryones(MockHttpServletRequestBuilder request) throws Exception {
        assertThat(perform(mvc(Plans.enforced()), ORGANISER, request)).isLessThan(400);
    }

    /** A party with one evening of requests: the summary and its CSV answer 200, not the 404 of a party without any. */
    private static EveningSummaryService oneEvening() {
        EveningSummaryService summaries = mock(EveningSummaryService.class);
        when(summaries.evenings(PUB)).thenReturn(List.of(new EveningSummaryService.Evening(java.time.LocalDate.of(2026, 10, 10), 3)));
        return summaries;
    }

    /** The CSV's header and its words: any text will do here. */
    private static StaticMessageSource codesAsTexts() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        return messages;
    }
}
