package com.scan2play.controller;

import com.scan2play.model.MusicProviderType;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

@Controller
@Slf4j
public class HomeController {

    /**
     * A tile of the landing page that logs in with Google: the kind of party it stands for is kept in the session (the session
     * outlives the login), and {@link DjSessionHelper#getPartySettings} gives the DJ's party that kind — a new party, or the DJ's
     * own one switched between YouTube and requests-only. Then Google's login.
     *
     * @param kind {@code youtube} or {@code requests}; anything else goes back to the landing page
     */
    @GetMapping("/start/{kind}")
    public String start(@PathVariable String kind, HttpSession session) {
        MusicProviderType chosen = switch (kind) {
            case "youtube" -> MusicProviderType.YOUTUBE;
            case "requests" -> MusicProviderType.REQUESTS_ONLY;
            default -> null;
        };
        if (chosen == null) {
            return "redirect:/";
        }
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, chosen.name());
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            log.info("User already authenticated, redirecting to dashboard");
            return REDIRECT_DASHBOARD;
        }
        return "landing";
    }
}