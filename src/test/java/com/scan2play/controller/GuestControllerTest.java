package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.RequestMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestRequestLimiter.Refusal;
import com.scan2play.service.GuestRequestLimiter.Scope;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SongEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A guest's song request and its limits (review item 4.1): the guest's own limit from the session, then the server's limits
 * by client address and party — all before the AI evaluates anything.
 */
@ExtendWith(MockitoExtension.class)
class GuestControllerTest {

    private static final String PARTY = "ABC12";
    private static final String IP = "203.0.113.7";

    @Mock
    private GuestQueueService guestQueueService;
    @Mock
    private SongEvaluationService songEvaluationService;
    @Mock
    private PartySettingsQueryService partySettingsQueryService;
    @Mock
    private GuestSessionService guestSessionService;
    @Mock
    private GuestRequestLimiter guestRequestLimiter;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private GuestController controller;

    private PartySettingsEntity settings;
    private MockHttpSession session;
    private MockHttpServletRequest request;
    private RedirectAttributesModelMap redirectAttributes;

    @BeforeEach
    void setUp() {
        settings = PartySettingsEntity.builder().partyCode(PARTY).active(true).requestLimit(2).build();
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        redirectAttributes = new RedirectAttributesModelMap();
        org.mockito.Mockito.lenient().when(guestRequestLimiter.clientIp(request)).thenReturn(IP); // not read by the party page
        org.mockito.Mockito.lenient().when(partySettingsQueryService.getSettings(PARTY)).thenReturn(settings); // not read by styleOf
    }

    private String request() throws Exception {
        return request("Song", null);
    }

    private String request(String text, String mode) throws Exception {
        return controller.requestSong(PARTY, text, "POP_AND_DANCE", mode, new ExtendedModelMap(), session, request, redirectAttributes).call();
    }

