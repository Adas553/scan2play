package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SongEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.scan2play.controller.ViewAttributes.*;
import static com.scan2play.service.DjService.DECISION_REJECTED;

@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
@Slf4j
public class GuestController {

    private final SongEvaluationService songEvaluationService;
    private final GuestQueueService guestQueueService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final GuestSessionService guestSessionService;
    private final GuestVoteService guestVoteService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final MessageSource messageSource;

    @GetMapping("/{partyCode}")
    public String partyIndex(@PathVariable String partyCode, Model model, HttpSession session) {
        try {
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

            if (!settings.isActive()) {
                model.addAttribute(PARTY_CODE, partyCode);   // "check again" opens the party's link
                return "party_ended";
            }

            model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
            model.addAttribute(VIBE_NOTE, settings.getVibeNote());
            model.addAttribute(DJ_NAME, settings.getDjName());
            model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
            model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
            model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
            model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, session));
            model.addAttribute(PARTY_CODE, partyCode);
            return "index";
        } catch (IllegalArgumentException e) {
            log.warn("Invalid party code access attempt: {}", partyCode);
            return REDIRECT_HOME;
        }
    }

    /**
     * The list under the request form alone (the requests sent lately, where the guest's song waits): the party page
     * fetches it again when the guest comes back to it and on "↻ Odśwież" — no timer, so a room of phones asks only when someone
     * looks. Empty when the party has ended or there is nothing to show.
     */
    @GetMapping("/{partyCode}/queue")
    public String partyQueue(@PathVariable String partyCode, Model model, HttpSession session) {
        try {
            if (partySettingsQueryService.getSettings(partyCode).isActive()) {
                model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, session));
                model.addAttribute(PARTY_CODE, partyCode);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "fragments/guest-queue :: guestQueue";
    }

    /**
     * The folded rest of the list ("Pokaż pozostałe prośby"): fetched by the page only when the guest unfolds it — with 300 requests
     * waiting, the list every guest's phone fetches again stays the first five. Empty when the party has ended.
     */
    @GetMapping("/{partyCode}/queue/more")
    public String partyQueueMore(@PathVariable String partyCode, Model model, HttpSession session) {
        try {
            if (partySettingsQueryService.getSettings(partyCode).isActive()) {
                model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, session));
                model.addAttribute(PARTY_CODE, partyCode);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "fragments/guest-queue :: moreList";
    }

    /**
     * A guest's 👍 on a waiting song of the list ({@code on=true}), or taking it back ({@code on=false}) — one vote per song, the
     * guest's own request is their vote already ({@link GuestVoteService}). Sent by guest-party.js in the background
     * ({@code X-Requested-With: fetch}): the answer is that song's row with its new votes (none when it no longer waits) and a note
     * when the 👍 did not count — the page puts only the votes in place, nothing moves.
     * Without the script (a plain form post): back to the party page.
     */
    @PostMapping("/{partyCode}/vote")
    public String vote(@PathVariable String partyCode, @RequestParam long id, @RequestParam(defaultValue = "true") boolean on,
                       @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                       Model model, HttpSession session, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        PartySettingsEntity settings;
        try {
            settings = partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String note = null;
        if (!settings.isActive()) {
            note = "guest.vote.party_ended";
        } else if (!guestSessionService.myRequestIds(session, partyCode).contains(id)) {   // the guest's own song: no 👍 on it
            Optional<Long> wait = guestRequestLimiter.tryAcquireVote(guestRequestLimiter.clientIp(request), partyCode);
            if (wait.isPresent()) {
                note = "guest.vote.too_many";
            } else {
                GuestVoteService.Result result = on ? guestVoteService.vote(session, partyCode, id)
                        : guestVoteService.takeBack(session, partyCode, id);
                if (result == GuestVoteService.Result.GONE) {
                    note = "guest.vote.gone";
                }
            }
        }
        String noteText = note == null ? null : messageSource.getMessage(note, null, LocaleContextHolder.getLocale());
        if (!"fetch".equals(requestedWith)) {
            if (noteText != null) {
                redirectAttributes.addFlashAttribute(ERROR_MESSAGE, noteText);
            }
            return "redirect:/p/" + partyCode;
        }
        if (settings.isActive()) {
            GuestQueueService.GuestQueue queue = guestQueue(partyCode, session);
            model.addAttribute(GUEST_QUEUE, queue);
            model.addAttribute(PARTY_CODE, partyCode);
            model.addAttribute(VOTE_SONG, queue.song(id).orElse(null));
        }
        model.addAttribute(VOTE_NOTE, noteText);
        return "fragments/guest-queue :: voteAnswer";
    }

    /** What the guest sees of the requests: their own marked, their 👍 shown. */
    private GuestQueueService.GuestQueue guestQueue(String partyCode, HttpSession session) {
        return guestQueueService.view(partyCode, guestSessionService.myRequestIds(session, partyCode),
                guestVoteService.myVotes(session, partyCode));
    }

    /**
     * Processes a song request asynchronously using {@link Callable} to release the Tomcat thread
     * during the AI evaluation (~2-4 seconds).
     * The HTTP connection stays open; the guest sees the result when processing completes.
     */
    @PostMapping("/{partyCode}/request")
    public Callable<String> requestSong(@PathVariable String partyCode,
                              @RequestParam String songName,
                              Model model,
                              HttpSession session,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        String clientIp = guestRequestLimiter.clientIp(request);
        Locale locale = LocaleContextHolder.getLocale();
        return () -> {
            try {
                PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

                if (!settings.isActive()) {
                    model.addAttribute(PARTY_CODE, partyCode);
                    return "party_ended";
                }

                // Nothing typed (only spaces get past the form's "required"; a POST may skip the form): back to the form before
                // any limit is used or the AI is asked — with the AI down it would be an empty row in the DJ's queue
                if (SongEvaluationService.asTyped(songName).isEmpty()) {
                    return refuse(redirectAttributes, partyCode, "guest.error.empty");
                }
                // The guest's own limit first (the DJ's setting), then the server's limits that do not need the cookie.
                // Both count the request before the evaluation.
                Optional<Long> waitTimeSeconds = guestSessionService.tryAcquire(session, partyCode, settings);
                if (waitTimeSeconds.isPresent()) {
                    return refuse(redirectAttributes, partyCode, "guest.error.rate_limit",
                            settings.getRequestLimit(), waitText(waitTimeSeconds.get()));
                }
                Optional<GuestRequestLimiter.Refusal> refusal = guestRequestLimiter.tryAcquire(clientIp, partyCode);
                if (refusal.isPresent()) {
                    return switch (refusal.get().scope()) {
                        case CLIENT -> refuse(redirectAttributes, partyCode, "guest.error.too_many_requests",
                                waitText(refusal.get().waitSeconds()));
                        case PARTY -> refuse(redirectAttributes, partyCode, "guest.error.party_daily_limit");
                    };
                }

                // The guest's earlier requests and 👍: the same song asked for again while it waits is not one more vote
                Set<Long> alreadyTheirs = new HashSet<>(guestSessionService.myRequestIds(session, partyCode));
                alreadyTheirs.addAll(guestVoteService.myVotes(session, partyCode));
                DjResponse response = songEvaluationService.evaluateAndSaveSong(partyCode, songName,
                        styleOf(settings, locale), alreadyTheirs);
                // A request that came to nothing — a mood sent back to the form, a rejected song, the guest's own song asked for
                // again — does not use the guest's limit up (the server's own limits keep counting it: against abuse)
                if (response.isMood() || response.ownSong() || DECISION_REJECTED.equalsIgnoreCase(response.decision())) {
                    guestSessionService.giveBack(session, partyCode);
                }
                if (response.isMood()) {
                    // A mood, not a song (the DJ sets the mood): nothing was saved. Back to the form with the text, asking for a song.
                    redirectAttributes.addFlashAttribute(LAST_REQUEST, songName);
                    return refuse(redirectAttributes, partyCode, "guest.error.song_only");
                }

                // Remembered in the session, so the party page can say that the guest's songs wait. The result itself is about this
                // request alone: another of the guest's waiting songs named here read as the AI's mix-up (the owner, 2026-10-07).
                // A song that already had the guest's vote (their request or 👍) stays as it was: a 👍 stays one they can take back.
                if (!response.ownSong()) {
                    guestSessionService.rememberRequest(session, partyCode, response.requestId());
                }
                model.addAttribute(RESPONSE, response);
                model.addAttribute(PARTY_CODE, partyCode);
                return "result";
            } catch (IllegalArgumentException e) {
                log.warn("Song request for unknown party code: {}", partyCode);
                return REDIRECT_HOME;
            }
        };
    }

    /**
     * The style the AI judges the request against (review item 4.6) — decided here, not by the form: the DJ's vibe when the DJ set
     * one (by its name in the guest's language), otherwise {@code ANY} (the AI judges by the DJ's vibe note, if any). The guests
     * pick no vibe: the DJ sets it.
     */
    String styleOf(PartySettingsEntity settings, Locale locale) {
        VibeType partyVibe = settings.getGlobalVibe();
        if (partyVibe != null && partyVibe != VibeType.ANY) {
            return messageSource.getMessage("vibe." + partyVibe.name(), null, partyVibe.name(), locale);
        }
        return VibeType.ANY.name();
    }

    /**
     * The guest's wait as people say it: from a minute on in whole minutes, rounded up ("3 min" — a phone at a party is not
     * read to the second), below a minute in seconds ("45 s"). The same short units in every language of the app.
     */
    static String waitText(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds + 59) / 60 + " min";
    }

    /** Back to the party page with the message of the limit that refused the request. */
    private String refuse(RedirectAttributes redirectAttributes, String partyCode, String messageKey, Object... args) {
        redirectAttributes.addFlashAttribute(ERROR_MESSAGE,
                messageSource.getMessage(messageKey, args, LocaleContextHolder.getLocale()));
        return "redirect:/p/" + partyCode;
    }
}
