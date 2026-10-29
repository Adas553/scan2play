package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.HistoryFilter;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.AiHealthMonitor;
import com.scan2play.service.DemoParty;
import com.scan2play.service.DjService;
import com.scan2play.service.LogoImage;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlayHistoryService;
import com.scan2play.service.PushNotificationService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.service.QrCodeService;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.DjLibraryService;
import com.scan2play.util.SongList;
import com.scan2play.util.Texts;
import com.scan2play.util.Times;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final PlanService planService;
    private final AiHealthMonitor aiHealthMonitor;

    /**
     * On every answer of the queue poll, 304 too: the warnings above the queue that hold now, comma-separated —
     * {@value #FLAG_PARTY_FULL} (the party's 24-hour limit stops guest songs), {@value #FLAG_AI_DOWN} (the AI does not judge the
     * requests, {@code AiHealthMonitor}; the review of 2026-10-21, O2) — or {@value #FLAG_NONE}. The dashboard shows or hides its
     * warnings by it.
     */
    static final String GUEST_LIMITS_HEADER = "X-Guest-Limits";
    static final String FLAG_PARTY_FULL = "party-full";
    static final String FLAG_AI_DOWN = "ai-down";
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
        this.cleanBaseUrl = com.scan2play.util.Texts.withoutTrailingSlash(rawBaseUrl);
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
            keepOwner(settings, authentication);
        }

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());
        model.addAttribute(WELCOME, owner && settings.getWelcomeDismissedAt() == null);

        // --- Who works on it (V30, V32): the owner everything, the staff what the owner gave them; the panels to switch between ---
        model.addAttribute(IS_OWNER, owner);
        model.addAttribute(ACCESS, access);
        model.addAttribute(PANEL_ACCESS, accessValue(access));
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        List<PartyStaffService.Panel> panels = partyStaffService.panelsOf(authentication.getName());
        model.addAttribute(PANELS, panels);
        model.addAttribute(HAS_OWN_PARTY, owner || panels.stream().anyMatch(PartyStaffService.Panel::own));
        addSettings(model, settings, owner);

        // --- QR Code ---
        String guestUrl = guestUrl(partyCode);
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);
        model.addAttribute(PERMANENT_LINK, guestUrl);

        // --- Active Queue (Accepted songs only) ---
        List<SongRequestEntity> queue = djService.getDashboardQueue(partyCode);
        model.addAttribute(HISTORY, queue);
        model.addAttribute(DEDICATIONS, djService.dedicationsOf(queue));   // the guests' dedications under their songs (V38)
        model.addAttribute(LIBRARY_MARKS, libraryMarks(settings, queue));   // "✓" / "?" / "✗" by the DJ's library (V41, V42)
        // "Ukryj „Nie masz”" beside the search: for anyone of the panel, while the party has a library its plan marks by
        model.addAttribute(LIBRARY_ON, planService.allows(settings, PlanFeature.LIBRARY) && djService.libraryOf(partyCode).isPresent());
        model.addAttribute(WANTED_SONGS, SongList.of(planService.asPlanned(settings).getHostWanted()));
        model.addAttribute(BLOCKED_SONGS, SongList.of(planService.asPlanned(settings).getHostBlocked()));

        return "dashboard";
    }

    /** The queue's marks of the DJ's library (V41): only with the plan that has it — without Pro the library waits, unmarked. */
    private Map<Long, LibraryMark> libraryMarks(PartySettingsEntity settings, List<SongRequestEntity> songs) {
        return planService.allows(settings, PlanFeature.LIBRARY) ? djService.libraryMarksOf(settings.getPartyCode(), songs) : Map.of();
    }

    /**
     * The page "Ustawienia imprezy" (the design review, 2026-10-10): what a DJ sets before a party, not during it — the hosts' lists,
     * the profiles and the tip link, the guests' limits, this device. The panel keeps the vibe, "Kto gra" and the QR code, and lists
     * these as rows with their state that lead here. Each section only for whom may change it (V32: hidden, not greyed); a person
     * with none of them still has "To urządzenie". The party the page names ({@code ?party=}), as the panel; one the person may not
     * open is a 403.
     */
    @GetMapping("/settings")
    public String settingsPage(@RequestParam(required = false) String party, Model model, OAuth2AuthenticationToken authentication,
                               HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = access.party();
        model.addAttribute(PARTY_CODE, settings.getPartyCode());
        model.addAttribute(IS_ACTIVE, settings.isActive());   // the heading of every DJ's page (dj-nav)
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        addSettings(model, settings, access.owner());
        return "settings";
    }

    /** What the panel's settings and the page "Ustawienia imprezy" show: the party's own values and the server's limits' use. */
    private void addSettings(Model model, PartySettingsEntity settings, boolean owner) {
        String partyCode = settings.getPartyCode();
        if (owner) {
            model.addAttribute(STAFF, partyStaffService.staffOf(partyCode));
            // the DJ's library (V41): the organiser's card and its row in "Więcej" alone
            Optional<DjLibraryService.Info> library = djService.libraryOf(partyCode);
            model.addAttribute(LIBRARY_TRACKS, library.map(DjLibraryService.Info::tracks).orElse(null));
            model.addAttribute(LIBRARY_UPLOADED, library.map(info -> Times.day(info.uploadedAt())).orElse(null));
            model.addAttribute(LIBRARY_PLAYLIST, library.map(DjLibraryService.Info::playlist).orElse(null));
            model.addAttribute(LIBRARY_MAX_BYTES, DjLibraryController.MAX_BYTES);
            model.addAttribute(LIBRARY_MAX_MB, DjLibraryController.MAX_BYTES / (1024 * 1024));
            // the DJ's logo (V45): the organiser's card shows the one saved, whatever the plan shows the guests
            model.addAttribute(DJ_LOGO, settings.logoPath());
            model.addAttribute(LOGO_MAX_BYTES, LogoImage.MAX_BYTES);
            model.addAttribute(LOGO_MAX_MB, LogoImage.MAX_BYTES / (1024 * 1024));
            model.addAttribute(LOGO_MAX_SIDE, LogoImage.MAX_SIDE);
        }
        // what the organiser's plan has (V35): the cards of a feature without Pro say so to the organiser, in place of their form;
        // what the party works with — the comment style, the guests' limits — is the plan's (the saved values wait for Pro)
        model.addAttribute(PLAN, planService.view(settings, owner));
        PartySettingsEntity planned = planService.asPlanned(settings);

        // --- Party State ---
        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(VIBE_NOTE, settings.getVibeNote());
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
        model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
        model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
        model.addAttribute(TIP_URL, settings.getTipUrl());
        model.addAttribute(COMMENT_STYLE, planned.getCommentStyle());
        model.addAttribute(DEDICATIONS_ON, settings.isDedicationsOn());   // the guests' dedications on or off (V38)
        // --- The hosts' lists (V29) and the link that lets them fill the lists ---
        model.addAttribute(HOST_BLOCKED, settings.getHostBlocked());
        model.addAttribute(HOST_WANTED, settings.getHostWanted());
        model.addAttribute(HOST_BLOCKED_COUNT, entries(settings.getHostBlocked()));
        model.addAttribute(HOST_WANTED_COUNT, entries(settings.getHostWanted()));
        model.addAttribute(SONG_LIST_MAX, SongList.MAX_LINES);
        // a link made more than 30 days ago (V37) is shown as none: it opens nothing, and "Utwórz link" makes a new one
        boolean hostLinkWorks = settings.hostLinkWorks(Instant.now());
        model.addAttribute(HOST_LINK, hostLinkWorks ? cleanBaseUrl + "/h/" + settings.getHostToken() : null);
        model.addAttribute(HOST_LINK_UNTIL, hostLinkWorks ? Times.day(PartySettingsEntity.linkWorksUntil(settings.getHostTokenMadeAt())) : null);
        model.addAttribute(REQUEST_LIMIT, planned.getRequestLimit());
        model.addAttribute(COOLDOWN_MINUTES, planned.getCooldownMinutes());
        model.addAttribute(DUPLICATE_CHECK_WINDOW, planned.getDuplicateCheckWindow());

        // --- The server's guest limits (they are not the DJ's to set) and whether one stops guest songs now ---
        model.addAttribute(SERVER_LIMIT_PER_NETWORK, guestRequestLimiter.perClientLimit());
        model.addAttribute(SERVER_LIMIT_WINDOW_MINUTES, guestRequestLimiter.clientWindowMinutes());
        model.addAttribute(SERVER_LIMIT_PER_PARTY, guestRequestLimiter.perPartyLimit());
        model.addAttribute(PARTY_REQUESTS_USED, guestRequestLimiter.partyRequestsUsed(partyCode));
        model.addAttribute(BUSIEST_NETWORK_REQUESTS_USED, guestRequestLimiter.busiestClientRequestsUsed(partyCode));
        model.addAttribute(PARTY_LIMIT_REACHED, guestRequestLimiter.isPartyLimitReached(partyCode));
        model.addAttribute(AI_DOWN, aiDown(partyCode));

        // --- Notifications on the DJ's devices (offered only when the server has the keys) ---
        model.addAttribute(PUSH_PUBLIC_KEY, pushNotificationService.publicKey());
    }

    // ---- The demo (DemoController, DemoParty): the panel of a party no one owns, shown to anyone, only to look at ----

    /** The demo's panel: as its owner sees it — every card, every button (the page's script keeps them from sending) — and the queue given. */
    void demoPanel(Model model, PartySettingsEntity settings, List<SongRequestEntity> queue) {
        PartyStaffService.Access access = demoAccess(settings);
        String partyCode = settings.getPartyCode();
        model.addAttribute(IS_ACTIVE, settings.isActive());
        model.addAttribute(IS_OWNER, true);
        model.addAttribute(PANEL_ACCESS, accessValue(access));
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(PANELS, List.of());
        model.addAttribute(HAS_OWN_PARTY, true);
        addSettings(model, settings, true);
        String guestUrl = guestUrl(partyCode);
        model.addAttribute(QR_CODE_BASE64, qrCodeService.generateQrCodeBase64(guestUrl, 250, 250));
        model.addAttribute(PERMANENT_LINK, guestUrl);
        demoQueue(model, settings, queue);
    }

    /** The demo's queue: the rows of the panel ({@code dashboard :: songTableBody}) for the queue given. */
    void demoQueue(Model model, PartySettingsEntity settings, List<SongRequestEntity> queue) {
        model.addAttribute(PARTY_CODE, settings.getPartyCode());
        model.addAttribute(ACCESS, demoAccess(settings));
        model.addAttribute(PLAN, planService.view(settings, true));
        model.addAttribute(HISTORY, queue);
        model.addAttribute(DEDICATIONS, djService.dedicationsOf(queue));
        model.addAttribute(WANTED_SONGS, SongList.of(planService.asPlanned(settings).getHostWanted()));
        model.addAttribute(BLOCKED_SONGS, SongList.of(planService.asPlanned(settings).getHostBlocked()));
    }

    /** The demo's History tab: the fragment the panel's tab shows ({@code history :: historyTableContent}). */
    void demoHistory(Model model, PartySettingsEntity settings, int limit, String filter) {
        model.addAttribute(PARTY_CODE, settings.getPartyCode());
        model.addAttribute(IS_OWNER, true);
        model.addAttribute(ACCESS, demoAccess(settings));
        model.addAttribute(PLAN, planService.view(settings, true));
        addHistory(model, settings, limit, filter);
        model.addAttribute(HISTORY_HEADING, true);
    }

    private static PartyStaffService.Access demoAccess(PartySettingsEntity settings) {
        return new PartyStaffService.Access(settings, true, java.util.Set.of());
    }

    /** The entries of a hosts' list as stored ({@code SongList.tidy}: one per line), 0 for none. */
    static int entries(String list) {
        return list == null || list.isBlank() ? 0 : list.split("\n").length;
    }

    /**
     * What the organiser's Google account says of them, kept with the party — written only when it changed: their name for their
     * staff (V32: "Ola Kowalska" where the party has no "Kto gra", not its code), and their address to write to them about the
     * account and help (V48). Never the staff's: only the owner's panel calls it.
     */
    private void keepOwner(PartySettingsEntity settings, OAuth2AuthenticationToken authentication) {
        Object name = authentication.getPrincipal().getAttribute("name");
        String kept = Texts.oneLine(name == null ? null : name.toString(), PartySettingsEntity.OWNER_NAME_MAX);
        String ownerName = kept.isEmpty() ? null : kept;
        String ownerEmail = ownerEmail(authentication);
        if (!Objects.equals(ownerName, settings.getOwnerName()) || !Objects.equals(ownerEmail, settings.getOwnerEmail())) {
            partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> {
                s.setOwnerName(ownerName);
                s.setOwnerEmail(ownerEmail);
            });
        }
    }

    /** The organiser's address as kept: only one Google verified — an unverified one could be anyone's — else null. */
    static String ownerEmail(OAuth2AuthenticationToken authentication) {
        String address = StaffController.verifiedAddress(authentication);
        return address == null || address.strip().length() > PartySettingsEntity.OWNER_EMAIL_MAX ? null : address.strip();
    }

    /**
     * "✕" or "Napisz do nas" on the panel's welcome card (V48): closed for good, on every device of the organiser's — the server
     * keeps it, not the browser. The organiser's alone; the staff never see the card. Written once: a second close keeps the first
     * time.
     */
    @PostMapping("/dashboard/welcome-dismiss")
    public ResponseEntity<Void> dismissWelcome(@RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                                               HttpSession session) {
        PartySettingsEntity settings = sessionHelper.requireOwner(partyCode, authentication, session);
        if (settings.getWelcomeDismissedAt() == null) {
            partySettingsCommandService.updateSettings(partyCode, s -> {
                if (s.getWelcomeDismissedAt() == null) {
                    s.setWelcomeDismissedAt(Instant.now());
                }
            });
        }
        return ResponseEntity.noContent().build();
    }

    /** The value of {@link #PANEL_ACCESS_HEADER}: "owner", or the permissions' names. */
    static String accessValue(PartyStaffService.Access access) {
        return access.owner() ? "owner" : StaffPermission.format(access.permissions());
    }

    /** The side of the QR code on the print page, in pixels: sharp on an A4 poster (the cards show it smaller). */
    static final int QR_PRINT_SIZE = 1000;

    /**
     * The party's QR code to print and put up: {@code layout=poster} — one A4 poster — or
     * {@code cards} — eight cards on a sheet to cut out and put on the tables — or {@code tent} — a table tent, one A4 sheet folded
     * across its middle (V50); the cards and the tents as many as the party's tables, numbered, when the DJ set them. The bar above it,
     * which is not printed, follows the DJ's language. Anything else is the poster.
     */
    @GetMapping("/qr-print")
    public String qrPrint(@RequestParam(defaultValue = "poster") String layout, @RequestParam(required = false) String party,
                          Model model, OAuth2AuthenticationToken authentication, HttpSession session, Locale locale) {
        // the staff's too (V30): the code is on the tables anyway, a bartender prints a new one
        // the profiles and the tip link as the organiser's plan has them (V35)
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = planService.asPlanned(access.party());
        String partyCode = settings.getPartyCode();
        String guestUrl = guestUrl(partyCode);
        model.addAttribute(PARTY_CODE, partyCode);
        // the DJ's profiles under the code: on paper the guests read them, "@djkoko" (V24)
        model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
        model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
        model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
        model.addAttribute(TIP_URL, settings.getTipUrl());
        model.addAttribute(DJ_LOGO, settings.logoPath());
        model.addAttribute(PERMANENT_LINK, guestUrl);
        model.addAttribute(QR_CODE_BASE64, qrCodeService.generateQrCodeBase64(guestUrl, QR_PRINT_SIZE, QR_PRINT_SIZE));
        model.addAttribute(QR_LAYOUT, "cards".equals(layout) || "tent".equals(layout) ? layout : "poster");
        // the print as the DJ set it (V46): the words, the language, what of the DJ's it shows — and the editor for whom may change
        // the party's card "Impreza"; the parts left off are rendered hidden, so the editor's preview shows them again at once
        model.addAttribute(POSTER, settings);
        model.addAttribute(PRINT_LANG, settings.getPosterLanguage().printed(locale.getLanguage()));
        model.addAttribute(POSTER_EDIT, access.may(StaffPermission.VIBE));
        model.addAttribute(POSTER_THEMES, com.scan2play.model.PosterTheme.values());
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
        model.addAttribute(PLAN, planService.view(settings, access.owner()));
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
        model.addAttribute(PLAN, planService.view(settings, access.owner()));
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
        // an empty filter asks once more, for one entry of any kind (a bounded read, only then)
        model.addAttribute(HISTORY_ANY, !page.entries().isEmpty() || (filter != HistoryFilter.ALL
                && !playHistoryService.getHistory(settings.getPartyCode(), 1, HistoryFilter.ALL).entries().isEmpty()));
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
        // the hosts' lists too: a song becomes ⭐ when they add it, with the queue as it was (or 🚫, one the DJ added, V44)
        // the hosts' wishes and the tips as the organiser's plan has them (V35): a change of the plan sends the rows again
        PartySettingsEntity planned = planService.asPlanned(settings);
        PlanService.View plan = planService.view(settings, access.owner());
        String fingerprint = djService.getQueueFingerprint(partyCode);
        // a new library (V41) marks the same queue anew: its upload's moment is part of the ETag (only with the plan that shows marks)
        String library = plan.allows("LIBRARY") ? djService.libraryOf(partyCode)
                .map(info -> "-l" + Long.toHexString(info.uploadedAt().toEpochMilli())).orElse("") : "";
        String wanted = (planned.getHostWanted() == null ? "" : "-w" + Integer.toHexString(planned.getHostWanted().hashCode()))
                + (planned.getHostBlocked() == null ? "" : "-b" + Integer.toHexString(planned.getHostBlocked().hashCode()))
                + (plan.allows("TIPS") ? "" : "-f") + library;
        String etag = "\"q-" + fingerprint + wanted + "\"";

        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", etag);
            return null;  // response is short-circuited — Spring skips view resolution
        }

        // --- Full render (queue changed) ---
        // The list read now, not the 3 s cache the guests' page fills: a request committed after the cache was filled would get
        // the new ETag over the old list, and every later poll a 304 — the request never shown (review 2026-10-14 K1)
        response.setHeader("ETag", etag);
        List<SongRequestEntity> waiting = djService.getWaiting(partyCode);
        model.addAttribute(HISTORY, waiting);
        model.addAttribute(DEDICATIONS, djService.dedicationsOf(waiting));
        model.addAttribute(LIBRARY_MARKS, libraryMarks(settings, waiting));
        model.addAttribute(WANTED_SONGS, SongList.of(planned.getHostWanted()));
        model.addAttribute(BLOCKED_SONGS, SongList.of(planned.getHostBlocked()));
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(ACCESS, access);   // the rows' buttons by the person's permissions (V32)
        model.addAttribute(PLAN, plan);       // and by the organiser's plan (V35): no 💸 without Pro
        return "dashboard :: songTableBody";
    }

    /**
     * "Odblokuj prośby gości" in the warning above the queue (review 2026-10-14 W4): the party's 24-hour limit of guest requests starts
     * again — one guest with a script could spend it and leave every other guest refused until the next day. For whom may set the
     * guests' limits (the organiser, a person of the staff with LIMITS); not a feature of a plan — it is the server's limit, the same
     * for every party. The next poll of every window says the limit is no longer reached (X-Guest-Limits).
     */
    @PostMapping("/dashboard/guest-limit-reset")
    public String resetGuestLimit(@RequestParam String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.require(partyCode, StaffPermission.LIMITS, authentication, session);
        guestRequestLimiter.resetParty(partyCode);
        return REDIRECT_DASHBOARD + "?party=" + partyCode;
    }

    /** The value of {@link #GUEST_LIMITS_HEADER}. */
    private String guestLimitFlags(String partyCode) {
        List<String> flags = new ArrayList<>(2);
        if (guestRequestLimiter.isPartyLimitReached(partyCode)) {
            flags.add(FLAG_PARTY_FULL);
        }
        if (aiDown(partyCode)) {
            flags.add(FLAG_AI_DOWN);
        }
        return flags.isEmpty() ? FLAG_NONE : String.join(",", flags);
    }

    /** Whether the panel says the AI does not judge the requests now — never the demo's, which asks no AI. */
    private boolean aiDown(String partyCode) {
        return !DemoParty.isDemo(partyCode) && aiHealthMonitor.isAiDown();
    }
}