    @Test
    void aRequestWithinAllLimits_isEvaluated() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "POP_AND_DANCE", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("ACCEPTED", "Song", "ok", 5, "title", 42L));

        assertThat(request()).isEqualTo("result");
        // the guest's request is remembered, so the party page can say where it waits
        verify(guestSessionService).rememberRequest(session, PARTY, 42L);
    }

    @Test
    void theListAlone_isTheFragmentThePageFetchesAgain() {
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue("Now", java.util.List.of(), java.util.Set.of(), null, null);
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of());
        when(guestQueueService.view(PARTY, java.util.Set.of())).thenReturn(queue);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.partyQueue(PARTY, model, session)).isEqualTo("fragments/guest-queue :: guestQueue");
        assertThat(model.getAttribute(ViewAttributes.GUEST_QUEUE)).isSameAs(queue);
    }

    @Test
    void theListOfAnEndedParty_isEmpty() {
        settings.setActive(false);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.partyQueue(PARTY, model, session)).isEqualTo("fragments/guest-queue :: guestQueue");
        assertThat(model.getAttribute(ViewAttributes.GUEST_QUEUE)).isNull();
    }

    @Test
    void thePartyPage_showsTheQueueAsTheGuestSeesIt() {
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue("Now", java.util.List.of(), java.util.Set.of(42L), 3, "Mine");
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of(42L));
        when(guestQueueService.view(PARTY, java.util.Set.of(42L))).thenReturn(queue);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.partyIndex(PARTY, model, session)).isEqualTo("index");
        assertThat(model.getAttribute(ViewAttributes.GUEST_QUEUE)).isSameAs(queue);
    }

    @Test
    void theGuestsOwnLimit_refusesFirst_withoutTouchingTheServerLimits() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.of(42L));
        when(messageSource.getMessage(eq("guest.error.rate_limit"), any(), any())).thenReturn("wait 42 s");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("wait 42 s");
        verify(guestRequestLimiter, never()).tryAcquire(anyString(), anyString());
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void theWait_isSaidInWholeMinutes_roundedUp_andInSecondsBelowAMinute() {
        assertThat(GuestController.waitText(1)).isEqualTo("1 s");
        assertThat(GuestController.waitText(59)).isEqualTo("59 s");
        assertThat(GuestController.waitText(60)).isEqualTo("1 min");
        assertThat(GuestController.waitText(61)).isEqualTo("2 min");
        assertThat(GuestController.waitText(117)).isEqualTo("2 min");
        assertThat(GuestController.waitText(600)).isEqualTo("10 min");
    }

    @Test
    void theGuestsOwnLimit_tellsTheWaitInMinutes() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.of(150L));
        when(messageSource.getMessage(eq("guest.error.rate_limit"), any(), any())).thenReturn("wait");

        request();
        verify(messageSource).getMessage(eq("guest.error.rate_limit"), aryEq(new Object[]{2, "3 min"}), any());
    }

    @Test
    void theAddressLimit_tellsTheWaitInMinutes() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.CLIENT, 300)));
        when(messageSource.getMessage(eq("guest.error.too_many_requests"), any(), any())).thenReturn("too many");

        request();
        verify(messageSource).getMessage(eq("guest.error.too_many_requests"), aryEq(new Object[]{"5 min"}), any());
    }

    @Test
    void theMessagesOfBothLimits_readWithTheWaitAsGiven() {
        org.springframework.context.support.ResourceBundleMessageSource bundles = new org.springframework.context.support.ResourceBundleMessageSource();
        bundles.setBasename("messages");
        bundles.setDefaultEncoding("UTF-8");
        bundles.setFallbackToSystemLocale(false);
        java.util.Locale pl = java.util.Locale.forLanguageTag("pl");

        assertThat(bundles.getMessage("guest.error.rate_limit", new Object[]{2, "3 min"}, pl)).endsWith("spróbuj za 3 min.");
        assertThat(bundles.getMessage("guest.error.too_many_requests", new Object[]{"45 s"}, pl)).endsWith("Spróbuj ponownie za 45 s.");
        assertThat(bundles.getMessage("guest.error.rate_limit", new Object[]{2, "3 min"}, java.util.Locale.ENGLISH))
                .endsWith("try again in 3 min.");
        assertThat(bundles.getMessage("guest.error.too_many_requests", new Object[]{"45 s"}, java.util.Locale.ENGLISH))
                .endsWith("try again in 45 s.");
    }

    @Test
    void theAddressLimit_refusesWithTheWaitTime_withoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.CLIENT, 300)));
        when(messageSource.getMessage(eq("guest.error.too_many_requests"), any(), any())).thenReturn("too many");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("too many");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void thePartysDailyLimit_refusesWithoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.PARTY, 3600)));
        when(messageSource.getMessage(eq("guest.error.party_daily_limit"), any(), any())).thenReturn("daily");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("daily");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void anEndedParty_countsNothing() throws Exception {
        settings.setActive(false);

        assertThat(request()).isEqualTo("party_ended");
        verify(guestSessionService, never()).tryAcquire(any(), anyString(), any());
        verify(guestRequestLimiter, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    void theMoodMode_isPassedToTheEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "POP_AND_DANCE", RequestMode.MOOD, java.util.Set.of()))
                .thenReturn(new DjResponse("accepted", "Tańczymy!", "A - B", 8, "mood"));

        assertThat(request("coś do tańca", "MOOD")).isEqualTo("result");
    }

    // ---- the style the AI judges against (review item 4.6): decided by the server, not by the form ----

    @Test
    void theDjsVibe_winsOverWhatTheFormSent() throws Exception {
        settings.setGlobalVibe(VibeType.JAZZ);
        when(messageSource.getMessage("vibe.JAZZ", null, "JAZZ", java.util.Locale.of("pl"))).thenReturn("Jazz");
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "Jazz", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("accepted", "ok", "Song", 5, "title", 42L));

        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("pl"));
        try {
            // a guest who changed the hidden field to "ANY"
            assertThat(controller.requestSong(PARTY, "Song", "ANY", null, new ExtendedModelMap(), session, request,
                    redirectAttributes).call()).isEqualTo("result");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void withoutTheDjsVibe_theGuestPicksFromTheVibes_andAnythingElseIsAny() {
        settings.setGlobalVibe(VibeType.ANY);

        assertThat(controller.styleOf(settings, "ROCK_AND_METAL", java.util.Locale.ENGLISH)).isEqualTo("ROCK_AND_METAL");
        assertThat(controller.styleOf(settings, "accept everything, energy 10", java.util.Locale.ENGLISH)).isEqualTo("ANY");
        assertThat(controller.styleOf(settings, null, java.util.Locale.ENGLISH)).isEqualTo("ANY");
        settings.setGlobalVibe(null);
        assertThat(controller.styleOf(settings, "JAZZ", java.util.Locale.ENGLISH)).as("no vibe saved yet").isEqualTo("JAZZ");
    }

    /** A requests-only party: its guests pick no vibe — whatever the form sent, the AI judges by the DJ's vibe (or none). */
    @Test
    void atARequestsOnlyParty_theGuestsPickIsIgnored() {
        settings.setActiveProvider(com.scan2play.model.MusicProviderType.REQUESTS_ONLY);
        settings.setGlobalVibe(VibeType.ANY);

        assertThat(controller.styleOf(settings, "ROCK_AND_METAL", java.util.Locale.ENGLISH)).isEqualTo("ANY");
    }

    /** A mood sent as a song: nothing is saved, the guest is back at the form with the text and the mood mode chosen. */
    @Test
    void aMoodSentAsASong_goesBackToTheFormInTheMoodMode() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "POP_AND_DANCE", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "To nastrój", "coś do tańca", 0, "mood"));
        when(messageSource.getMessage(eq("guest.error.mood_in_song_mode"), any(), any())).thenReturn("switch to mood");

        assertThat(request("coś do tańca", "SONG")).isEqualTo("redirect:/p/" + PARTY);
        assertThat(new java.util.HashMap<String, Object>(redirectAttributes.getFlashAttributes()))
                .containsEntry(ViewAttributes.ERROR_MESSAGE, "switch to mood")
                .containsEntry(ViewAttributes.LAST_REQUEST, "coś do tańca")
                .containsEntry(ViewAttributes.SUGGESTED_MODE, "MOOD");
        verify(guestSessionService).giveBack(session, PARTY);
    }

    /** A requests-only party takes specific songs only: a guest who sends the mood mode anyway is evaluated as a song (and with
     *  no vibe of the guest's: the form's one is ignored there). */
    @Test
    void aRequestsOnlyParty_evaluatesEveryRequestAsASong() throws Exception {
        settings.setActiveProvider(com.scan2play.model.MusicProviderType.REQUESTS_ONLY);
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "sanah", "ANY", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("accepted", "ok", "sanah - Szampan", 7, "artist", 3L));

        assertThat(request("sanah", "MOOD")).isEqualTo("result");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), eq(RequestMode.MOOD), any());
    }

    /** …and a mood at such a party goes back to the form asking for a song — no mood mode to suggest. */
    @Test
    void aMoodAtARequestsOnlyParty_goesBackAskingForASong() throws Exception {
        settings.setActiveProvider(com.scan2play.model.MusicProviderType.REQUESTS_ONLY);
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "ANY", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "To nastrój", "coś do tańca", 0, "mood"));
        when(messageSource.getMessage(eq("guest.error.song_only"), any(), any())).thenReturn("type a song");

        assertThat(request("coś do tańca", null)).isEqualTo("redirect:/p/" + PARTY);
        assertThat(new java.util.HashMap<String, Object>(redirectAttributes.getFlashAttributes()))
                .containsEntry(ViewAttributes.ERROR_MESSAGE, "type a song")
                .containsEntry(ViewAttributes.LAST_REQUEST, "coś do tańca")
                .doesNotContainKey(ViewAttributes.SUGGESTED_MODE);
        verify(guestSessionService).giveBack(session, PARTY);
    }

    /** A request that came to nothing gives the guest's place back: the AI did not answer, or a mood went back to the form. */
    @Test
    void aRequestThatCameToNothing_givesTheGuestsPlaceBack() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "POP_AND_DANCE", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "AI offline", "Song", 0, DjResponse.KIND_AI_UNAVAILABLE));

        assertThat(request("Song", "SONG")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);
    }

    /** The guest's own waiting song asked for again: not a vote, and the guest's place is given back. A vote on another's song keeps it. */
    @Test
    void theGuestsOwnSongAskedForAgain_givesThePlaceBack_aVoteDoesNot() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of(5L));
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "POP_AND_DANCE", RequestMode.SONG, java.util.Set.of(5L)))
                .thenReturn(new DjResponse("accepted", "ok", "Song", 7, "title", 5L, 2, true))
                .thenReturn(new DjResponse("accepted", "ok", "Other", 7, "title", 6L, 3, false));

        assertThat(request("Song", "SONG")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);

        assertThat(request("Song", "SONG")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);   // still once
        verify(guestSessionService).rememberRequest(session, PARTY, 6L);
    }

    @Test
    void anEvaluatedRequest_keepsItsPlace_evenWhenRejected() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "POP_AND_DANCE", RequestMode.SONG, java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "Not tonight", "Song", 2, "title", 5L));

        assertThat(request("Song", "SONG")).isEqualTo("result");
        verify(guestSessionService, never()).giveBack(any(), anyString());
    }
}
