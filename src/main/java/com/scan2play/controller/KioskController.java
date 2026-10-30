package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.PartyScheduleService;
import com.scan2play.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The venue's kiosk (V56, Pro): a tablet at the bar where guests without a phone ask for a song and vote — the request form, and
 * beside it the most wanted songs with 👍, as the TV screen shows them. Opened by its secret link ({@code /k/{token}}, made on
 * "Ustawienia imprezy" by whoever has the permission KIOSK), without a login; a code that is no kiosk's, or a party without Pro, is a
 * 404.
 * <p>
 * One tablet is many guests, one after another: each guest's <b>turn</b> is a session of its own — "Następny gość" (or the page by
 * itself, after a while without a touch) ends the session, and the next guest has the DJ's limit and their own 👍 again. Against one
 * person at the tablet, the kiosk has its own limits ({@link GuestRequestLimiter#tryAcquireKiosk}, {@code tryAcquireKioskVote}) in
 * place of an address's — the tablet shares the venue's Wi-Fi with the phones —; the party's day counts its requests as every
 * guest's. A request goes the guests' way ({@link GuestController#send}), marked as the kiosk's for the DJ's queue. No link leads
 * out of the page: the DJ's profiles and tip link are for the guests' own phones.
 */
@Controller
@RequestMapping("/k")
@RequiredArgsConstructor
@Slf4j
public class KioskController {

    private final PartySettingsRepository partySettingsRepository;
    private final PlanService planService;
    private final GuestController guests;
    private final GuestQueueService guestQueueService;
    private final GuestSessionService guestSessionService;
    private final GuestVoteService guestVoteService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final PartyScheduleService partyScheduleService;
    private final MessageSource messageSource;

    /** The kiosk's page: the form and the most wanted, or — the party closed — when it opens. */
    @GetMapping("/{token}")
    public String kiosk(@PathVariable String token, Model model, HttpSession session) {
        PartySettingsEntity party = party(token);
        addPage(model, token, party, session);
        return "kiosk";
    }

    /** The most wanted alone, fetched again by the page every few seconds (kiosk.js): the room's votes, this turn's 👍 marked. */
    @GetMapping("/{token}/list")
    public String list(@PathVariable String token, Model model, HttpSession session) {
        PartySettingsEntity party = party(token);
        addList(model, token, party, session);
        return "kiosk :: kioskList";
    }

    /**
     * A guest's request at the kiosk, asynchronous as the party page's (the AI takes seconds): the answer on the kiosk's page — its
     * verdict and comment, then "Następny gość". A limit's refusal or a mood: back to the form, saying why.
     */
    @PostMapping("/{token}/request")
    public Callable<String> request(@PathVariable String token, @RequestParam String songName,
                                    @RequestParam(required = false) String dedication, Model model, HttpSession session,
                                    RedirectAttributes redirectAttributes) {
        Locale locale = LocaleContextHolder.getLocale();
        return () -> {
            PartySettingsEntity party = party(token);
            if (!party.isActive()) {
                return "redirect:/k/" + token;   // the page says it is closed
            }
            // the guest's limit as the organiser's plan has it (V35), as on the party page
            GuestController.Sent sent = guests.send(party.getPartyCode(), planService.asPlanned(party), songName, dedication, session,
                    null, true, locale);
            if (sent instanceof GuestController.Sent.Refused refused) {
                redirectAttributes.addFlashAttribute(ERROR_MESSAGE,
                        messageSource.getMessage(refused.messageKey(), refused.args(), locale));
                return "redirect:/k/" + token;
            }
            if (sent instanceof GuestController.Sent.Mood mood) {
                redirectAttributes.addFlashAttribute(LAST_REQUEST, mood.songName());
                redirectAttributes.addFlashAttribute(LAST_DEDICATION, mood.dedication());
                redirectAttributes.addFlashAttribute(ERROR_MESSAGE, messageSource.getMessage("guest.error.song_only", null, locale));
                return "redirect:/k/" + token;
            }
            model.addAttribute(RESPONSE, ((GuestController.Sent.Answered) sent).response());
            addPage(model, token, party, session);
            return "kiosk";
        };
    }

    /**
     * A 👍 at the kiosk ({@code on=true}) or taken back: one per song in a guest's turn, not on this turn's own request, within the
     * kiosk's limit of votes. Sent by kiosk.js ({@code X-Requested-With: fetch}): the answer is the list with the new votes and a note
     * when the 👍 did not count. Without the script: the page again.
     */
    @PostMapping("/{token}/vote")
    public String vote(@PathVariable String token, @RequestParam long id, @RequestParam(defaultValue = "true") boolean on,
                       @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                       Model model, HttpSession session) {
        PartySettingsEntity party = party(token);
        String partyCode = party.getPartyCode();
        String note = null;
        if (!party.isActive()) {
            note = "guest.vote.party_ended";
        } else if (!guestSessionService.myRequestIds(session, partyCode).contains(id)) {   // this turn's own song: no 👍 on it
            Optional<Long> wait = guestRequestLimiter.tryAcquireKioskVote(partyCode);
            if (wait.isPresent()) {
                note = "guest.vote.too_many";
            } else {
                GuestVoteService.Result result = on ? guestVoteService.vote(session, partyCode, id)
                        : guestVoteService.takeBack(session, partyCode, id);
                if (result == GuestVoteService.Result.GONE) {
                    note = "guest.vote.gone";
                }
            }
        }
        if (!"fetch".equals(requestedWith)) {
            return "redirect:/k/" + token;
        }
        addList(model, token, party, session);
        model.addAttribute(VOTE_NOTE, note == null ? null : messageSource.getMessage(note, null, LocaleContextHolder.getLocale()));
        return "kiosk :: kioskList";
    }

    /** "Następny gość": this guest's turn ends — the session goes, and the next guest starts on the kiosk's page with a new one. */
    @PostMapping("/{token}/next")
    public String next(@PathVariable String token, HttpSession session) {
        party(token);
        session.invalidate();
        return "redirect:/k/" + token;
    }

    private void addPage(Model model, String token, PartySettingsEntity party, HttpSession session) {
        PartySettingsEntity planned = planService.asPlanned(party);
        model.addAttribute(KIOSK_TOKEN, token);
        model.addAttribute(PARTY_CODE, party.getPartyCode());
        model.addAttribute(DJ_NAME, party.getDjName());
        model.addAttribute(DJ_LOGO, planned.logoPath());
        model.addAttribute(DEDICATIONS_ON, party.isDedicationsOn());
        model.addAttribute(IS_ACTIVE, party.isActive());
        model.addAttribute(OPENS_AT, party.isActive() ? null : partyScheduleService.guestOpens(party, LocaleContextHolder.getLocale()));
        addList(model, token, party, session);
    }

    private void addList(Model model, String token, PartySettingsEntity party, HttpSession session) {
        String partyCode = party.getPartyCode();
        model.addAttribute(KIOSK_TOKEN, token);
        model.addAttribute(IS_ACTIVE, party.isActive());
        if (party.isActive()) {
            model.addAttribute(TV_SONGS, guestQueueService.mostWanted(partyCode));
            model.addAttribute(KIOSK_MY_VOTES, guestVoteService.myVotes(session, partyCode));
            model.addAttribute(KIOSK_MY_REQUESTS, guestSessionService.myRequestIds(session, partyCode));
        }
    }

    /** The party whose kiosk this is, with the plan that has it — else a 404 (a code made up, a link made new, no Pro). */
    private PartySettingsEntity party(String token) {
        if (token == null || token.length() > 64) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return partySettingsRepository.findByKioskToken(token)
                .filter(party -> planService.allows(party, PlanFeature.KIOSK))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
