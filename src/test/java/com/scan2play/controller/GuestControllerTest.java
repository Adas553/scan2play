package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.RequestMode;
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
        when(partySettingsQueryService.getSettings(PARTY)).thenReturn(settings);
    }

    private String request() throws Exception {
        return request("Song", null);
    }

    private String request(String text, String mode) throws Exception {
        return controller.requestSong(PARTY, text, "Pop", mode, new ExtendedModelMap(), session, request, redirectAttributes).call();
    }

    @Test
    void aRequestWithinAllLimits_isEvaluated() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "Pop", RequestMode.SONG))
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
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any());
    }

    @Test
    void theAddressLimit_refusesWithTheWaitTime_withoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.CLIENT, 300)));
        when(messageSource.getMessage(eq("guest.error.too_many_requests"), any(), any())).thenReturn("too many");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("too many");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any());
    }

    @Test
    void thePartysDailyLimit_refusesWithoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.PARTY, 3600)));
        when(messageSource.getMessage(eq("guest.error.party_daily_limit"), any(), any())).thenReturn("daily");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("daily");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any());
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
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "Pop", RequestMode.MOOD))
                .thenReturn(new DjResponse("accepted", "Tańczymy!", "A - B", 8, "mood"));

        assertThat(request("coś do tańca", "MOOD")).isEqualTo("result");
    }

    /** A mood sent as a song: nothing is saved, the guest is back at the form with the text and the mood mode chosen. */
    @Test
    void aMoodSentAsASong_goesBackToTheFormInTheMoodMode() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "Pop", RequestMode.SONG))
                .thenReturn(new DjResponse("rejected", "To nastrój", "coś do tańca", 0, "mood"));
        when(messageSource.getMessage(eq("guest.error.mood_in_song_mode"), any(), any())).thenReturn("switch to mood");

        assertThat(request("coś do tańca", "SONG")).isEqualTo("redirect:/p/" + PARTY);
        assertThat(new java.util.HashMap<String, Object>(redirectAttributes.getFlashAttributes()))
                .containsEntry(ViewAttributes.ERROR_MESSAGE, "switch to mood")
                .containsEntry(ViewAttributes.LAST_REQUEST, "coś do tańca")
                .containsEntry(ViewAttributes.SUGGESTED_MODE, "MOOD");
    }
}
