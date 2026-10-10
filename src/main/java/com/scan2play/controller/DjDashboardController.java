package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.HistoryFilter;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.util.SongList;
import com.scan2play.util.Texts;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.Optional;

import static com.scan2play.controller.ViewAttributes.*;


/**
 * Controller responsible for DJ dashboard views and AJAX polling endpoints.
 * <p>
 * Handles:
 * <ul>
 *     <li>Main dashboard view</li>
 *     <li>Dashboard AJAX updates (ETag-based polling)</li>
 *     <li>History view and history fragment</li>
 * </ul>
 * Party settings changes are handled by {@link DjPartySettingsController}.
 * Song queue actions are handled by {@link DjSongController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjDashboardController {

    private final DjService djService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final QrCodeService qrCodeService;
    private final DjSessionHelper sessionHelper;
    private final PlayHistoryService playHistoryService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final PushNotificationService pushNotificationService;
    private final PartyStaffService partyStaffService;
    private final StaffInvitationService staffInvitationService;

    /**
     * On every answer of the queue poll, 304 too: the limits that stop guest songs now, comma-separated —
     * {@value #FLAG_PARTY_FULL} (the party's 24-hour limit) — or {@value #FLAG_NONE}. The dashboard shows or hides its warnings
     * by it.
     */
    static final String GUEST_LIMITS_HEADER = "X-Guest-Limits";
    static final String FLAG_PARTY_FULL = "party-full";
    static final String FLAG_NONE = "none";

    /**
     * On every answer of the queue poll, 304 too: how many requests the server limits have used now,
     * {@code <busiest network>,<party>} — the dashboard keeps its badges current by it, without a reload.
     */
    static final String GUEST_LIMITS_USE_HEADER = "X-Guest-Limits-Use";

    /**
     * On every answer of the queue poll, 304 too: {@code true} while the party is open, {@code false} once the DJ ended it — in any
     * window, so a second window (the DJ's phone) shows the "party closed" banner without a reload.
     */
    static final String PARTY_ACTIVE_HEADER = "X-Party-Active";

    /** The history shows this many requests at first, and this many more each time the DJ asks for more. */
    static final int HISTORY_PAGE_SIZE = 50;

    /** The furthest back the history goes: the query stays bounded, and the list stays a list a person can use. */
    static final int HISTORY_MAX_LIMIT = 300;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    private String cleanBaseUrl;

    /**
     * Sanitizes the guest URL once at startup to avoid repeated string operations during requests.
     */
    @PostConstruct
    public void init() {
        this.cleanBaseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
    }

    /**
     * On every answer of the queue poll, 304 too: what the person may do at the party ({@code owner}, or the permissions' names) — a
     * window whose page was made with other permissions loads itself again (the organiser changed the person's role, V32).
     */
    static final String PANEL_ACCESS_HEADER = "X-Panel-Access";

    /**
     * The DJ panel: the party {@code party} names, else the default panel ({@link DjSessionHelper#panel}); none at all — a person who
     * came by an invitation that did not work, or whose access was taken away — is the page "no-panel". A person back from Google's
     * login with an invitation waiting is asked "Dołączyć?" first ({@code /join/{token}}), and so is one whose Google address an
     * organiser invited (V34, {@code /dj/invitation}) — before any panel, so no DJ's party is made for someone who came to help.
     */
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String party, Model model, OAuth2AuthenticationToken authentication,
                            HttpSession session) {
        Object pending = session.getAttribute(StaffController.SESSION_PENDING_INVITATION);
        if (pending != null) {
            session.removeAttribute(StaffController.SESSION_PENDING_INVITATION);
            return "redirect:/join/" + pending;
        }
        if (staffInvitationService.waitingFor(StaffController.verifiedAddress(authentication)).isPresent()) {
            return "redirect:/dj/invitation";
        }
        Optional<PartyStaffService.Access> panel = sessionHelper.panel(party, authentication, session);
        model.addAttribute(PANEL_NOTE, sessionHelper.takeNote(session).orElse(null));
        if (panel.isEmpty()) {
            return "no-panel";
        }
        PartyStaffService.Access access = panel.get();
        PartySettingsEntity settings = access.party();
        String partyCode = settings.getPartyCode();
        boolean owner = access.owner();

        log.info("DJ Dashboard access: ownerId={}, partyCode={}, owner={}", authentication.getName(), partyCode, owner);
        if (owner) {
            keepOwnerName(settings, authentication);
        }

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());

        // --- Who works on it (V30, V32): the owner everything, the staff what the owner gave them; the panels to switch between ---
        model.addAttribute(IS_OWNER, owner);
        model.addAttribute(ACCESS, access);
        model.addAttribute(PANEL_ACCESS, accessValue(access));
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(PANELS, partyStaffService.panelsOf(authentication.getName()));
        model.addAttribute(HAS_OWN_PARTY, owner || partyStaffService.hasOwnParty(authentication.getName()));
        if (owner) {
            model.addAttribute(STAFF, partyStaffService.staffOf(partyCode));
        }

        // --- Party State ---
        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(VIBE_NOTE, settings.getVibeNote());
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
        model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
        model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
        model.addAttribute(TIP_URL, settings.getTipUrl());
        model.addAttribute(COMMENT_STYLE, settings.getCommentStyle());
        // --- The hosts' lists (V29) and the link that lets them fill the lists ---
        model.addAttribute(HOST_BLOCKED, settings.getHostBlocked());
        model.addAttribute(HOST_WANTED, settings.getHostWanted());
        model.addAttribute(HOST_LINK, settings.getHostToken() == null ? null : cleanBaseUrl + "/h/" + settings.getHostToken());
        model.addAttribute(REQUEST_LIMIT, settings.getRequestLimit());
        model.addAttribute(COOLDOWN_MINUTES, settings.getCooldownMinutes());
        model.addAttribute(DUPLICATE_CHECK_WINDOW, settings.getDuplicateCheckWindow());

        // --- The server's guest limits (they are not the DJ's to set) and whether one stops guest songs now ---
        model.addAttribute(SERVER_LIMIT_PER_NETWORK, guestRequestLimiter.perClientLimit());
        model.addAttribute(SERVER_LIMIT_WINDOW_MINUTES, guestRequestLimiter.clientWindowMinutes());
        model.addAttribute(SERVER_LIMIT_PER_PARTY, guestRequestLimiter.perPartyLimit());
        model.addAttribute(PARTY_REQUESTS_USED, guestRequestLimiter.partyRequestsUsed(partyCode));
        model.addAttribute(BUSIEST_NETWORK_REQUESTS_USED, guestRequestLimiter.busiestClientRequestsUsed(partyCode));
        model.addAttribute(PARTY_LIMIT_REACHED, guestRequestLimiter.isPartyLimitReached(partyCode));

        // --- Notifications on the DJ's devices (offered only when the server has the keys) ---
        model.addAttribute(PUSH_PUBLIC_KEY, pushNotificationService.publicKey());

        // --- QR Code ---
        String guestUrl = guestUrl(partyCode);
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);
        model.addAttribute(PERMANENT_LINK, guestUrl);

        // --- Active Queue (Accepted songs only) ---
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));
        model.addAttribute(WANTED_SONGS, SongList.of(settings.getHostWanted()));

        return "dashboard";
    }

    /**
     * The organiser's name from their Google account, kept for their staff (V32: "Ola Kowalska" where the party has no "Kto gra",
     * not its code) — written only when it changed.
     */
    private void keepOwnerName(PartySettingsEntity settings, OAuth2AuthenticationToken authentication) {
        Object name = authentication.getPrincipal().getAttribute("name");
        String kept = Texts.oneLine(name == null ? null : name.toString(), PartySettingsEntity.OWNER_NAME_MAX);
        String value = kept.isEmpty() ? null : kept;
        if (!Objects.equals(value, settings.getOwnerName())) {
            partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setOwnerName(value));
        }
    }

    /** The value of {@link #PANEL_ACCESS_HEADER}: "owner", or the permissions' names. */
    static String accessValue(PartyStaffService.Access access) {
        return access.owner() ? "owner" : StaffPermission.format(access.permissions());
    }

    /** The side of the QR code on the print page, in pixels: sharp on an A4 poster (the cards show it smaller). */
    static final int QR_PRINT_SIZE = 1000;

    /**
     * The party's QR code to print and put up: {@code layout=poster} — one A4 poster — or
     * {@code cards} — eight cards to cut out and put on the tables. The texts of the code are in Polish and English at once
     * (guests are of both); the bar above it, which is not printed, follows the DJ's language. Anything else than
     * {@code cards} is the poster.
     */
    @GetMapping("/qr-print")
    public String qrPrint(@RequestParam(defaultValue = "poster") String layout, @RequestParam(required = false) String party,
                          Model model, OAuth2AuthenticationToken authentication, HttpSession session) {
        // the staff's too (V30): the code is on the tables anyway, a bartender prints a new one
        PartySettingsEntity settings = sessionHelper.access(party, authentication, session).party();
        String partyCode = settings.getPartyCode();
        String guestUrl = guestUrl(partyCode);
        model.addAttribute(PARTY_CODE, partyCode);
        // the DJ's profiles under the code: on paper the guests read them, "@djkoko" (V24)
        model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
        model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
        model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
        model.addAttribute(TIP_URL, settings.getTipUrl());
        model.addAttribute(PERMANENT_LINK, guestUrl);
        model.addAttribute(QR_CODE_BASE64, qrCodeService.generateQrCodeBase64(guestUrl, QR_PRINT_SIZE, QR_PRINT_SIZE));
        model.addAttribute(QR_LAYOUT, "cards".equals(layout) ? "cards" : "poster");
        return "qr-print";
    }

    /** The address the guests open: what the QR code holds. */
    private String guestUrl(String partyCode) {
        return cleanBaseUrl + "/p/" + partyCode;
    }

    /**
     * Displays the history of played and rejected songs — the last {@code limit} entries of the kind {@code filter}
     * says (see {@link #addHistory}).
     */
    @GetMapping("/history-view")
    public String historyView(@RequestParam(defaultValue = "" + HISTORY_PAGE_SIZE) int limit,
                              @RequestParam(required = false) String filter, @RequestParam(required = false) String party,
                              Model model, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = sessionHelper.require(access.party().getPartyCode(), StaffPermission.HISTORY, authentication, session);
        String partyCode = settings.getPartyCode();

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        addHistory(model, settings, limit, filter);

        return "history";
    }

    /**
     * Returns the history table as an HTML fragment for AJAX-based tab switching.
     * Used by the dashboard to load history without a full page reload,
     * so the DJ keeps their place on the page. "Show more" asks for the same fragment with a larger
     * {@code limit}, and a button of the filter (All / Played / Rejected) with another
     * {@code filter}.
     */
    @GetMapping("/history-view/fragment")
    public String historyFragment(@RequestParam String partyCode,
                                  @RequestParam(defaultValue = "" + HISTORY_PAGE_SIZE) int limit,
                                  @RequestParam(required = false) String filter, Model model,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(partyCode, authentication, session);
        PartySettingsEntity settings = sessionHelper.require(partyCode, StaffPermission.HISTORY, authentication, session);
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        addHistory(model, settings, limit, filter);
        // The fragment lands under the dashboard's other panels: it gets a heading of its own (the standalone page has one)
        model.addAttribute(HISTORY_HEADING, true);
        return "history :: historyTableContent";
    }

    /**
     * Puts the last {@code limit} entries of the history in the model — songs of guests that played or were rejected — at least
     * one page, at most {@value #HISTORY_MAX_LIMIT}
     * (the queries are always bounded) — and what the "Show more" button needs: whether there are older ones to show
     * and the limit to ask for next. The entries are of the kind the filter says (a missing or unknown filter is
     * "all"); the filter goes into the model too, so that its button is the lit one.
     */
    private void addHistory(Model model, PartySettingsEntity settings, int requestedLimit, String filterParam) {
        int limit = Math.max(HISTORY_PAGE_SIZE, Math.min(requestedLimit, HISTORY_MAX_LIMIT));
        HistoryFilter filter = HistoryFilter.fromParam(filterParam);
        PlayHistoryService.Page page = playHistoryService.getHistory(settings.getPartyCode(), limit, filter);
        model.addAttribute(HISTORY_FILTER, filter.param());
        model.addAttribute(HISTORY, page.entries());
        model.addAttribute(HISTORY_HAS_MORE, page.hasMore() && limit < HISTORY_MAX_LIMIT);
        model.addAttribute(HISTORY_NEXT_LIMIT, Math.min(limit + HISTORY_PAGE_SIZE, HISTORY_MAX_LIMIT));
    }

    /**
     * AJAX polling endpoint that returns a partial HTML fragment of the song request table.
     * <p>
     * Supports ETag-based conditional responses: if the queue hasn't changed since the
     * client's last poll, returns 304 Not Modified (empty body). This avoids unnecessary
     * Thymeleaf rendering, reduces bandwidth, and prevents the client-side DOM replacement
     * that would reset any active column sorting.
     */
    @GetMapping("/dashboard/updates")
    public String getDashboardUpdates(@RequestParam String partyCode, Model model,
                                      HttpServletRequest request, HttpServletResponse response,
                                      OAuth2AuthenticationToken authentication, HttpSession session) {
        // the owner or anyone of the staff (V30); an access taken away is a 403 — the page loads itself again and says so
        PartyStaffService.Access access = sessionHelper.access(partyCode, authentication, session);
        PartySettingsEntity settings = access.party();
        response.setHeader(PANEL_ACCESS_HEADER, accessValue(access));
        // Not part of the ETag: the warnings follow the limits even while the queue stays the same
        response.setHeader(GUEST_LIMITS_HEADER, guestLimitFlags(partyCode));
        response.setHeader(GUEST_LIMITS_USE_HEADER, guestRequestLimiter.busiestClientRequestsUsed(partyCode)
                + "," + guestRequestLimiter.partyRequestsUsed(partyCode));
        response.setHeader(PARTY_ACTIVE_HEADER, String.valueOf(settings.isActive()));

        // --- Lightweight fingerprint check (avoids full query + render) ---
        // the hosts' wishes too: a song becomes ⭐ when they add it, with the queue as it was
        String fingerprint = djService.getQueueFingerprint(partyCode);
        String wanted = settings.getHostWanted() == null ? "" : "-w" + Integer.toHexString(settings.getHostWanted().hashCode());
        String etag = "\"q-" + fingerprint + wanted + "\"";

        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", etag);
            return null;  // response is short-circuited — Spring skips view resolution
        }

        // --- Full render (queue changed) ---
        response.setHeader("ETag", etag);
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));
        model.addAttribute(WANTED_SONGS, SongList.of(settings.getHostWanted()));
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(ACCESS, access);   // the rows' buttons by the person's permissions (V32)
        return "dashboard :: songTableBody";
    }

    /** The value of {@link #GUEST_LIMITS_HEADER}. */
    private String guestLimitFlags(String partyCode) {
        return guestRequestLimiter.isPartyLimitReached(partyCode) ? FLAG_PARTY_FULL : FLAG_NONE;
    }
}

