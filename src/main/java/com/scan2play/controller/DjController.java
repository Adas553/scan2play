package com.scan2play.controller;

import com.scan2play.model.DjResponse;
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

@Controller
@RequiredArgsConstructor
@Slf4j
public class DjController {

    private final DjService djService;
    private final SongRequestRepository repository;


    // 1. Home page for the guest (Form)
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("globalVibe", djService.getCurrentGlobalVibe());
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
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, OAuth2AuthenticationToken authentication) {
        if (authentication != null) {
            String activeProvider = authentication.getAuthorizedClientRegistrationId();
            log.info("DJ logged in using: {}", activeProvider);
            model.addAttribute("activeProvider", activeProvider);
        }

        model.addAttribute("globalVibe", djService.getCurrentGlobalVibe());
        try {
            model.addAttribute("history", repository.findAll());
        } catch (Exception e) {
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
     * @return list of all SongRequestEntity objects
     */
    @GetMapping("/history")
    public List<SongRequestEntity> getHistory() {
        return repository.findAll();
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
        List<SongRequestEntity> history = repository.findAll();
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
}