package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SongEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.concurrent.Callable;

import static com.scan2play.controller.ViewAttributes.*;

@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
@Slf4j
public class GuestController {

    private final DjService djService;
    private final SongEvaluationService songEvaluationService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final GuestSessionService guestSessionService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final MessageSource messageSource;

    @GetMapping("/{partyCode}")
    public String partyIndex(@PathVariable String partyCode, Model model) {
        try {
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
            
            if (!settings.isActive()) {
                return "party_ended";
            }
            
            model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
            model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
            model.addAttribute(PUBLIC_QUEUE, djService.getPublicQueue(partyCode));
            model.addAttribute(PARTY_CODE, partyCode);
            return "index";
        } catch (IllegalArgumentException e) {
            log.warn("Invalid party code access attempt: {}", partyCode);
            return REDIRECT_HOME;
        }
    }

    /**
     * Processes a song request asynchronously using {@link Callable} to release the Tomcat thread
     * during the AI evaluation and Spotify API calls (~2-4 seconds).
     * The HTTP connection stays open; the guest sees the result when processing completes.
     */
    @PostMapping("/{partyCode}/request")
    public Callable<String> requestSong(@PathVariable String partyCode,
                              @RequestParam String songName,
                              @RequestParam(defaultValue = "90s Rock") String style,
                              Model model,
                              HttpSession session,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        String clientIp = guestRequestLimiter.clientIp(request);
        return () -> {
            try {
                PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);

                if (!settings.isActive()) {
                    return "party_ended";
                }

                // The guest's own limit first (the DJ's setting), then the server's limits that do not need the cookie.
                // Both count the request before the evaluation.
                Optional<Long> waitTimeSeconds = guestSessionService.tryAcquire(session, partyCode, settings);
                if (waitTimeSeconds.isPresent()) {
                    return refuse(redirectAttributes, partyCode, "guest.error.rate_limit",
                            settings.getRequestLimit(), waitTimeSeconds.get());
                }
                Optional<GuestRequestLimiter.Refusal> refusal = guestRequestLimiter.tryAcquire(clientIp, partyCode);
                if (refusal.isPresent()) {
                    return switch (refusal.get().scope()) {
                        case CLIENT -> refuse(redirectAttributes, partyCode, "guest.error.too_many_requests",
                                refusal.get().waitSeconds());
                        case PARTY -> refuse(redirectAttributes, partyCode, "guest.error.party_daily_limit");
                    };
                }

                DjResponse response = songEvaluationService.evaluateAndSaveSong(partyCode, songName, style);

                model.addAttribute(RESPONSE, response);
                model.addAttribute(PARTY_CODE, partyCode);
                return "result";
            } catch (IllegalArgumentException e) {
                log.warn("Song request for unknown party code: {}", partyCode);
                return REDIRECT_HOME;
            }
        };
    }

    /** Back to the party page with the message of the limit that refused the request. */
    private String refuse(RedirectAttributes redirectAttributes, String partyCode, String messageKey, Object... args) {
        redirectAttributes.addFlashAttribute(ERROR_MESSAGE,
                messageSource.getMessage(messageKey, args, LocaleContextHolder.getLocale()));
        return "redirect:/p/" + partyCode;
    }
}
