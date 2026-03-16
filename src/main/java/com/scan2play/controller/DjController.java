package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsService;
import com.scan2play.service.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * Main web controller for handling guest requests and DJ dashboard interactions.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class DjController {

    private final DjService djService;
    private final PartySettingsService partySettingsService;
    private final SongRequestRepository repository;
    private final QrCodeService qrCodeService;

    @Value("${scan2play.guest-url}")
    private String guestUrl;

    /**
     * Displays the main guest-facing page with the song request form and public queue.
     *
     * @param model Spring model to pass attributes to the view.
     * @return The name of the index view template.
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute(GLOBAL_VIBE, djService.getCurrentGlobalVibe());
        model.addAttribute(PUBLIC_QUEUE, djService.getPublicQueue());
        return "index";
    }

    /**
     * Processes a new song request submitted via the form.
     * 1. Validates & evaluates song using Gemini AI
     * 2. Saves evaluation result to database
     * 3. Displays result page with AI decision/comment
     *
     * @param songName song title (usually from QR scan or manual input)
     * @param style    desired music style/vibe (default: "90s Rock")
     * @param model    Spring MVC model for passing data to view
     * @return name of the result Thymeleaf template
     */
    @PostMapping("/request")
    public String requestSong(@RequestParam String songName,
                              @RequestParam(defaultValue = "90s Rock") String style,
                              Model model) {
        DjResponse response = djService.evaluateAndSaveSong(songName, style);
        model.addAttribute(RESPONSE, response);
        return "result";
    }

    /**
     * Displays the DJ/Admin control panel with full request history.
     * Shows all previous song evaluations stored in database.
     *
     * @param model          Spring model for view attributes.
     * @param authentication The current user's authentication token (used to identify the provider).
     * @return The name of the dashboard view template.
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, OAuth2AuthenticationToken authentication) {
        if (authentication != null) {
            String activeProvider = authentication.getAuthorizedClientRegistrationId();
            log.info("DJ logged in using: {}", activeProvider);
            model.addAttribute(ACTIVE_PROVIDER, activeProvider);
        }

        PartySettingsEntity settings = partySettingsService.getSettings();
        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(PLAYBACK_MODE, settings.getPlaybackMode());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);

        // Generate QR Code for guest URL
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);

        try {
            // Using sorting by RequestedAt Descending (newest first)
            model.addAttribute(HISTORY, repository.findAllByOrderByRequestedAtDesc());
        } catch (RuntimeException e) {
            log.error("Error while fetching song history", e);
            model.addAttribute(HISTORY, List.of());
        }
        return "dashboard";
    }

    /**
     * API endpoint returning all song requests in JSON format.
     * Can be used by:
     * - future SPA frontend
     * - mobile app
     * - external monitoring tools
     *
     * @return list of all SongRequestEntity objects, sorted from newest to oldest.
     */
    @GetMapping("/history")
    public List<SongRequestEntity> getHistory() {
        return repository.findAllByOrderByRequestedAtDesc();
    }

    /**
     * HTMX endpoint that returns a partial HTML fragment of the song request table.
     * Used for dynamic dashboard updates without a full page reload.
     *
     * @param model Spring MVC model
     * @return the "songTableBody" fragment from the dashboard template
     */
    @GetMapping("/dashboard/updates")
    public String getDashboardUpdates(Model model) {
        List<SongRequestEntity> history = repository.findAllByOrderByRequestedAtDesc();
        model.addAttribute(HISTORY, history);
        return "dashboard :: songTableBody";
    }

    /**
     * Updates the global music vibe/theme for the event.
     *
     * @param newVibe the new description of the event's atmosphere (e.g., "Techno Night")
     * @return redirects back to the dashboard view
     */
    @PostMapping("/dashboard/vibe")
    public String updateGlobalVibe(@RequestParam VibeType newVibe) {
        djService.setCurrentGlobalVibe(newVibe);
        return "redirect:/dashboard";
    }

    /**
     * Updates the active music provider for the party.
     *
     * @param activeProvider The new {@link MusicProviderType} to use for resolving tracks.
     * @return A redirect to the DJ dashboard.
     */
    @PostMapping("/dashboard/provider")
    public String updateProvider(@RequestParam MusicProviderType activeProvider) {
        djService.setActiveProvider(activeProvider);
        return "redirect:/dashboard";
    }

    /**
     * Marks a specific song request as "played", removing it from the public queue
     * but keeping it in the history log.
     *
     * @param id the ID of the song request to archive
     * @return redirects back to the dashboard
     */
    @PostMapping("/dashboard/play")
    public String markAsPlayed(@RequestParam Long id) {
        djService.markSongAsPlayed(id);
        return "redirect:/dashboard";
    }

    /**
     * Toggles the playback mode between AUTO and MANUAL.
     *
     * @return A redirect to the DJ dashboard.
     */
    @PostMapping("/dashboard/playback-mode")
    public String togglePlaybackMode() {
        djService.setPlaybackMode(
                djService.getCurrentPlaybackMode() == PlaybackMode.AUTO ? PlaybackMode.MANUAL : PlaybackMode.AUTO
        );
        return "redirect:/dashboard";
    }
}
