package com.scan2play.controller;

import com.scan2play.service.SpotifyAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

/**
 * Handles the secondary Spotify OAuth2 flow for playback control.
 * <p>
 * This is separate from the DJ login OAuth2 — it grants permission to control
 * the DJ's Spotify player (add to queue, read playback state).
 * <p>
 * <b>Security:</b> Both endpoints require authentication and validate that the
 * {@code partyCode} belongs to the logged-in DJ (IDOR protection).
 * The callback uses a CSRF-safe state parameter (partyCode) verified against the session.
 */
@Controller
@RequestMapping("/dj/spotify")
@RequiredArgsConstructor
@Slf4j
public class SpotifyAuthController {

    private final SpotifyAuthService spotifyAuthService;
    private final DjSessionHelper sessionHelper;

    /**
     * Initiates the Spotify authorization redirect for playback control.
     * Validates that the partyCode belongs to the authenticated DJ before redirecting.
     */
    @GetMapping("/login")
    public String spotifyLogin(@RequestParam("partyCode") String partyCode,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String authorizationUrl = spotifyAuthService.getAuthorizationUrl(partyCode);
        return "redirect:" + authorizationUrl;
    }

    /**
     * Handles the Spotify OAuth2 callback after user grants permissions.
     * Validates that the returned state (partyCode) matches the DJ's session.
     */
    @GetMapping("/callback")
    public String spotifyCallback(@RequestParam("code") String code,
                                  @RequestParam("state") String partyCode,
                                  OAuth2AuthenticationToken authentication, HttpSession session) throws IOException {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        spotifyAuthService.exchangeCodeForToken(code, partyCode);
        return REDIRECT_DASHBOARD;
    }
}
