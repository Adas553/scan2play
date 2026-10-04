package com.scan2play.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

@Controller
@Slf4j
public class HomeController {

    /**
     * The landing page's button: Google's login (then the DJ's party — {@link DjSessionHelper#getPartySettings}). The old links of
     * the landing page's tiles ({@code /start/requests} and the like) lead there too.
     */
    @GetMapping({"/start", "/start/{kind}"})
    public String start() {
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