package com.scan2play.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
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

    /**
     * The landing page. Its form for the staff's invitation (POST /join) carries a CSRF token, kept in the session: the token is
     * made here, before the page is rendered — the form is at the page's end, and by the time it was rendered the server had sent
     * the first 8 KB, after which no session can be made (production, 2026-10-18: a first visit got the page cut off).
     */
    @GetMapping("/")
    public String home(Authentication authentication, CsrfToken csrfToken) {
        if (authentication != null && authentication.isAuthenticated()) {
            log.info("User already authenticated, redirecting to dashboard");
            return REDIRECT_DASHBOARD;
        }
        csrfToken.getToken();   // the deferred token loaded — and its session made — while the response can still take a cookie
        return "landing";
    }
}