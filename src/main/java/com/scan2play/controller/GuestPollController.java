package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlanService;
import com.scan2play.service.PollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * A guest's side of "Pytanie do gości" (V74, Pro): the DJ's question on the party page — above the request form, or on the page
 * "the DJ is not taking requests" when the DJ closed the requests to ask it alone — answered with one tap. The card is fetched again
 * (guest-poll.js) when the guest comes back to the page, as the list is; an answer goes in the background and comes back as the card
 * with the results. Without the script: a plain form, back to the party page.
 */
@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
public class GuestPollController {

    private static final String CARD = "fragments/guest-poll :: guestPoll";

    private final PartySettingsQueryService partySettingsQueryService;
    private final PollService pollService;
    private final PlanService planService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final PollView pollView;

    /** The card alone: the question asked now (or just ended, with its results), or an empty place for one. */
    @GetMapping("/{partyCode}/poll")
    public String card(@PathVariable String partyCode, Model model, HttpServletRequest request) {
        PartySettingsEntity settings = party(partyCode);
        model.addAttribute(PARTY_CODE, partyCode);
        pollView.addForGuest(model, settings, request);
        return CARD;
    }

    /**
     * A guest's answer ({@code choice}: the answer's index) to the question {@code id} — or a new one in place of the one they gave.
     * The same limit of an address as a 👍 ({@link GuestRequestLimiter#tryAcquireVote}). Sent by guest-poll.js
     * ({@code X-Requested-With: fetch}): the card again, with a note when it did not count; without the script back to the page.
     */
    @PostMapping("/{partyCode}/poll/{id}")
    public String vote(@PathVariable String partyCode, @PathVariable long id, @RequestParam(defaultValue = "-1") int choice,
                       @RequestHeader(value = "X-Requested-With", required = false) String requestedWith, Model model,
                       HttpSession session, HttpServletRequest request) {
        PartySettingsEntity settings = party(partyCode);
        String note = null;
        if (!planService.allows(settings, PlanFeature.POLLS)) {
            note = "guest.poll.note.ended";
        } else if (guestRequestLimiter.tryAcquireVote(guestRequestLimiter.clientIp(request), partyCode).isPresent()) {
            note = "guest.poll.note.too_many";
        } else {
            note = switch (pollService.vote(partyCode, id, PollService.voterOf(session), choice)) {
                case VOTED, CHANGED -> null;
                case NOT_ASKED, INVALID -> "guest.poll.note.ended";
                case FULL -> "guest.poll.note.full";
            };
        }
        if (!"fetch".equals(requestedWith)) {
            return "redirect:/p/" + partyCode;
        }
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(POLL_NOTE, note);
        pollView.addForGuest(model, settings, request);
        return CARD;
    }

    private PartySettingsEntity party(String partyCode) {
        try {
            return partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
