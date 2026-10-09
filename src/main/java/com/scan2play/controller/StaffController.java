package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.PartyStaffService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The party's staff (V30): the invitation page and the panel switcher.
 * <ul>
 *     <li>{@code GET /join/{token}} — public: the owner's invitation link. It names the party and keeps the token in the session;
 *     its button leads through Google's login (or, logged in already, straight) to the panel, where the person joins
 *     ({@code DjDashboardController}): the login always lands on the panel, so the token waits for it there.</li>
 *     <li>{@code POST /dj/panel} — "Mój panel" or a party the person works at.</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class StaffController {

    /** The session attribute of an invitation opened before the login. */
    static final String SESSION_PENDING_INVITATION = "pendingStaffInvitation";

    private final PartyStaffService partyStaffService;
    private final DjSessionHelper sessionHelper;

    @GetMapping("/join/{token}")
    public String invitation(@PathVariable String token, Model model, HttpSession session, Authentication authentication) {
        PartySettingsEntity party = partyStaffService.partyOfLink(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        session.setAttribute(SESSION_PENDING_INVITATION, token);
        boolean loggedIn = authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated();
        model.addAttribute(JOIN_PARTY_NAME, PartyStaffService.nameOf(party));
        model.addAttribute(JOIN_URL, loggedIn ? "/dj/dashboard" : "/start");
        return "join";
    }

    /** Opens a panel: {@code party} = a party the person works at (or owns); none = the person's own ("Mój panel"). */
    @PostMapping("/dj/panel")
    public String switchPanel(@RequestParam(required = false) String party, OAuth2AuthenticationToken authentication,
                              HttpSession session) {
        if (party == null || party.isBlank()) {
            sessionHelper.switchToOwnParty(authentication, session);
        } else {
            sessionHelper.switchTo(party, authentication, session);
        }
        return REDIRECT_DASHBOARD;
    }
}
