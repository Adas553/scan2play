package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
 *     <li>Vibe, rate limits, and playback mode settings</li>
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
    public String updateGlobalVibe(@RequestParam String partyCode, @RequestParam VibeType newVibe) {
        partySettingsCommandService.updateSettings(partyCode, s -> s.setGlobalVibe(newVibe));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the rate limiting and duplicate checking parameters for the party.
     */
    @PostMapping("/dashboard/limits")
    public String updateLimits(@RequestParam String partyCode,
                               @RequestParam double requestLimit,
                               @RequestParam double cooldownMinutes,
                               @RequestParam int duplicateCheckWindow) {

        // Convert to integers and ensure minimum values
        int safeRequestLimit = Math.max(1, (int) Math.round(requestLimit));
        int safeCooldownMinutes = Math.max(1, (int) Math.round(cooldownMinutes));
        int safeDuplicateCheckWindow = Math.max(0, duplicateCheckWindow);

        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setRequestLimit(safeRequestLimit);
            s.setCooldownMinutes(safeCooldownMinutes);
            s.setDuplicateCheckWindow(safeDuplicateCheckWindow);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * Toggles the playback mode between AUTO and MANUAL for the specific party.
     */
    @PostMapping("/dashboard/playback-mode")
    public String togglePlaybackMode(@RequestParam String partyCode) {
        partySettingsCommandService.updateSettings(partyCode, s -> {
            PlaybackMode newMode = (s.getPlaybackMode() == PlaybackMode.AUTO)
                    ? PlaybackMode.MANUAL
                    : PlaybackMode.AUTO;
            s.setPlaybackMode(newMode);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * Saves or clears the YouTube fallback playlist URL.
     * When the guest queue is empty, Auto-Pilot plays this playlist as background music.
     * <p>
     * Returns 200 OK with the extracted playlist/video ID in the {@code X-Fallback-Id}
     * response header. The client-side Auto-Pilot reads this header directly — URL parsing
     * logic lives exclusively in {@link DjDashboardController#extractPlaylistId(String)}.
     * <p>
     * This endpoint is YouTube-only. All YouTube dashboard forms are AJAX-intercepted,
     * so a redirect is not needed (the AJAX handler reads the header instead).
     *
     * @param partyCode          The unique code of the party.
     * @param fallbackPlaylistUrl YouTube playlist URL or ID (blank = clear).
     */
    @PostMapping("/dashboard/fallback-playlist")
    public ResponseEntity<Void> updateFallbackPlaylist(@RequestParam String partyCode,
                                                       @RequestParam(required = false) String fallbackPlaylistUrl) {
        String sanitized = (fallbackPlaylistUrl != null && !fallbackPlaylistUrl.isBlank())
                ? fallbackPlaylistUrl.trim()
                : null;
        partySettingsCommandService.updateSettings(partyCode, s -> s.setFallbackPlaylistUrl(sanitized));

        String extractedId = DjDashboardController.extractPlaylistId(sanitized);
        log.info("Party [{}]: Fallback playlist updated to: {} (extracted: {})",
                partyCode, sanitized != null ? sanitized : "(cleared)", extractedId);

        return ResponseEntity.ok()
                .header("X-Fallback-Id", extractedId != null ? extractedId : "")
                .build();
    }

    /**
     * Toggles shuffle mode for the fallback playlist.
     * Returns 200 OK with the new shuffle state in the {@code X-Fallback-Shuffle} header.
     */
    @PostMapping("/dashboard/fallback-shuffle")
    public ResponseEntity<Void> toggleFallbackShuffle(@RequestParam String partyCode) {
        PartySettingsEntity updated = partySettingsCommandService.updateSettings(partyCode,
                s -> s.setFallbackShuffle(!s.isFallbackShuffle()));

        log.info("Party [{}]: Fallback shuffle toggled to {}", partyCode, updated.isFallbackShuffle());

        return ResponseEntity.ok()
                .header("X-Fallback-Shuffle", String.valueOf(updated.isFallbackShuffle()))
                .build();
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
}

