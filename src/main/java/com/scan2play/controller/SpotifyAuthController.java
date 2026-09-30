package com.scan2play.controller;

import com.scan2play.service.SpotifyAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

/**
 * Handles the secondary Spotify OAuth2 flow for playback control.
 * <p>
 * This is separate from the DJ login OAuth2 — it grants permission to control
 * the DJ's Spotify player (add to queue, read playback state).
 * <p>
 * <b>Security:</b> Both endpoints require authentication and validate that the
 * {@code partyCode} belongs to the logged-in DJ (IDOR protection).
 * The callback checks the OAuth {@code state}: a random value made for this login and kept in the DJ's session, used once.
 * (It used to be the party code, which is public — it is in the QR code — so a DJ could be sent a callback link carrying someone
 * else's authorization code, and their party would then have queued songs to that other person's Spotify.)
 */
@Controller
@RequestMapping("/dj/spotify")
@RequiredArgsConstructor
@Slf4j
public class SpotifyAuthController {

    private final SpotifyAuthService spotifyAuthService;
    private final DjSessionHelper sessionHelper;

    /** The session attribute that holds the {@code state} of the Spotify login in progress. */
    static final String SESSION_OAUTH_STATE = "spotifyOAuthState";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Initiates the Spotify authorization redirect for playback control.
     * Validates that the partyCode belongs to the authenticated DJ before redirecting.
     */
    @GetMapping("/login")
    public String spotifyLogin(@RequestParam("partyCode") String partyCode,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        session.setAttribute(SESSION_OAUTH_STATE, state);
        String authorizationUrl = spotifyAuthService.getAuthorizationUrl(state);
        return "redirect:" + authorizationUrl;
    }

    /**
     * Handles the Spotify OAuth2 callback after user grants permissions.
     * The returned {@code state} must be the one this session's login started with (it is used once); the tokens go to the
     * party of the logged-in DJ.
     */
    @GetMapping("/callback")
    public String spotifyCallback(@RequestParam("code") String code,
                                  @RequestParam("state") String state,
                                  OAuth2AuthenticationToken authentication, HttpSession session) throws IOException {
        Object expected = session.getAttribute(SESSION_OAUTH_STATE);
        session.removeAttribute(SESSION_OAUTH_STATE);
        if (expected == null || !expected.equals(state)) {
            log.warn("Spotify callback with an unknown OAuth state rejected (DJ {})", authentication != null ? authentication.getName() : null);
            throw new AccessDeniedException("Unknown OAuth state");
        }
        String partyCode = sessionHelper.getPartySettings(authentication, session).getPartyCode();
        spotifyAuthService.exchangeCodeForToken(code, partyCode);
        return REDIRECT_DASHBOARD;
    }
}
