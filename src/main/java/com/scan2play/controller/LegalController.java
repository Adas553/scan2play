package com.scan2play.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves legal pages required for YouTube API compliance and Google OAuth verification.
 * These pages must be publicly accessible (no authentication required).
 */
@Controller
public class LegalController {

    @GetMapping("/privacy")
    public String privacyPolicy() {
        return "privacy";
    }

    @GetMapping("/terms")
    public String termsOfService() {
        return "terms";
    }
}

