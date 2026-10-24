package com.scan2play.controller;

import com.scan2play.service.PlanService;
import com.scan2play.service.SiteCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

@Controller
@Slf4j
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
     * The landing page. Its form for the staff's invitation (POST /join) carries a CSRF token, kept in the session: the token is
     * made here, before the page is rendered — the form is at the page's end, and by the time it was rendered the server had sent
     * the first 8 KB, after which no session can be made (production, 2026-10-18: a first visit got the page cut off). Pro is named
     * only while the plans are enforced ({@code plansEnforced}; the owner, 2026-10-21: before, its page is a 404).
     */
    @GetMapping("/")
    public String home(Authentication authentication, CsrfToken csrfToken, Model model, Locale locale,
                       @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        if (authentication != null && authentication.isAuthenticated()) {
            log.info("User already authenticated, redirecting to dashboard");
            return REDIRECT_DASHBOARD;
        }
        // a visitor (a robot is not counted): how many come from a post, in which language (V40)
        siteCounter.countVisit("pl".equals(locale.getLanguage()) ? SiteCounter.Event.LANDING_PL : SiteCounter.Event.LANDING_EN,
                userAgent);
        csrfToken.getToken();   // the deferred token loaded — and its session made — while the response can still take a cookie
        model.addAttribute("plansEnforced", planService.isEnforced());
        return "landing";
    }
}