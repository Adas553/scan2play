package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.util.CodeGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The party's staff (V30, V32).
 * <ul>
 *     <li>{@code GET /join/{token}} — public: the owner's invitation. Logged in, it asks "Dołączyć?" and {@code POST /join/{token}}
 *     joins; not logged in, it keeps the token in the session and its button leads through Google's login back to this question
 *     (the panel sends a person with a waiting invitation here). Joining is never a side effect of opening a page (the review,
 *     2026-10-10: two navigations of a foreign site could join a logged-in DJ to its party and show it the DJ's Google name).</li>
 *     <li>{@code /dj/staff} — the owner's page "Obsługa": who has access, what each may do (a role or ticked one by one), the
 *     invitation link.</li>
 *     <li>{@code POST /dj/panel}, {@code /dj/staff/leave}, {@code /dj/join} — the panel switcher, leaving a staff, a link pasted in
 *     the app.</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class StaffController {

    /** The session attribute of an invitation opened before the login. */
    static final String SESSION_PENDING_INVITATION = "pendingStaffInvitation";

    private final PartyStaffService partyStaffService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final DjSessionHelper sessionHelper;

    // ---------------------------------------------------------------- the invitation

    @GetMapping("/join/{token}")
    public String invitation(@PathVariable String token, Model model, HttpSession session, Authentication authentication,
                             HttpServletResponse response) {
        Optional<PartySettingsEntity> party = partyStaffService.partyOfLink(token);
        if (party.isEmpty()) {
            return joinProblem(model, response, "join.problem.old_link", HttpStatus.NOT_FOUND);
        }
        boolean loggedIn = authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated();
        if (loggedIn) {
            session.removeAttribute(SESSION_PENDING_INVITATION);   // asked now: the token is in the address
        } else {
            session.setAttribute(SESSION_PENDING_INVITATION, token);
        }
        model.addAttribute(JOIN_PARTY_NAME, PartyStaffService.nameOf(party.get()));
        model.addAttribute(JOIN_OWNER_NAME, party.get().getOwnerName());
        model.addAttribute(JOIN_TOKEN, token);
        model.addAttribute(JOIN_LOGGED_IN, loggedIn);
        model.addAttribute(JOIN_ROLE, StaffRole.DEFAULT);
        return "join";
    }

    /** "Dołącz" on the invitation (logged in; else the login first, the token waiting in the session). */
    @PostMapping("/join/{token}")
    public String join(@PathVariable String token, Model model, HttpSession session, Authentication authentication,
                       HttpServletResponse response) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth) || !authentication.isAuthenticated()) {
            session.setAttribute(SESSION_PENDING_INVITATION, token);
            return "redirect:/start";
        }
        Object name = oauth.getPrincipal().getAttribute("name");
        PartyStaffService.Joined joined = partyStaffService.join(token, oauth.getName(), name == null ? null : name.toString());
        return switch (joined.outcome()) {
            case JOINED, ALREADY -> {
                String code = joined.party().getPartyCode();
                sessionHelper.switchTo(code, oauth, session);
                sessionHelper.note(session, new DjSessionHelper.Note("dashboard.staff.joined",
                        PartyStaffService.nameOf(joined.party()), false));
                yield REDIRECT_DASHBOARD + "?party=" + code;
            }
            case OWNER -> {
                sessionHelper.note(session, new DjSessionHelper.Note("dashboard.staff.own_link", null, false));
                yield REDIRECT_DASHBOARD + "?party=" + joined.party().getPartyCode();
            }
            case FULL -> {
                model.addAttribute(JOIN_PARTY_NAME, PartyStaffService.nameOf(joined.party()));
                yield joinProblem(model, response, "join.problem.full", HttpStatus.CONFLICT);
            }
            case UNKNOWN_LINK -> joinProblem(model, response, "join.problem.old_link", HttpStatus.NOT_FOUND);
        };
    }

    private static String joinProblem(Model model, HttpServletResponse response, String key, HttpStatus status) {
        response.setStatus(status.value());
        model.addAttribute(JOIN_PROBLEM, key);
        return "join";
    }

    /** The token at the end of a pasted invitation link ("https://…/join/AbC_1", "…/join/AbC_1/", or the token alone). */
    private static final Pattern PASTED_LINK = Pattern.compile("(?:.*/join/)?([A-Za-z0-9_-]{1,32})/?");

    /** A guests' link pasted where an invitation was asked for ("…/p/AB12C"): told apart, it never was an invitation. */
    private static final Pattern GUESTS_LINK = Pattern.compile(".*/p/[A-Za-z0-9]{5}/?(?:[?#].*)?");

    /**
     * An invitation link pasted in the panel (the app on an iPhone's Home Screen has a login of its own, so a link from an e-mail
     * opens elsewhere): a party's link leads to its invitation, which asks "Dołączyć?"; anything else back to the panel with a note.
     */
    @PostMapping("/dj/join")
    public String joinByPastedLink(@RequestParam String link, HttpSession session) {
        Optional<String> token = tokenOf(link).filter(t -> partyStaffService.partyOfLink(t).isPresent());
        if (token.isPresent()) {
            return "redirect:/join/" + token.get();
        }
        String key = GUESTS_LINK.matcher(link.strip()).matches() ? "dashboard.join.guests_link" : "dashboard.staff.old_link";
        sessionHelper.note(session, new DjSessionHelper.Note(key, null, true));
        return REDIRECT_DASHBOARD;
    }

    /**
     * The invitation link pasted on the landing page (public): a party's link goes to Google's login, the token waiting in the session —
     * the panel then asks "Dołączyć?"; anything else is back on the landing page with a note, before any login.
     */
    @PostMapping("/join")
    public String joinFromTheLandingPage(@RequestParam String link, HttpSession session) {
        Optional<String> token = tokenOf(link).filter(t -> partyStaffService.partyOfLink(t).isPresent());
        if (token.isEmpty()) {
            return GUESTS_LINK.matcher(link == null ? "" : link.strip()).matches()
                    ? "redirect:/?staffLink=guests" : "redirect:/?staffLink=invalid";
        }
        session.setAttribute(SESSION_PENDING_INVITATION, token.get());
        return "redirect:/start";
    }

    /** The token of a pasted link: the end of ".../join/AbC_1" (a slash after it too), or the token alone. */
    static Optional<String> tokenOf(String link) {
        Matcher token = PASTED_LINK.matcher(link == null ? "" : link.strip());
        return token.matches() ? Optional.of(token.group(1)) : Optional.empty();
    }

    // ---------------------------------------------------------------- the person of the staff

    /**
     * "Opuść obsługę": the person leaves the staff of the party the page shows, and the panel opens another — their own, another
     * party they work at, or none (no DJ's party made for them). Nothing for the owner: the party is theirs.
     */
    @PostMapping("/dj/staff/leave")
    public String leaveStaff(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(partyCode, authentication, session);
        if (!access.owner()) {
            partyStaffService.leave(access.party().getPartyCode(), authentication.getName());
            sessionHelper.forget(access.party().getPartyCode(), session);
            sessionHelper.note(session, new DjSessionHelper.Note("dashboard.staff.left", PartyStaffService.nameOf(access.party()), false));
        }
        return REDIRECT_DASHBOARD;
    }

    /** Opens a panel: {@code party} = a party the person works at (or owns); none = their own, made now ("Załóż własną imprezę"). */
    @PostMapping("/dj/panel")
    public String switchPanel(@RequestParam(required = false) String party, OAuth2AuthenticationToken authentication,
                              HttpSession session) {
        PartySettingsEntity opened = party == null || party.isBlank()
                ? sessionHelper.switchToOwnParty(authentication, session)
                : sessionHelper.switchTo(party, authentication, session);
        return REDIRECT_DASHBOARD + "?party=" + opened.getPartyCode();
    }

    // ---------------------------------------------------------------- the owner's page "Obsługa"

    @GetMapping("/dj/staff")
    public String staffPage(@RequestParam(required = false) String party, Model model, OAuth2AuthenticationToken authentication,
                            HttpSession session, HttpServletRequest request) {
        PartySettingsEntity settings = sessionHelper.requireOwner(party, authentication, session);
        String partyCode = settings.getPartyCode();
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(STAFF, partyStaffService.staffOf(partyCode));
        // the address the owner opened the panel at, not the guests' one: the invitation leads through Google's login, which works
        // only where the panel does — locally the guests' address is the computer's in the local network, and Google refuses a
        // login there ("device_id and device_name are required for private IP", 2026-10-09)
        model.addAttribute(STAFF_LINK, settings.getStaffToken() == null ? null
                : ServletUriComponentsBuilder.fromContextPath(request).path("/join/{token}").buildAndExpand(settings.getStaffToken()).toUriString());
        model.addAttribute(STAFF_ROLES, StaffRole.values());
        model.addAttribute(STAFF_PERMISSIONS, StaffPermission.values());
        model.addAttribute(STAFF_SAVED, session.getAttribute(SESSION_STAFF_SAVED));
        session.removeAttribute(SESSION_STAFF_SAVED);
        return "staff";
    }

    /** "Zapisano" for the next showing of the staff page (after the redirect). */
    static final String SESSION_STAFF_SAVED = "staffSaved";

    /**
     * What one person may do: a role's set, or with {@code role=CUSTOM} the ticked {@code permissions}. Only a person of the owner's
     * own party; counts from the person's next request.
     */
    @PostMapping("/dj/staff/permissions")
    public String setPermissions(@RequestParam String partyCode, @RequestParam long id, @RequestParam StaffRole role,
                                 @RequestParam(required = false) List<StaffPermission> permissions,
                                 OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        Set<StaffPermission> granted = role != StaffRole.CUSTOM ? role.permissions()
                : permissions == null || permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions);
        if (partyStaffService.setPermissions(partyCode, id, granted)) {
            session.setAttribute(SESSION_STAFF_SAVED, id);
        }
        return backToStaffPage(partyCode);
    }

    /** The invitation link: {@code link=new} makes one (the old one dead; who joined stays), {@code link=off} takes it away. */
    @PostMapping("/dj/staff/link")
    public String updateStaffLink(@RequestParam String partyCode, @RequestParam String link,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        String token = switch (link) {
            case "new" -> CodeGenerator.generateSecret();
            case "off" -> null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        partySettingsCommandService.updateSettings(partyCode, s -> s.setStaffToken(token));
        return backToStaffPage(partyCode);
    }

    /** Takes one person's access away: their next request says so. Only a person of the owner's own party. */
    @PostMapping("/dj/staff/remove")
    public String removeStaff(@RequestParam String partyCode, @RequestParam long id,
                              OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        partyStaffService.remove(partyCode, id);
        return backToStaffPage(partyCode);
    }

    private static String backToStaffPage(String partyCode) {
        return "redirect:/dj/staff?party=" + partyCode;
    }
}
