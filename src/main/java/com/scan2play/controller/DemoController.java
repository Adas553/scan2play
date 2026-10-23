package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.DemoParty;
import com.scan2play.service.DjService;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SiteCounter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The demo (the owner, 2026-10-24; {@link DemoParty}): the DJ's panel of the demo party, to anyone, without a login — only to look at.
 * The page is the real panel ({@code dashboard.html} with {@code demo}): every card and button, and its script ({@code demo.js})
 * sends nothing — a form or a link to a DJ's page says "start your own party". The queue and the history are polled from here, as
 * the panel polls {@code /dj/…}. Each visitor sees the examples and their own requests (sent from {@code /p/DEMO} in the same
 * browser, remembered in their session) — never another visitor's words.
 */
@Controller
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DjDashboardController panel;
    private final PartySettingsQueryService partySettingsQueryService;
    private final DjService djService;
    private final GuestSessionService guestSessionService;
    private final SiteCounter siteCounter;

    @GetMapping
    public String demo(Model model, HttpServletRequest request, CsrfToken csrfToken,
                       @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        siteCounter.countVisit(SiteCounter.Event.DEMO, userAgent);
        // the page's forms carry a token (sent nowhere, demo.js): loaded while the response can still make the session
        csrfToken.getToken();
        PartySettingsEntity settings = party();
        panel.demoPanel(model, settings, visible(request.getSession(false)));
        model.addAttribute(ViewAttributes.DEMO, true);
        return "dashboard";
    }

    /** The queue alone, as the panel's poll asks for it ({@code polling.js}): the same headers, an ETag of what this visitor sees. */
    @GetMapping("/updates")
    public String updates(Model model, HttpServletRequest request, HttpServletResponse response) {
        PartySettingsEntity settings = party();
        List<SongRequestEntity> queue = visible(request.getSession(false));
        response.setHeader(DjDashboardController.PANEL_ACCESS_HEADER, "owner");
        response.setHeader(DjDashboardController.PARTY_ACTIVE_HEADER, "true");
        response.setHeader(DjDashboardController.GUEST_LIMITS_HEADER, DjDashboardController.FLAG_NONE);
        String etag = "\"demo-" + Integer.toHexString(queue.stream()
                .map(song -> song.getId() + ":" + song.getVotes() + ":" + song.getDedications() + ":" + song.getTips())
                .collect(Collectors.joining(",")).hashCode()) + "\"";
        response.setHeader("ETag", etag);
        if (etag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return null;
        }
        panel.demoQueue(model, settings, queue);
        model.addAttribute(ViewAttributes.DEMO, true);
        return "dashboard :: songTableBody";
    }

    /** The History tab's fragment, as the panel's tab asks for it ({@code tabs.js}). */
    @GetMapping("/history")
    public String history(@RequestParam(defaultValue = "" + DjDashboardController.HISTORY_PAGE_SIZE) int limit,
                          @RequestParam(required = false) String filter, Model model) {
        panel.demoHistory(model, party(), limit, filter);
        model.addAttribute(ViewAttributes.DEMO, true);
        return "history :: historyTableContent";
    }

    private PartySettingsEntity party() {
        try {
            return partySettingsQueryService.getSettings(DemoParty.CODE);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);   // not made yet (DemoParty makes it at every start)
        }
    }

    /** The demo's waiting requests this visitor may see: the examples (checked) and their own (their session's), no one else's. */
    private List<SongRequestEntity> visible(HttpSession session) {
        Set<Long> mine = session == null ? Set.of() : guestSessionService.myRequestIds(session, DemoParty.CODE);
        return djService.getWaiting(DemoParty.CODE).stream()
                .filter(song -> song.isChecked() || mine.contains(song.getId()))
                .toList();
    }
}
