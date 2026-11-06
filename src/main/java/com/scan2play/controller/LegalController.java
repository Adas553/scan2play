package com.scan2play.controller;

import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

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

    /** The footer's "Cennik" (components :: footer) on these pages: only while the plans are enforced, as the page itself. */
    @ModelAttribute("plansEnforced")
    public boolean plansEnforced() {
        return planService.isEnforced();
    }

    /**
     * The pricing page ("Cennik", V35): Free and Pro, what Pro adds — where "Zobacz, co daje Pro" leads. Only while the plans are
     * enforced: before that nothing says "Pro", and the page is a 404.
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
     * The pricing page's other addresses: one page, one canonical address ({@code /pro}, already linked from the panel), so a search
     * engine sees no copy — a 301, so it keeps the page's one address. A 404 while the plans are not enforced, as the page itself.
     */
    @GetMapping({"/cennik", "/pricing"})
    public RedirectView pricing(HttpServletRequest request) {
        if (!planService.isEnforced()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        RedirectView toThePage = new RedirectView("/pricing".equals(request.getRequestURI()) ? "/pro?lang=en" : "/pro?lang=pl", true);
        toThePage.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        toThePage.setExposeModelAttributes(false);   // plansEnforced is no part of the address
        return toThePage;
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
