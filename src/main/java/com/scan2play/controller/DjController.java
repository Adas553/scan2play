package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsService;
import com.scan2play.service.QrCodeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

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
@RequiredArgsConstructor
@Slf4j
public class DjController {

    private final DjService djService;
    private final PartySettingsService partySettingsService;
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
     * Redirects the root URL to the DJ dashboard.
     *
     * @return The redirect view name.
     */
    @GetMapping("/")
    public String redirectToDashboard() {
        return "redirect:/dashboard";
    }

    /**
     * Displays the DJ/Admin control panel.
     * <p>
     * This method:
     * 1. Identifies the active party (currently defaults to the first one found for MVP).
     * 2. Generates a QR code linking to the guest page for this specific party.
     * 3. Fetches current settings (Vibe, Provider, Playback Mode).
     * 4. Loads the song request history.
     * </p>
     *
     * @param model          Spring model for view attributes.
     * @param authentication The current user's authentication token (used to identify the provider).
     * @return The name of the dashboard view template.
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, OAuth2AuthenticationToken authentication) {
        // Optimization Note:
        // In a real multi-tenant scenario, we would fetch the party based on the logged-in user's ID.
        // For MVP, we fetch the first available party or create one if none exist.
        Page<PartySettingsEntity> parties = partySettingsService.findAll(PageRequest.of(0, 1));
        PartySettingsEntity settings = parties.isEmpty()
                ? partySettingsService.createNewParty()
                : parties.getContent().getFirst();

        String partyCode = settings.getPartyCode();
        model.addAttribute("partyCode", partyCode);

        if (authentication != null) {
            String activeProvider = authentication.getAuthorizedClientRegistrationId();
            log.info("DJ logged in using: {}", activeProvider);
            model.addAttribute(ACTIVE_PROVIDER, activeProvider);
        }

        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(PLAYBACK_MODE, settings.getPlaybackMode());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);

        // Generate QR Code dynamically for this party
        // Optimization: QrCodeService could be cached if performance becomes an issue.
        String guestUrl = cleanBaseUrl + "/p/" + partyCode;
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);

        model.addAttribute(HISTORY, djService.getHistoryForParty(partyCode));

        return "dashboard";
    }

    /**
     * API endpoint returning all song requests in JSON format for a specific party.
     *
     * @param partyCode The unique code of the party to fetch history for.
     * @return list of all SongRequestEntity objects, sorted from newest to oldest.
     */
    @GetMapping("/history")
    @ResponseBody
    public List<SongRequestEntity> getHistory(@RequestParam String partyCode) {
        return djService.getHistoryForParty(partyCode);
    }

    /**
     * HTMX endpoint that returns a partial HTML fragment of the song request table.
     * Used for dynamic dashboard updates without a full page reload.
     *
     * @param partyCode The unique code of the party context.
     * @param model     Spring MVC model.
     * @return the "songTableBody" fragment from the dashboard template.
     */
    @GetMapping("/dashboard/updates")
    public String getDashboardUpdates(@RequestParam String partyCode, Model model) {
        model.addAttribute(HISTORY, djService.getHistoryForParty(partyCode));
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
        return "redirect:/dashboard";
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
        return "redirect:/dashboard";
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
        return "redirect:/dashboard";
    }

    /**
     * Toggles the playback mode between AUTO and MANUAL for the specific party.
     *
     * @param partyCode The unique code of the party.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/playback-mode")
    public String togglePlaybackMode(@RequestParam String partyCode) {
        PlaybackMode currentMode = djService.getCurrentPlaybackMode(partyCode);
        djService.setPlaybackMode(partyCode, currentMode == PlaybackMode.AUTO ? PlaybackMode.MANUAL : PlaybackMode.AUTO);
        return "redirect:/dashboard";
    }
}
