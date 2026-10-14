package com.scan2play.controller;

import com.scan2play.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the legal pages (privacy policy, terms), required for Google OAuth verification, and the guide "Jak działa Scan2Play"
 * (2026-10-12). These pages must be publicly accessible (no authentication required).
 * <p>
 * Templates are per-language (e.g. privacy.html / privacy_pl.html) — no i18n keys needed
 * for static legal content that rarely changes.
 */
@Controller
@RequiredArgsConstructor
public class LegalController {

    private final PlanService planService;

    @GetMapping("/privacy")
    public String privacyPolicy() {
        return resolveTemplate("privacy");
    }

    @GetMapping("/terms")
    public String termsOfService() {
        return resolveTemplate("terms");
    }

    /**
     * The guide for DJs (the menu "Konto" → "Instrukcja", the landing page, an empty queue): public, so a DJ reads it before the first
     * login and a person of the staff before joining. Logged in, its heading leads back to the panel; else to the landing page.
     */
    @GetMapping({"/jak-to-dziala", "/how-it-works"})
    public String guide(Authentication authentication, Model model) {
        model.addAttribute("loggedIn", authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken));
        return resolveTemplate("guide");
    }

    /**
     * "Scan2Play Pro" (V35): what the Pro plan adds and what stays everyone's — where "Zobacz, co daje Pro" leads. Only while the
     * plans are enforced: before that nothing says "Pro", and the page is a 404.
     */
    @GetMapping("/pro")
    public String pro(Authentication authentication, Model model) {
        if (!planService.isEnforced()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("loggedIn", authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken));
        return resolveTemplate("pro");
    }

    /**
     * Returns a locale-specific template name if it exists (e.g. "privacy_pl"),
     * otherwise falls back to the default English template (e.g. "privacy").
     */
    private String resolveTemplate(String baseName) {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        if ("pl".equals(lang)) {
            return baseName + "_pl";
        }
        return baseName;
    }
}
