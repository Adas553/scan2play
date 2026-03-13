package com.scan2play.controller;

import com.scan2play.model.DjResponse;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.VibeType;
import com.scan2play.service.DjService;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;

import java.util.List;

/**
 * Main web controller for handling guest requests and DJ dashboard interactions.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class DjController {

    private final DjService djService;
    private final SongRequestRepository repository;


    /**
     * Displays the main guest-facing page with the song request form and public queue.
     *
     * @param model Spring model to pass attributes to the view.
     * @return The name of the index view template.
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("globalVibe", djService.getCurrentGlobalVibe());
        model.addAttribute("publicQueue", djService.getPublicQueue());
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
        model.addAttribute("response", response);
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
            model.addAttribute("activeProvider", activeProvider);
        }

        model.addAttribute("globalVibe", djService.getCurrentGlobalVibe());
        model.addAttribute("activeProvider", djService.getActiveProvider());

        try {
            // Using sorting by RequestedAt Descending (newest first)
            model.addAttribute("history", repository.findAllByOrderByRequestedAtDesc());
        } catch (RuntimeException e) {
            log.error("Error while fetching song history", e);
            model.addAttribute("history", List.of());
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
        model.addAttribute("history", history);
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
}