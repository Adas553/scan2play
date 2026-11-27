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
import org.junit.jupiter.api.Test;
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
    private com.scan2play.service.WishService wishes;
    private com.scan2play.service.PollService polls;

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffService.class);
        settings = mock(PartySettingsCommandService.class);
        wishes = WishControllers.wishes(PUB);
        polls = PollControllers.polls(PUB);
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
                history, mock(GuestRequestLimiter.class), mock(PushNotificationService.class), staff, mock(StaffInvitationService.class), plans, new com.scan2play.service.AiHealthMonitor(), com.scan2play.service.PartySchedules.texts(), mock(com.scan2play.service.WelcomeEmailService.class), com.scan2play.service.FreeAiLimit.off(), wishes, org.mockito.Mockito.mock(com.scan2play.service.PollService.class), org.mockito.Mockito.mock(com.scan2play.service.EventPlanService.class));
        ReflectionTestUtils.setField(dashboard, "rawBaseUrl", "http://localhost:8080");
        dashboard.init();
        return MockMvcBuilders.standaloneSetup(
                        new DjSongController(mock(DjService.class), helper, plans, mock(com.scan2play.service.SongRequestCommandService.class)),
                        new DjPartySettingsController(settings, mock(AccountDeletionService.class), mock(com.scan2play.service.PartyCodeService.class), helper, plans, com.scan2play.service.PartySchedules.of(settings)),
                        dashboard,
                        new DjSummaryController(oneEvening(), helper, codesAsTexts(), plans, "https://www.scan2play.com.pl/"),
                        new DjLibraryController(mock(com.scan2play.service.DjLibraryService.class), helper, plans, codesAsTexts(),
                                settings, DjLibraryController.MAX_BYTES),
                        new PartyLogoController(mock(com.scan2play.service.PartyLogoService.class), query, helper, plans, codesAsTexts()),
                        WishControllers.djWishes(wishes, helper, plans, settings, codesAsTexts()),
                        WishControllers.djPlan(wishes, helper, plans, settings, codesAsTexts()),
                        new DjPollsController(polls, helper, plans, codesAsTexts()),
                        new DjDeviceController(devices(), deckWatch(), helper, plans))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html")).build();
    }

    /** The DJ's program's pairing (V78): a code, and "Odłącz" finding its computer. */
    private static com.scan2play.service.DjDeviceService devices() {
        com.scan2play.service.DjDeviceService devices = mock(com.scan2play.service.DjDeviceService.class);
        when(devices.newCode(PUB)).thenReturn("123456");
        when(devices.remove(PUB, 1L)).thenReturn(true);
        return devices;
    }

    /** "Ostrzeż po" (V81) taking a value offered, and the card's list. */
    private static com.scan2play.service.DeckWatchService deckWatch() {
        com.scan2play.service.DeckWatchService watch = mock(com.scan2play.service.DeckWatchService.class);
        when(watch.setSilenceMinutes(PUB, 10)).thenReturn(true);
        when(watch.watch(any())).thenReturn(new com.scan2play.service.DeckWatchService.Watch(java.util.List.of(), 20));
        return watch;
    }

    /** "Ostrzeż po" with Pro: a value not offered (the service says no) or not a number is 400, nothing more. */
    @Test
    void ostrzezPo_aValueNotOffered_is400() throws Exception {
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), ORGANISER, post("/dj/devices/silence").param("partyCode", PUB)
                .param("minutes", "10"))).isEqualTo(204);
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), ORGANISER, post("/dj/devices/silence").param("partyCode", PUB)
                .param("minutes", "15"))).isEqualTo(400);
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), ORGANISER, post("/dj/devices/silence").param("partyCode", PUB)
                .param("minutes", "abc"))).isEqualTo(400);
        assertThat(perform(mvc(Plans.enforced(ORGANISER)), ORGANISER, post("/dj/devices/silence").param("partyCode", PUB)))
                .as("no minutes").isEqualTo(400);
    }

    /** The answer's status: a request let through is answered (below 400), not only "nothing thrown" (the review of 2026-10-14). */
    private static int perform(MockMvc mvc, String who, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.principal(user(who)).session(new MockHttpSession())).andReturn().getResponse().getStatus();
    }

    /**
     * The panel's own reading of the history folder (variant E, docs/ideas.md item 18) is parked: its endpoint is gone with it, the
     * program's songs come by /api/device/plays.
     */
    @Test
    void thePanelsOwnFolderReading_hasNoEndpoint() throws Exception {
        assertThat(perform(mvc(Plans.off()), ORGANISER, post("/dj/dashboard/plays").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"partyCode\":\"" + PUB + "\",\"plays\":[]}"))).isIn(404, 405);
    }

    static Stream<Arguments> proFeatures() {
        return Stream.of(
                Arguments.of(PlanFeature.HOST_LISTS, true, post("/dj/dashboard/host-lists").param("partyCode", PUB).param("blocked", "Akcent")),
                Arguments.of(PlanFeature.HOST_LISTS, true, post("/dj/dashboard/host-link").param("partyCode", PUB).param("link", "new")),
                Arguments.of(PlanFeature.PROFILES, false, post("/dj/dashboard/dj-links").param("partyCode", PUB).param("instagram", "@dj")),
                Arguments.of(PlanFeature.TIPS, false, post("/dj/dashboard/tip-link").param("partyCode", PUB).param("tip", "")),
                Arguments.of(PlanFeature.PROFILES, false, post("/dj/dashboard/booking-contact").param("partyCode", PUB)
                        .param("bookingEmail", "dj@koko.pl")),
                Arguments.of(PlanFeature.TIPS, true, post("/dj/dashboard/tip-count").param("partyCode", PUB).param("id", "1")),
                Arguments.of(PlanFeature.COMMENT_STYLES, true, post("/dj/dashboard/comment-style").param("partyCode", PUB).param("commentStyle", "FUNNY")),
                Arguments.of(PlanFeature.GUEST_LIMITS, true, post("/dj/dashboard/limits").param("partyCode", PUB).param("requestLimit", "5")
                        .param("cooldownMinutes", "1").param("duplicateCheckWindow", "30")),
                Arguments.of(PlanFeature.SUMMARY, true, get("/dj/summary").param("party", PUB)),
                Arguments.of(PlanFeature.SUMMARY, true, get("/dj/summary/csv").param("party", PUB)),
                Arguments.of(PlanFeature.SUMMARY, true, post("/dj/summary/title").param("party", PUB).param("evening", "2026-10-10").param("title", "x")),
                Arguments.of(PlanFeature.SUMMARY, true, post("/dj/summary/share").param("party", PUB).param("evening", "2026-10-10")),
                Arguments.of(PlanFeature.SUMMARY, true, post("/dj/summary/share/off").param("party", PUB).param("evening", "2026-10-10")),
                Arguments.of(PlanFeature.LIBRARY, false, post("/dj/library").param("partyCode", PUB)
                        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).content("#EXTM3U\nA - B.mp3\n")),
                // the guests' suggestions marked by the library (V58): switched on only with Pro
                Arguments.of(PlanFeature.LIBRARY, false, post("/dj/library/hints").param("partyCode", PUB).param("on", "true")),
                Arguments.of(PlanFeature.LOGO, false, post("/dj/logo").param("partyCode", PUB)
                        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                        .content(com.scan2play.service.TestImages.png(40, 20))),
                // "Życzenia przed imprezą" (V68): a new event, reopening, a new link, "⭐", the invitation's print
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/create").param("partyCode", PUB).param("name", "Wesele")
                        .param("date", com.scan2play.service.WishService.today().plusDays(1).toString())),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/closed").param("partyCode", PUB).param("closed", "false")),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/link").param("partyCode", PUB)),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/theme").param("partyCode", PUB).param("theme", "WEDDING")),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/must-play").param("partyCode", PUB).param("song", "Wilki - Baśka")),
                Arguments.of(PlanFeature.WISHES, true, get("/dj/qr-print").param("party", PUB).param("wish", "1")),
                // "Plan imprezy" (V77): a save, a template, the hosts' link, the event's lists put over the party's
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/plan").param("partyCode", PUB).param("label", "Tort")),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/plan/template").param("partyCode", PUB).param("template", "wedding")),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/plan/clear").param("partyCode", PUB)),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/plan/host-link").param("partyCode", PUB)),
                Arguments.of(PlanFeature.WISHES, true, post("/dj/wishes/1/plan/use-lists").param("partyCode", PUB)),
                // "Pytanie do gości" (V74): a new question, asking one
                Arguments.of(PlanFeature.POLLS, true, post("/dj/polls/create").param("partyCode", PUB).param("question", "Disco polo?")),
                Arguments.of(PlanFeature.POLLS, true, post("/dj/polls/1/ask").param("partyCode", PUB)),
                Arguments.of(PlanFeature.POLLS, true, post("/dj/polls/1/edit").param("partyCode", PUB).param("question", "Disco polo?")),
                // "Zagrane samo" (item 19, V78): the program's pairing code and the list of computers
                Arguments.of(PlanFeature.DECK, true, post("/dj/devices/code").param("partyCode", PUB)),
                Arguments.of(PlanFeature.DECK, true, get("/dj/devices").param("partyCode", PUB)),
                // ... and "Ostrzeż po" (V81)
                Arguments.of(PlanFeature.DECK, true, post("/dj/devices/silence").param("partyCode", PUB).param("minutes", "10")));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("proFeatures")
    void aProFeature_isRefused_forAnOrganiserWithoutPro_andNothingIsSaved(PlanFeature feature, boolean delegated,
                                                                         MockHttpServletRequestBuilder request) {
        assertThatThrownBy(() -> perform(mvc(Plans.enforced()), ORGANISER, request))
                .hasRootCauseInstanceOf(PlanRequiredException.class).rootCause().extracting("feature").isEqualTo(feature);
        verify(settings, never()).updateSettings(anyString(), any());
        org.mockito.Mockito.verifyNoInteractions(wishes);
        org.mockito.Mockito.verifyNoInteractions(polls);
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
                Arguments.of(get("/dj/qr-print").param("party", PUB)),
                Arguments.of(post("/dj/qr-print/settings").param("partyCode", PUB).param("heading", "Osiemnastka")),
                // deleting the library needs no Pro: the organiser's own data goes whatever the plan
                Arguments.of(post("/dj/library/delete").param("partyCode", PUB)),
                // nor switching the guests' marked suggestions off (V58)
                Arguments.of(post("/dj/library/hints").param("partyCode", PUB).param("on", "false")),
                // nor deleting the logo (V45)
                Arguments.of(post("/dj/logo/delete").param("partyCode", PUB)),
                // nor taking a paired computer away (V78): "Odłącz" after Pro ended
                Arguments.of(post("/dj/devices/remove").param("partyCode", PUB).param("id", "1")),
                // nor the events of "Życzenia przed imprezą" made before (V68): seen, closed, downloaded, deleted
                Arguments.of(get("/dj/wishes").param("party", PUB).param("event", "1")),
                Arguments.of(post("/dj/wishes/1/closed").param("partyCode", PUB).param("closed", "true")),
                Arguments.of(get("/dj/wishes/1/csv").param("party", PUB)),
                Arguments.of(post("/dj/wishes/1/delete").param("partyCode", PUB)),
                // nor a plan made before (V77): seen, printed, its hosts' link taken away
                Arguments.of(get("/dj/wishes/1/plan").param("party", PUB)),
                Arguments.of(get("/dj/wishes/1/plan/print").param("party", PUB)),
                Arguments.of(post("/dj/wishes/1/plan/host-link").param("partyCode", PUB).param("on", "false")),
                // nor the questions to the guests made before (V74): seen, ended, deleted
                Arguments.of(get("/dj/polls").param("party", PUB)),
                Arguments.of(get("/dj/polls/1/results").param("party", PUB)),
                Arguments.of(post("/dj/polls/1/end").param("partyCode", PUB)),
                Arguments.of(post("/dj/polls/1/results").param("partyCode", PUB).param("hidden", "false")),
                Arguments.of(post("/dj/polls/1/tv").param("partyCode", PUB).param("onTv", "false")),
                Arguments.of(post("/dj/polls/1/delete").param("partyCode", PUB)));
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
        when(summaries.hasRequests(PUB, java.time.LocalDate.of(2026, 10, 10))).thenReturn(true);   // a title or a link: an evening there is
        return summaries;
    }

    /** The CSV's header and its words: any text will do here. */
    private static StaticMessageSource codesAsTexts() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        return messages;
    }
}
