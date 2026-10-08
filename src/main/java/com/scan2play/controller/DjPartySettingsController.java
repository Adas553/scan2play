package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.VibeType;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.util.SocialLinks;
import com.scan2play.util.Texts;
import com.scan2play.util.TipLinks;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

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
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setActive(true));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Ends the current party session without logging out the DJ.
     */
    @PostMapping("/end-party")
    public String endParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        log.info("Ending party for DJ: {}", authentication.getName());
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        partySettingsCommandService.updateSettings(settings.getPartyCode(), p -> p.setActive(false));
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
     * How the AI words its comment to the guest (V22): classic, funny, lightly sarcastic, sarcastic or short. An unknown value is a
     * 400 (the enum binding), as for the vibe.
     */
    @PostMapping("/dashboard/comment-style")
    public String updateCommentStyle(@RequestParam String partyCode, @RequestParam CommentStyle commentStyle,
                                     OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setCommentStyle(commentStyle));
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
     * The DJ's profiles (V24) the guests see under "🎧 Gra: …" and on the QR print: each one "@name", a name, or a link copied from
     * the site, kept as an https address on that site ({@link SocialLinks}); empty clears it. 400 and nothing saved when one of them
     * is not a profile on its site.
     */
    @PostMapping("/dashboard/dj-links")
    public String updateDjLinks(@RequestParam String partyCode, @RequestParam(required = false) String instagram,
                                @RequestParam(required = false) String facebook, @RequestParam(required = false) String tiktok,
                                OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String instagramUrl;
        String facebookUrl;
        String tiktokUrl;
        try {
            instagramUrl = SocialLinks.instagram(Texts.oneLine(instagram, PartySettingsEntity.LINK_MAX));
            facebookUrl = SocialLinks.facebook(Texts.oneLine(facebook, PartySettingsEntity.LINK_MAX));
            tiktokUrl = SocialLinks.tiktok(Texts.oneLine(tiktok, PartySettingsEntity.LINK_MAX));
        } catch (IllegalArgumentException e) {
            log.info("Party [{}]: the DJ's links not saved — {}", partyCode, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setInstagramUrl(instagramUrl);
            s.setFacebookUrl(facebookUrl);
            s.setTiktokUrl(tiktokUrl);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * The DJ's tip link (V27): the guests see "💸 Napiwek dla DJ-a" on the party page and under an accepted request, and the link
     * on the QR print. Kept as an https address on one of the services ({@link TipLinks}); empty clears it. 400 and nothing saved
     * when it is not a page there.
     */
    @PostMapping("/dashboard/tip-link")
    public String updateTipLink(@RequestParam String partyCode, @RequestParam(required = false) String tip,
                                OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String tipUrl;
        try {
            tipUrl = TipLinks.tipUrl(Texts.oneLine(tip, PartySettingsEntity.LINK_MAX));
        } catch (IllegalArgumentException e) {
            log.info("Party [{}]: the tip link not saved — {}", partyCode, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        partySettingsCommandService.updateSettings(partyCode, s -> s.setTipUrl(tipUrl));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the rate limiting and duplicate checking parameters for the party.
     */
    @PostMapping("/dashboard/limits")
    public String updateLimits(@RequestParam String partyCode,
                               @RequestParam double requestLimit,
                               @RequestParam double cooldownMinutes,
                               @RequestParam double duplicateCheckWindow,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);

        // Whole numbers within bounds (review item 5.4): the duplicate window is read from the database and sent to the AI with
        // every guest's request, so it has a ceiling; the guest limit's own ceilings only keep the numbers sensible. All three
        // the same way: a number with a fraction is rounded, not refused.
        int safeRequestLimit = wholeWithin(requestLimit, 1, MAX_REQUEST_LIMIT);
        int safeCooldownMinutes = wholeWithin(cooldownMinutes, 1, MAX_COOLDOWN_MINUTES);
        int safeDuplicateCheckWindow = wholeWithin(duplicateCheckWindow, 0, MAX_DUPLICATE_CHECK_WINDOW);

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
        String ownerId = authentication.getName();
        log.info("Account deletion requested by ownerId={}", ownerId);
        accountDeletionService.deleteAllUserData(ownerId);
        session.invalidate();
        return REDIRECT_HOME;
    }

    /** {@code value} rounded to a whole number within {@code min}..{@code max}; not a number at all is {@code min}. */
    static int wholeWithin(double value, int min, int max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return (int) Math.round(Math.min(max, Math.max(min, value)));
    }
}

