package com.scan2play.controller;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the legal pages (privacy policy, terms), required for Google OAuth verification.
 * These pages must be publicly accessible (no authentication required).
 * <p>
 * Templates are per-language (e.g. privacy.html / privacy_pl.html) — no i18n keys needed
 * for static legal content that rarely changes.
 */
@Controller
public class LegalController {

    @GetMapping("/privacy")
    public String privacyPolicy() {
        return resolveTemplate("privacy");
    }

    @GetMapping("/terms")
    public String termsOfService() {
        return resolveTemplate("terms");
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
