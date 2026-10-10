package com.scan2play.controller;

import com.scan2play.model.StaffPermission;
import com.scan2play.service.DjService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

/**
 * Controller responsible for song queue actions performed by the DJ.
 * <p>
 * Every action works on the party the page names ({@code partyCode}; none: the default panel, a page of an older version) and
 * checks what the person may do there ({@link DjSessionHelper#require}, V32).
 * <p>
 * Handles:
 * <ul>
 *     <li>Marking songs as played</li>
 *     <li>Skipping a song, clearing the queue</li>
 * </ul>
 * Dashboard views are handled by {@link DjDashboardController}.
 * Party settings are handled by {@link DjPartySettingsController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
public class DjSongController {

    private final DjService djService;
    private final DjSessionHelper sessionHelper;

    /**
     * Marks a specific song request as "played".
     * Validates that the song belongs to the authenticated DJ's party (IDOR protection).
     *
     * @param id The ID of the song request to archive.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/play")
    public String markAsPlayed(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.markSongAsPlayed(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Skips a waiting song request: it leaves the queue as rejected (a song the DJ does not have, or does not want now).
     * Validates that the song belongs to the authenticated DJ's party (IDOR protection).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/dismiss")
    public String dismiss(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.dismissSong(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Puts a request the DJ skipped back in the queue ("Cofnij" right after the skip, "↩ Przywróć" in the history). Only the
     * authenticated DJ's own party, only a skipped request ({@link DjService#restoreSkippedSong}).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard (the standalone history page's form lands there, with the song in the queue).
     */
    @PostMapping("/dashboard/restore")
    public String restore(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.restoreSkippedSong(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Counts a tip for a song ("💸", V28) — or takes one back ({@code add=false}) — at a song in the queue. Only the
     * authenticated DJ's own party: the party code comes from the session, the song must be one of its ({@link DjService#countTip}).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/tip-count")
    public String countTip(@RequestParam Long id, @RequestParam(defaultValue = "true") boolean add,
                           @RequestParam(required = false) String partyCode,
                           OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.TIPS, authentication, session).getPartyCode();
        djService.countTip(id, ownerPartyCode, add);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Clears the DJ's queue: every waiting request leaves it as rejected (it stays in the history). Only the authenticated DJ's own
     * party — the party code comes from the session, never from the form.
     *
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/clear-queue")
    public String clearQueue(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.CLEAR_QUEUE, authentication, session).getPartyCode();
        djService.clearQueue(ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Clears the DJ's history: the requests that played or were rejected are deleted ({@link DjService#clearHistory}). Only the
     * authenticated DJ's own party — the party code comes from the session, never from the form.
     *
     * @return Redirects to the standalone history page (the dashboard's History tab sends it in the background and reloads the tab).
     */
    @PostMapping("/dashboard/clear-history")
    public String clearHistory(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        // the owner alone (V30): the staff work the queue, the history is the owner's record of the evenings
        String ownerPartyCode = sessionHelper.requireOwner(partyCode, authentication, session).getPartyCode();
        djService.clearHistory(ownerPartyCode);
        return "redirect:/dj/history-view?party=" + ownerPartyCode;
    }
}

