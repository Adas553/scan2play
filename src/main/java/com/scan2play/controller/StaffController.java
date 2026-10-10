package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.util.CodeGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
 *     <li>{@code /dj/invitation} — an invitation by e-mail (V34) waiting for the person who logged in: the panel sends them here
 *     first; "Dołącz" / "Nie, dziękuję" are POSTs, as with the link.</li>
 *     <li>{@code /dj/staff} — the owner's page "Obsługa": who has access, what each may do (a role or ticked one by one), the
 *     invitations by e-mail, the invitation link and its role (V33).</li>
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
    private final StaffInvitationService staffInvitationService;

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
        addRole(model, party.get().staffLinkGrants());
        return "join";
    }

    /** The role the invitation gives and its permissions (the link's, V33, or the e-mail invitation's, V34). */
    private static void addRole(Model model, Set<StaffPermission> permissions) {
        model.addAttribute(JOIN_ROLE, StaffRole.of(permissions));
        model.addAttribute(JOIN_PERMISSIONS, permissions.stream().sorted().toList());
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

    // ---------------------------------------------------------------- the invitation by e-mail (V34)

    /**
     * The address Google verified for the logged-in account, or null: an invitation by e-mail is matched only against it (the scope
     * {@code email}; an unverified address could be anyone's). Google's OpenID user says {@code email_verified} as a boolean, a plain
     * OAuth 2 user may say it as a text.
     */
    public static String verifiedAddress(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth) || !(oauth.getPrincipal() instanceof OAuth2User user)) {
            return null;
        }
        Object address = user.getAttribute("email");
        Object verified = user.getAttribute("email_verified");
        boolean isVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return isVerified && address instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * The invitation by e-mail waiting for the person (the panel sends them here before anything else): the party, who invites, the
     * role — "Dołącz" or "Nie, dziękuję". None waiting: the panel.
     */
    @GetMapping("/dj/invitation")
    public String emailInvitation(Model model, OAuth2AuthenticationToken authentication) {
        Optional<StaffInvitationService.Waiting> waiting = staffInvitationService.waitingFor(verifiedAddress(authentication));
        if (waiting.isEmpty()) {
            return REDIRECT_DASHBOARD;
        }
        PartySettingsEntity party = waiting.get().party();
        model.addAttribute(JOIN_PARTY_NAME, PartyStaffService.nameOf(party));
        model.addAttribute(JOIN_OWNER_NAME, party.getOwnerName());
        model.addAttribute(JOIN_LOGGED_IN, true);
        model.addAttribute(JOIN_INVITATION_ID, waiting.get().invitation().getId());
        addRole(model, waiting.get().invitation().getPermissions());
        return "join";
    }

    /** "Dołącz" on an invitation by e-mail: only the person whose verified address it names; the panel of that party opens. */
    @PostMapping("/dj/invitation/accept")
    public String acceptEmailInvitation(@RequestParam long id, Model model, OAuth2AuthenticationToken authentication,
                                        HttpSession session, HttpServletResponse response) {
        Object name = authentication.getPrincipal().getAttribute("name");
        StaffInvitationService.Answer answer = staffInvitationService.accept(id, verifiedAddress(authentication),
                authentication.getName(), name == null ? null : name.toString());
        return switch (answer.outcome()) {
            case JOINED, ALREADY -> {
                String code = answer.party().getPartyCode();
                sessionHelper.switchTo(code, authentication, session);
                sessionHelper.note(session, new DjSessionHelper.Note("dashboard.staff.joined", PartyStaffService.nameOf(answer.party()), false));
                yield REDIRECT_DASHBOARD + "?party=" + code;
            }
            case OWNER -> {
                sessionHelper.note(session, new DjSessionHelper.Note("dashboard.invitation.own_party", null, false));
                yield REDIRECT_DASHBOARD + "?party=" + answer.party().getPartyCode();
            }
            case FULL -> {
                model.addAttribute(JOIN_PARTY_NAME, PartyStaffService.nameOf(answer.party()));
                yield joinProblem(model, response, "join.problem.full", HttpStatus.CONFLICT);
            }
            case GONE -> {
                sessionHelper.note(session, new DjSessionHelper.Note("dashboard.invitation.gone", null, true));
                yield REDIRECT_DASHBOARD;
            }
        };
    }

    /**
     * "Nie, dziękuję": the invitation goes; the panel opens — the next invitation, the person's own panel, or "no-panel" (the note
     * keeps a DJ's party from being made for someone who came for an invitation only).
     */
    @PostMapping("/dj/invitation/decline")
    public String declineEmailInvitation(@RequestParam long id, OAuth2AuthenticationToken authentication, HttpSession session) {
        if (staffInvitationService.decline(id, verifiedAddress(authentication))) {
            sessionHelper.note(session, new DjSessionHelper.Note("dashboard.invitation.declined", null, false));
        }
        return REDIRECT_DASHBOARD;
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
        Set<StaffPermission> linkGrants = settings.getStaffToken() == null ? StaffRole.DEFAULT.permissions() : settings.staffLinkGrants();
        model.addAttribute(STAFF_LINK_ROLE, StaffRole.of(linkGrants));
        model.addAttribute(STAFF_LINK_PERMISSIONS, linkGrants);
        model.addAttribute(STAFF_INVITATIONS, staffInvitationService.waitingAt(partyCode));
        model.addAttribute(STAFF_PLACES_LEFT, Math.max(0, PartyStaffService.MAX_STAFF - partyStaffService.placesTaken(partyCode)));
        model.addAttribute(STAFF_ROLES, StaffRole.values());
        model.addAttribute(STAFF_DEFAULT_ROLE, StaffRole.DEFAULT);
        model.addAttribute(STAFF_PERMISSIONS, StaffPermission.values());
        model.addAttribute(STAFF_SAVED, session.getAttribute(SESSION_STAFF_SAVED));
        session.removeAttribute(SESSION_STAFF_SAVED);
        Object invited = session.getAttribute(SESSION_STAFF_INVITED);
        session.removeAttribute(SESSION_STAFF_INVITED);
        if (invited instanceof InviteResult result) {
            model.addAttribute(STAFF_INVITE_RESULT, result);
        }
        return "staff";
    }

    /** "Zapisano" for the next showing of the staff page (after the redirect). */
    static final String SESSION_STAFF_SAVED = "staffSaved";

    /** What came of "Zaproś", for the next showing of the staff page: the outcome and the address typed (kept in the field when wrong). */
    static final String SESSION_STAFF_INVITED = "staffInvited";

    /** @param outcome {@link StaffInvitationService.InviteOutcome}'s name — the message key's last part */
    public record InviteResult(String outcome, String address, boolean problem) implements java.io.Serializable {
    }

    /**
     * "Zaproś": the address of the person's Google account and what they will be able to do (a role's set, or with
     * {@code role=CUSTOM} the ticked permissions). The owner's alone; the page says what came of it.
     */
    @PostMapping("/dj/staff/invite")
    public String invite(@RequestParam String partyCode, @RequestParam(required = false) String email,
                         @RequestParam(required = false) StaffRole role, @RequestParam(required = false) List<StaffPermission> permissions,
                         OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        StaffInvitationService.InviteOutcome outcome = staffInvitationService.invite(partyCode, email, granted(role, permissions));
        boolean problem = outcome == StaffInvitationService.InviteOutcome.INVALID_ADDRESS || outcome == StaffInvitationService.InviteOutcome.FULL;
        String typed = email == null ? "" : email.strip();
        session.setAttribute(SESSION_STAFF_INVITED, new InviteResult(outcome.name(),
                typed.length() > 254 ? typed.substring(0, 254) : typed, problem));
        return backToStaffPage(partyCode);
    }

    /** "Cofnij zaproszenie": only an invitation of the owner's own party. */
    @PostMapping("/dj/staff/invitation/cancel")
    public String cancelInvitation(@RequestParam String partyCode, @RequestParam long id,
                                   OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        staffInvitationService.cancel(partyCode, id);
        return backToStaffPage(partyCode);
    }

    /** A role's set, or with {@link StaffRole#CUSTOM} the ticked permissions; no role: {@link StaffRole#DEFAULT}. */
    private static Set<StaffPermission> granted(StaffRole role, List<StaffPermission> permissions) {
        if (role == null) {
            return StaffRole.DEFAULT.permissions();
        }
        return role != StaffRole.CUSTOM ? role.permissions()
                : permissions == null || permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions);
    }

    /**
     * What one person may do: a role's set, or with {@code role=CUSTOM} the ticked {@code permissions}. Only a person of the owner's
     * own party; counts from the person's next request.
     */
    @PostMapping("/dj/staff/permissions")
    public String setPermissions(@RequestParam String partyCode, @RequestParam long id, @RequestParam StaffRole role,
                                 @RequestParam(required = false) List<StaffPermission> permissions,
                                 OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        if (partyStaffService.setPermissions(partyCode, id, granted(role, permissions))) {
            session.setAttribute(SESSION_STAFF_SAVED, id);
        }
        return backToStaffPage(partyCode);
    }

    /**
     * The invitation link: {@code link=new} makes one with the role picked beside it (V33: a role's set or the ticked permissions —
     * whoever joins by it gets them; the old link dead, who joined stays), {@code link=off} takes it away. A link's role is changed
     * only by a new link (the owner, 2026-10-10: a link sent as "Podgląd" must never start to give more).
     */
    @PostMapping("/dj/staff/link")
    public String updateStaffLink(@RequestParam String partyCode, @RequestParam String link,
                                  @RequestParam(required = false) StaffRole role, @RequestParam(required = false) List<StaffPermission> permissions,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        boolean make = switch (link) {
            case "new" -> true;
            case "off" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        String token = make ? CodeGenerator.generateSecret() : null;
        Set<StaffPermission> grants = make ? granted(role, permissions) : null;
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setStaffToken(token);
            s.setStaffLinkPermissions(grants);
        });
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
