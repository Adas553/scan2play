package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
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
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

import static com.scan2play.controller.ViewAttributes.*;

@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
@Slf4j
public class GuestController {

    private final SongEvaluationService songEvaluationService;
    private final GuestQueueService guestQueueService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final GuestSessionService guestSessionService;
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
            model.addAttribute(GUEST_QUEUE, guestQueueService.view(partyCode, guestSessionService.myRequestIds(session, partyCode)));
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
                model.addAttribute(GUEST_QUEUE, guestQueueService.view(partyCode, guestSessionService.myRequestIds(session, partyCode)));
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "fragments/guest-queue :: guestQueue";
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

                // The guest's earlier requests: the same song asked for again while it waits is not one more vote
                DjResponse response = songEvaluationService.evaluateAndSaveSong(partyCode, songName,
                        styleOf(settings, locale), guestSessionService.myRequestIds(session, partyCode));
                // A request that came to nothing — a mood sent back to the form, the guest's own song asked for again — does not
                // use the guest's limit up (the server's own limits keep counting it: against abuse)
                if (response.isMood() || response.ownSong()) {
                    guestSessionService.giveBack(session, partyCode);
                }
                if (response.isMood()) {
                    // A mood, not a song (the DJ sets the mood): nothing was saved. Back to the form with the text, asking for a song.
                    redirectAttributes.addFlashAttribute(LAST_REQUEST, songName);
                    return refuse(redirectAttributes, partyCode, "guest.error.song_only");
                }

                // Remembered in the session, so the party page (and this result) can say where the guest's song waits
                guestSessionService.rememberRequest(session, partyCode, response.requestId());
                model.addAttribute(RESPONSE, response);
                model.addAttribute(PARTY_CODE, partyCode);
                model.addAttribute(GUEST_QUEUE, guestQueueService.view(partyCode, guestSessionService.myRequestIds(session, partyCode)));
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
