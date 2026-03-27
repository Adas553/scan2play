package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.QrCodeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * Main web controller for handling DJ dashboard interactions and configuration.
 * <p>
 * This controller manages the "Back Office" of the party:
 * <ul>
 *     <li>Dashboard display</li>
 *     <li>Vibe and Provider settings</li>
 *     <li>Playback controls</li>
 * </ul>
 * Guest interactions are handled by {@link GuestController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjController {

    private final DjService djService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final QrCodeService qrCodeService;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    private String cleanBaseUrl;

    /**
     * Initializes the controller.
     * Sanitizes the guest URL once at startup to avoid repeated string operations during requests.
     */
    @PostConstruct
    public void init() {
        this.cleanBaseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
    }

    /**
     * Displays the DJ/Admin control panel (Active Queue).
     *
     * @param model          Spring model for view attributes.
     * @param authentication The current user's authentication token.
     * @return The name of the dashboard view template.
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, OAuth2AuthenticationToken authentication) {
        if (authentication == null) {
            return "redirect:/login"; 
        }

        String ownerId = authentication.getName();
        log.info("DJ Dashboard access: ownerId={}", ownerId);

        PartySettingsEntity settings = partySettingsCommandService.getOrCreatePartyForDj(ownerId);
        String partyCode = settings.getPartyCode();

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());

        // --- Party State ---
        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(PLAYBACK_MODE, settings.getPlaybackMode());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);

        // --- QR Code ---
        String guestUrl = cleanBaseUrl + "/p/" + partyCode;
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);
        model.addAttribute("permanentLink", guestUrl);

        // --- Active Queue (Accepted songs only) ---
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));

        return "dashboard";
    }

    /**
     * Re-activates the party session.
     */
    @PostMapping("/start-party")
    public String startParty(OAuth2AuthenticationToken authentication) {
        if (authentication != null) {
            String ownerId = authentication.getName();
            PartySettingsEntity settings = partySettingsCommandService.getOrCreatePartyForDj(ownerId);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setActive(true));
        }
        return "redirect:/dj/dashboard";
    }

    /**
     * Displays the history of played and rejected songs.
     *
     * @param model          Spring model for view attributes.
     * @param authentication The current user's authentication token.
     * @return The name of the history view template.
     */
    @GetMapping("/history-view")
    public String historyView(Model model, OAuth2AuthenticationToken authentication) {
        if (authentication == null) {
            return "redirect:/login";
        }
        String ownerId = authentication.getName();
        PartySettingsEntity settings = partySettingsCommandService.getOrCreatePartyForDj(ownerId);
        String partyCode = settings.getPartyCode();

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());
        model.addAttribute(HISTORY, djService.getHistory(partyCode));

        return "history";
    }

    /**
     * HTMX endpoint that returns a partial HTML fragment of the song request table.
     * Used for dynamic dashboard updates without a full page reload.
     *
     * @param partyCode The unique code of the party context.
     * @param model     Spring MVC model.
     * @return returns a partial HTML fragment of the song request table (Active Queue).
     */
    @GetMapping("/dashboard/updates")
    public String getDashboardUpdates(@RequestParam String partyCode, Model model) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));
        return "dashboard :: songTableBody";
    }

    /**
     * Updates the global music vibe/theme for the event.
     *
     * @param partyCode The unique code of the party.
     * @param newVibe   The new description of the event's atmosphere (e.g., "Techno Night").
     * @return Redirects back to the dashboard view.
     */
    @PostMapping("/dashboard/vibe")
    public String updateGlobalVibe(@RequestParam String partyCode, @RequestParam VibeType newVibe) {
        djService.setCurrentGlobalVibe(partyCode, newVibe);
        return "redirect:/dj/dashboard";
    }

    /**
     * Updates the active music provider for the party.
     *
     * @param partyCode      The unique code of the party.
     * @param activeProvider The new {@link MusicProviderType} to use for resolving tracks.
     * @return Redirects back to the dashboard view.
     */
    @PostMapping("/dashboard/provider")
    public String updateProvider(@RequestParam String partyCode, @RequestParam MusicProviderType activeProvider) {
        djService.setActiveProvider(partyCode, activeProvider);
        if (activeProvider != MusicProviderType.SPOTIFY) {
            djService.setPlaybackMode(partyCode, PlaybackMode.MANUAL);
        }
        return "redirect:/dj/dashboard";
    }

    /**
     * Marks a specific song request as "played".
     *
     * @param id The ID of the song request to archive.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/play")
    public String markAsPlayed(@RequestParam Long id) {
        djService.markSongAsPlayed(id);
        return "redirect:/dj/dashboard";
    }
    
    /**
     * Pushes a specific song to the Spotify queue manually.
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/requests/{id}/push-to-spotify")
    public String pushToSpotify(@PathVariable Long id) {
        djService.pushToSpotify(id);
        return "redirect:/dj/dashboard";
    }

    /**
     * Toggles the playback mode between AUTO and MANUAL for the specific party.
     *
     * @param partyCode The unique code of the party.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/playback-mode")
    public String togglePlaybackMode(@RequestParam String partyCode) {
        djService.togglePlaybackMode(partyCode);
        return "redirect:/dj/dashboard";
    }

    /**
     * Ends the current party session without logging out the DJ.
     *
     * @param authentication The current user's authentication token.
     * @return Redirect to the DJ dashboard.
     */
    @PostMapping("/end-party")
    public String endParty(OAuth2AuthenticationToken authentication) {
        if (authentication != null) {
            String ownerId = authentication.getName();
            log.info("Ending party for DJ: {}", ownerId);
            
            PartySettingsEntity settings = partySettingsCommandService.getOrCreatePartyForDj(ownerId);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), p -> p.setActive(false));
        }
        return "redirect:/dj/dashboard";
    }
}
