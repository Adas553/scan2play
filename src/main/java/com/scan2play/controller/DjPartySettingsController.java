package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.VibeType;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.util.Texts;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * Controller responsible for party configuration and lifecycle management.
 * <p>
 * Handles:
 * <ul>
 *     <li>Start / end party</li>
 *     <li>Vibe, who plays, and rate limits</li>
 *     <li>Account deletion (required by Google API Services User Data Policy)</li>
 * </ul>
 * Dashboard views are handled by {@link DjDashboardController}.
 * Song queue actions are handled by {@link DjSongController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjPartySettingsController {

    /** The most songs the duplicate check looks back at (each is a line of the AI's prompt for every guest's request). */
    static final int MAX_DUPLICATE_CHECK_WINDOW = 50;
    static final int MAX_REQUEST_LIMIT = 100;
    /** A day. */
    static final int MAX_COOLDOWN_MINUTES = 1440;

    private final PartySettingsCommandService partySettingsCommandService;
    private final AccountDeletionService accountDeletionService;
    private final DjSessionHelper sessionHelper;

    /**
     * Re-activates the party session.
     */
    @PostMapping("/start-party")
    public String startParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setActive(true));
        }
        return REDIRECT_DASHBOARD;
    }

    /**
     * Ends the current party session without logging out the DJ.
     */
    @PostMapping("/end-party")
    public String endParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            log.info("Ending party for DJ: {}", authentication.getName());
            PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), p -> p.setActive(false));
        }
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the global music vibe/theme for the event.
     */
    @PostMapping("/dashboard/vibe")
    public String updateGlobalVibe(@RequestParam String partyCode, @RequestParam VibeType newVibe,
                                   OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setGlobalVibe(newVibe));
        return REDIRECT_DASHBOARD;
    }

    /**
     * The DJ's own words about the vibe (V16): one line, at most {@value PartySettingsEntity#VIBE_NOTE_MAX} characters; empty
     * clears it. The AI is given it with every request, the guests see it on the party page.
     */
    @PostMapping("/dashboard/vibe-note")
    public String updateVibeNote(@RequestParam String partyCode, @RequestParam(required = false) String vibeNote,
                                 OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String note = Texts.oneLine(vibeNote, PartySettingsEntity.VIBE_NOTE_MAX);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setVibeNote(note.isEmpty() ? null : note));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Who plays (V17), e.g. "DJ Koko": one line, at most {@value PartySettingsEntity#DJ_NAME_MAX} characters; empty clears it. The
     * guests see it on the party page ("🎧 Gra: DJ Koko").
     */
    @PostMapping("/dashboard/dj-name")
    public String updateDjName(@RequestParam String partyCode, @RequestParam(required = false) String djName,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String name = Texts.oneLine(djName, PartySettingsEntity.DJ_NAME_MAX);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setDjName(name.isEmpty() ? null : name));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the rate limiting and duplicate checking parameters for the party.
     */
    @PostMapping("/dashboard/limits")
    public String updateLimits(@RequestParam String partyCode,
                               @RequestParam double requestLimit,
                               @RequestParam double cooldownMinutes,
                               @RequestParam int duplicateCheckWindow,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);

        // Whole numbers within bounds (review item 5.4): the duplicate window is read from the database and sent to the AI with
        // every guest's request, so it has a ceiling; the guest limit's own ceilings only keep the numbers sensible.
        int safeRequestLimit = clamp((int) Math.round(requestLimit), 1, MAX_REQUEST_LIMIT);
        int safeCooldownMinutes = clamp((int) Math.round(cooldownMinutes), 1, MAX_COOLDOWN_MINUTES);
        int safeDuplicateCheckWindow = clamp(duplicateCheckWindow, 0, MAX_DUPLICATE_CHECK_WINDOW);

        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setRequestLimit(safeRequestLimit);
            s.setCooldownMinutes(safeCooldownMinutes);
            s.setDuplicateCheckWindow(safeDuplicateCheckWindow);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * Deletes all data associated with the currently logged-in DJ account.
     * Required by Google API Services User Data Policy — users must be able to delete their data.
     * After deletion, the session is invalidated and the user is redirected to the home page.
     */
    @PostMapping("/delete-account")
    public String deleteAccount(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            String ownerId = authentication.getName();
            log.info("Account deletion requested by ownerId={}", ownerId);
            accountDeletionService.deleteAllUserData(ownerId);
            session.invalidate();
        }
        return REDIRECT_HOME;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}

