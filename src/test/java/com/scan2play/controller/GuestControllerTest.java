package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestRequestLimiter.Refusal;
import com.scan2play.service.GuestRequestLimiter.Scope;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
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
    private GuestVoteService guestVoteService;
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
        org.mockito.Mockito.lenient().when(guestQueueService.view(any(), any(), any()))   // an empty list, unless a test says otherwise
                .thenReturn(new GuestQueueService.GuestQueue(java.util.List.of(), java.util.List.of(), java.util.Set.of(), null, 0, java.util.Set.of()));
    }

    private String request() throws Exception {
        return request("Song");
    }

    private String request(String text) throws Exception {
        return controller.requestSong(PARTY, text, new ExtendedModelMap(), session, request, redirectAttributes).call();
    }

    @Test
    void aRequestWithinAllLimits_isEvaluated() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "ANY", java.util.Set.of()))
                .thenReturn(new DjResponse("ACCEPTED", "Song", "ok", "title", 42L));

        assertThat(request()).isEqualTo("result");
        // the guest's request is remembered, so the party page can say where it waits
        verify(guestSessionService).rememberRequest(session, PARTY, 42L);
    }

    @Test
    void theListAlone_isTheFragmentThePageFetchesAgain() {
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue(java.util.List.of(), java.util.List.of(), java.util.Set.of(), null, 0, java.util.Set.of());
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of());
        when(guestQueueService.view(PARTY, java.util.Set.of(), java.util.Set.of())).thenReturn(queue);
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
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue(java.util.List.of(), java.util.List.of(), java.util.Set.of(42L), "Mine", 1, java.util.Set.of());
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of(42L));
        when(guestQueueService.view(PARTY, java.util.Set.of(42L), java.util.Set.of())).thenReturn(queue);
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

    /**
     * A request with nothing in it (only spaces — the form's "required" lets them through — or a POST without the form) goes back
     * to the form at once: no limit is used, the AI is not asked, and nothing reaches the DJ (with the AI down it would have
     * been an empty row in the queue).
     */
    @Test
    void anEmptyRequest_goesBackToTheForm_withoutALimitOrTheAi() throws Exception {
        when(messageSource.getMessage(eq("guest.error.empty"), any(), any())).thenReturn("type a song");

        for (String empty : new String[] {"", "   ", " \n\t "}) {
            assertThat(request(empty)).isEqualTo("redirect:/p/" + PARTY);
            assertThat(redirectAttributes.getFlashAttributes().get(ViewAttributes.ERROR_MESSAGE)).isEqualTo("type a song");
        }
        verify(guestSessionService, never()).tryAcquire(any(), anyString(), any());
        verify(guestRequestLimiter, never()).tryAcquire(anyString(), anyString());
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any());
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

    // ---- the style the AI judges against (review item 4.6): decided by the server, not by the form ----

    @Test
    void theDjsVibe_isWhatTheAiJudgesBy() throws Exception {
        settings.setGlobalVibe(VibeType.JAZZ);
        when(messageSource.getMessage("vibe.JAZZ", null, "JAZZ", java.util.Locale.of("pl"))).thenReturn("Jazz");
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "Jazz", java.util.Set.of()))
                .thenReturn(new DjResponse("accepted", "ok", "Song", "title", 42L));

        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("pl"));
        try {
            assertThat(request("Song")).isEqualTo("result");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    /** The guests pick no vibe: without the DJ's vibe the AI judges by "any" (and the DJ's note, if any). */
    @Test
    void withoutTheDjsVibe_theStyleIsAny() {
        settings.setGlobalVibe(VibeType.ANY);
        assertThat(controller.styleOf(settings, java.util.Locale.ENGLISH)).isEqualTo("ANY");
        settings.setGlobalVibe(null);
        assertThat(controller.styleOf(settings, java.util.Locale.ENGLISH)).as("no vibe saved yet").isEqualTo("ANY");
    }

    /** A mood, not a song: nothing is saved, the guest is back at the form with the text, asked for a song; the place is given back. */
    @Test
    void aMood_goesBackToTheFormAskingForASong() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "coś do tańca", "ANY", java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "To nastrój", "coś do tańca", "mood"));
        when(messageSource.getMessage(eq("guest.error.song_only"), any(), any())).thenReturn("type a song");

        assertThat(request("coś do tańca")).isEqualTo("redirect:/p/" + PARTY);
        assertThat(new java.util.HashMap<String, Object>(redirectAttributes.getFlashAttributes()))
                .containsEntry(ViewAttributes.ERROR_MESSAGE, "type a song")
                .containsEntry(ViewAttributes.LAST_REQUEST, "coś do tańca");
        verify(guestSessionService).giveBack(session, PARTY);
    }

    /** The AI down: the request went to the DJ unchecked (SongEvaluationService) — it counts like any request. */
    @Test
    void aRequestPassedOnUnchecked_keepsItsPlace() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "ANY", java.util.Set.of()))
                .thenReturn(new DjResponse("accepted", "Sent to the DJ", "Song", DjResponse.KIND_UNCHECKED, 7L));

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService, never()).giveBack(any(), anyString());
    }

    /** The guest's own waiting song asked for again: not a vote, and the guest's place is given back. A vote on another's song keeps it. */
    @Test
    void theGuestsOwnSongAskedForAgain_givesThePlaceBack_aVoteDoesNot() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of(5L));
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "ANY", java.util.Set.of(5L)))
                .thenReturn(new DjResponse("accepted", "ok", "Song", "title", 5L, 2, true))
                .thenReturn(new DjResponse("accepted", "ok", "Other", "title", 6L, 3, false));

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);   // still once
        verify(guestSessionService).rememberRequest(session, PARTY, 6L);
        verify(guestQueueService, never()).view(any(), any(), any());   // the result is about this request alone
    }

    /**
     * A rejected request does not use the guest's own limit up (the owner, 2026-10-04: "nieudane próby się liczą … a nie powinno"):
     * with a limit of 2, two rejected songs would leave the guest waiting with nothing in the queue. The server's limits per network
     * and per party still count it (against abuse: every request is an AI call).
     */
    @Test
    void aRejectedRequest_givesThePlaceBack_anAcceptedOneKeepsIt() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "ANY", java.util.Set.of()))
                .thenReturn(new DjResponse("rejected", "Not tonight", "Song", "title", 5L))
                .thenReturn(new DjResponse("accepted", "Yes", "Song", "title", 6L));

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);   // still once
    }

    // ---- A guest's 👍 on the list (the owner, 2026-10-08: one vote per song) ----

    /** A song the guest gave their 👍 is theirs when they ask for it again: not one more vote, and still a 👍 they can take back. */
    @Test
    void aSongWithTheGuestsVote_askedForAgain_isNotCountedAgain_andStaysAVote() throws Exception {
        when(guestSessionService.tryAcquire(session, PARTY, settings)).thenReturn(Optional.empty());
        when(guestRequestLimiter.tryAcquire(IP, PARTY)).thenReturn(Optional.empty());
        when(guestVoteService.myVotes(session, PARTY)).thenReturn(java.util.Set.of(9L));
        when(songEvaluationService.evaluateAndSaveSong(PARTY, "Song", "ANY", java.util.Set.of(9L)))
                .thenReturn(new DjResponse("accepted", "ok", "Song", "title", 9L, 4, true));

        assertThat(request("Song")).isEqualTo("result");
        verify(guestSessionService).giveBack(session, PARTY);
        verify(guestSessionService, never()).rememberRequest(any(), anyString(), any());   // not "Twoja": the 👍 stays
    }

    private String vote(long id, boolean on, String requestedWith, ExtendedModelMap model) {
        return controller.vote(PARTY, id, on, requestedWith, model, session, request, redirectAttributes);
    }

    @Test
    void aVoteInTheBackground_isCounted_andThatSongComesBackWithItsVotes() {
        when(guestRequestLimiter.tryAcquireVote(IP, PARTY)).thenReturn(Optional.empty());
        when(guestVoteService.vote(session, PARTY, 7L)).thenReturn(GuestVoteService.Result.COUNTED);
        com.scan2play.entity.SongRequestEntity song = com.scan2play.entity.SongRequestEntity.builder().id(7L).songName("Song").votes(2).build();
        com.scan2play.entity.SongRequestEntity other = com.scan2play.entity.SongRequestEntity.builder().id(8L).songName("Other").build();
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue(java.util.List.of(other), java.util.List.of(song),
                java.util.Set.of(), null, 0, java.util.Set.of(7L));
        when(guestQueueService.view(PARTY, java.util.Set.of(), java.util.Set.of())).thenReturn(queue);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(vote(7L, true, "fetch", model)).as("that song's row only: the page moves nothing")
                .isEqualTo("fragments/guest-queue :: voteAnswer");
        assertThat(model).containsEntry(ViewAttributes.VOTE_SONG, song).containsEntry(ViewAttributes.PARTY_CODE, PARTY)
                .containsEntry(ViewAttributes.VOTE_NOTE, null);
    }

    /** The folded rest of the list, fetched when the guest unfolds it. */
    @Test
    void theRestOfTheList_isAFragmentOfItsOwn() {
        GuestQueueService.GuestQueue queue = new GuestQueueService.GuestQueue(java.util.List.of(), java.util.List.of(),
                java.util.Set.of(), null, 0, java.util.Set.of());
        when(guestQueueService.view(PARTY, java.util.Set.of(), java.util.Set.of())).thenReturn(queue);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.partyQueueMore(PARTY, model, session)).isEqualTo("fragments/guest-queue :: moreList");
        assertThat(model).containsEntry(ViewAttributes.GUEST_QUEUE, queue);
    }

    @Test
    void takingTheVoteBack_goesToTheVoteService() {
        when(guestRequestLimiter.tryAcquireVote(IP, PARTY)).thenReturn(Optional.empty());
        when(guestVoteService.takeBack(session, PARTY, 7L)).thenReturn(GuestVoteService.Result.TAKEN_BACK);

        vote(7L, false, "fetch", new ExtendedModelMap());

        verify(guestVoteService).takeBack(session, PARTY, 7L);
        verify(guestVoteService, never()).vote(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aVoteOnASongThatIsGone_saysSo() {
        when(guestRequestLimiter.tryAcquireVote(IP, PARTY)).thenReturn(Optional.empty());
        when(guestVoteService.vote(session, PARTY, 7L)).thenReturn(GuestVoteService.Result.GONE);
        when(messageSource.getMessage(eq("guest.vote.gone"), any(), any())).thenReturn("gone");
        ExtendedModelMap model = new ExtendedModelMap();

        vote(7L, true, "fetch", model);

        assertThat(model).containsEntry(ViewAttributes.VOTE_NOTE, "gone");
    }

    /** The guest's own request is their vote already: no 👍 on it, nothing counted, no limit used. */
    @Test
    void noVoteOnTheGuestsOwnRequest() {
        when(guestSessionService.myRequestIds(session, PARTY)).thenReturn(java.util.Set.of(7L));

        vote(7L, true, "fetch", new ExtendedModelMap());

        verify(guestVoteService, never()).vote(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(guestRequestLimiter, never()).tryAcquireVote(anyString(), anyString());
    }

    /** The network's limit of votes: nothing counted, a note. */
    @Test
    void tooManyVotesFromOneNetwork_countNothing() {
        when(guestRequestLimiter.tryAcquireVote(IP, PARTY)).thenReturn(Optional.of(30L));
        when(messageSource.getMessage(eq("guest.vote.too_many"), any(), any())).thenReturn("too many");
        ExtendedModelMap model = new ExtendedModelMap();

        vote(7L, true, "fetch", model);

        assertThat(model).containsEntry(ViewAttributes.VOTE_NOTE, "too many");
        verify(guestVoteService, never()).vote(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** Without the script (a plain form post): back to the party page, the note as the page's message. */
    @Test
    void aVoteWithoutTheScript_goesBackToThePartyPage() {
        when(guestRequestLimiter.tryAcquireVote(IP, PARTY)).thenReturn(Optional.empty());
        when(guestVoteService.vote(session, PARTY, 7L)).thenReturn(GuestVoteService.Result.GONE);
        when(messageSource.getMessage(eq("guest.vote.gone"), any(), any())).thenReturn("gone");

        assertThat(vote(7L, true, null, new ExtendedModelMap())).isEqualTo("redirect:/p/" + PARTY);
        assertThat(new java.util.HashMap<String, Object>(redirectAttributes.getFlashAttributes()))
                .containsEntry(ViewAttributes.ERROR_MESSAGE, "gone");
    }

    /** The party ended: nothing counted, a note, no list. */
    @Test
    void aVoteAfterThePartyEnded_countsNothing() {
        settings.setActive(false);
        when(messageSource.getMessage(eq("guest.vote.party_ended"), any(), any())).thenReturn("ended");
        ExtendedModelMap model = new ExtendedModelMap();

        vote(7L, true, "fetch", model);

        assertThat(model).containsEntry(ViewAttributes.VOTE_NOTE, "ended").doesNotContainKey(ViewAttributes.GUEST_QUEUE);
        verify(guestVoteService, never()).vote(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
