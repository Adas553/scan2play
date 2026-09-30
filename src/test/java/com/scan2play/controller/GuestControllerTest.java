package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.service.DjService;
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
    private DjService djService;
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
        when(guestRequestLimiter.clientIp(request)).thenReturn(IP);
        when(partySettingsQueryService.getSettings(PARTY)).thenReturn(settings);
    }

    private String request() throws Exception {
        return controller.requestSong(PARTY, "Song", "Pop", new ExtendedModelMap(), session, request, redirectAttributes).call();
    }

    @Test
    void aRequestWithinAllLimits_isEvaluated() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "Pop"))
                .thenReturn(new DjResponse("ACCEPTED", "Song", "ok", 5));

        assertThat(request()).isEqualTo("result");
    }

    @Test
    void theGuestsOwnLimit_refusesFirst_withoutTouchingTheServerLimits() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.of(42L));
        when(messageSource.getMessage(eq("guest.error.rate_limit"), any(), any())).thenReturn("wait 42 s");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("wait 42 s");
        verify(guestRequestLimiter, never()).tryAcquire(anyString(), anyString());
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString());
    }

    @Test
    void theAddressLimit_refusesWithTheWaitTime_withoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.CLIENT, 300)));
        when(messageSource.getMessage(eq("guest.error.too_many_requests"), any(), any())).thenReturn("too many");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("too many");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString());
    }

    @Test
    void thePartysDailyLimit_refusesWithoutAnEvaluation() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.of(new Refusal(Scope.PARTY, 3600)));
        when(messageSource.getMessage(eq("guest.error.party_daily_limit"), any(), any())).thenReturn("daily");

        assertThat(request()).isEqualTo("redirect:/p/" + PARTY);
        assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("daily");
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString());
    }

    @Test
    void anEndedParty_countsNothing() throws Exception {
        settings.setActive(false);

        assertThat(request()).isEqualTo("party_ended");
        verify(guestSessionService, never()).tryAcquire(any(), anyString(), any());
        verify(guestRequestLimiter, never()).tryAcquire(anyString(), anyString());
    }
}
