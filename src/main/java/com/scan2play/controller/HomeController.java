package com.scan2play.controller;

import com.scan2play.service.PlanService;
import com.scan2play.service.SiteCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Locale;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final PlanService planService;
    private final SiteCounter siteCounter;

    /**
     * The landing page's button: Google's login (then the DJ's party — {@link DjSessionHelper#getPartySettings}). The old links of
     * the landing page's tiles ({@code /start/requests} and the like) lead there too.
     */
    @GetMapping({"/start", "/start/{kind}"})
    public String start(@RequestHeader(value = "User-Agent", required = false) String userAgent) {
        siteCounter.countVisit(SiteCounter.Event.START, userAgent);
        return "redirect:/oauth2/authorization/google";
    }

    /**
     * The landing page. It makes no session (the review of 2026-10-21, S2: a row in {@code spring_session} for every visitor,
     * crawlers too): its form for the staff's invitation is a GET ({@code GET /join}), so it carries no CSRF token. A form that
     * posts would need its token loaded here, before the page is rendered — after the first 8 KB no session can be made
     * (production, 2026-10-18: a first visit got the page cut off). Pro is named only while the plans are enforced
     * ({@code plansEnforced}; the owner, 2026-10-21: before, its page is a 404). A logged-in DJ gets it too (the owner, 2026-10-24:
     * the panel's logo leads here, and sending them back to the panel made the logo seem dead) — its start buttons lead to the panel
     * ({@code loggedIn}), and their visit is not counted as a visitor's.
     */
    @GetMapping("/")
    public String home(Authentication authentication, Model model, Locale locale,
                       @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        boolean loggedIn = authentication != null && authentication.isAuthenticated();
        if (!loggedIn) {
            // a visitor (a robot is not counted): how many come from a post, in which language (V40)
            siteCounter.countVisit("pl".equals(locale.getLanguage()) ? SiteCounter.Event.LANDING_PL : SiteCounter.Event.LANDING_EN,
                    userAgent);
        }
        model.addAttribute("loggedIn", loggedIn);
        model.addAttribute("plansEnforced", planService.isEnforced());
        return "landing";
    }
}