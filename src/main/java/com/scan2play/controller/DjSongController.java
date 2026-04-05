package com.scan2play.controller;

import com.scan2play.service.DjService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

/**
 * Controller responsible for song queue actions performed by the DJ.
 * <p>
 * Handles:
 * <ul>
 *     <li>Marking songs as played</li>
 *     <li>Pushing songs to the Spotify queue</li>
 *     <li>Adding DJ-picked songs directly to the queue</li>
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
    public String markAsPlayed(@RequestParam Long id,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.getPartySettings(authentication, session).getPartyCode();
        djService.markSongAsPlayed(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Pushes a specific song to the Spotify queue manually.
     * Validates that the song belongs to the authenticated DJ's party (IDOR protection).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/requests/{id}/push-to-spotify")
    public String pushToSpotify(@PathVariable Long id,
                                OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.getPartySettings(authentication, session).getPartyCode();
        djService.pushToSpotify(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Adds a DJ-picked song directly to the party queue, bypassing AI evaluation.
     * Only meaningful for the YouTube provider (Auto-Pilot picks it up in the next polling cycle).
     *
     * @param partyCode The unique code of the party.
     * @param songName  The name of the song to add.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/dj-pick")
    public String addDjPick(@RequestParam String partyCode,
                            @RequestParam String songName,
                            OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        if (songName != null && !songName.isBlank()) {
            djService.addDjPick(partyCode, songName.trim());
        }
        return REDIRECT_DASHBOARD;
    }
}

