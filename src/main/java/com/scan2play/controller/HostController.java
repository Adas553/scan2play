package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.EventPlanService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlanService;
import com.scan2play.service.WishService;
import com.scan2play.util.SongList;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The hosts' page (V29): the couple at a wedding, the host of a party or the pub's owner fill in the songs not to play and the
 * songs to play, without an account — the DJ gives them the link {@code /h/{token}} (a secret of 128 bits, made and taken away in
 * the DJ's panel). An unknown or old secret is a 404: nothing tells it from a page that never was. An event's hosts write its plan
 * ("Plan imprezy", V77) on {@code /h/e/{token}}: the evening's moments and that event's own two lists.
 */
@Controller
@RequestMapping("/h")
@RequiredArgsConstructor
@Slf4j
public class HostController {

    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsCommandService partySettingsCommandService;
    private final PlanService planService;
    private final WishService wishService;
    private final EventPlanService eventPlanService;
    private final MessageSource messageSource;

    @GetMapping("/{token}")
    public String hostLists(@PathVariable String token, Model model) {
        PartySettingsEntity settings = partyOf(token);
        model.addAttribute(HOST_TOKEN, token);
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(HOST_BLOCKED, settings.getHostBlocked());
        model.addAttribute(HOST_WANTED, settings.getHostWanted());
        model.addAttribute(SONG_LIST_MAX, SongList.MAX_LINES);
        return "host";
    }

    @PostMapping("/{token}")
    public String saveHostLists(@PathVariable String token, @RequestParam(required = false) String blocked,
                                @RequestParam(required = false) String wanted, RedirectAttributes redirectAttributes) {
        PartySettingsEntity settings = partyOf(token);
        String blockedList = SongList.tidy(blocked);
        String wantedList = SongList.tidy(wanted);
        partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> {
            // asked again under the row's lock: the DJ may have made a new link since partyOf read it — the old one writes nothing
            // (written, it put its own token back over the new one; review 2026-10-14 W8)
            if (!token.equals(s.getHostToken())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            s.setHostBlocked(blockedList);
            s.setHostWanted(wantedList);
        });
        log.info("Party [{}]: the hosts saved their lists", settings.getPartyCode());
        redirectAttributes.addFlashAttribute(HOST_SAVED, true);
        if (SongList.entries(blocked) > SongList.MAX_LINES || SongList.entries(wanted) > SongList.MAX_LINES) {
            redirectAttributes.addFlashAttribute(HOST_DROPPED, SongList.MAX_LINES);   // the first ones kept: the page says so
        }
        return "redirect:/h/" + token;
    }

    // ---------------------------------------------------------------- "Plan imprezy" (V77): an event's hosts' link

    /**
     * The plan of one wishes' event, for its hosts ({@code /h/e/{token}}, the DJ made the link on the event's plan page): its moments
     * and its two lists. Works while the event is kept and the party has Pro; a link made new, taken away or unknown is a 404.
     */
    @GetMapping("/e/{token}")
    public String eventPlan(@PathVariable String token, Model model, Locale locale) {
        EventPlanService.HostEvent hostEvent = hostEventOf(token);
        PartySettingsEntity settings = partySettingsRepository.findByPartyCode(hostEvent.partyCode()).orElseThrow(HostController::notFound);
        WishService.Event event = wishService.event(hostEvent.partyCode(), hostEvent.id()).orElseThrow(HostController::notFound);
        model.addAttribute(HOST_TOKEN, token);
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(WISH_EVENT, event);
        model.addAttribute(WISH_DATE, WishController.dateText(event.date(), locale));
        EventPlanForm.addPlan(model, eventPlanService.plan(event.id()));
        model.addAttribute(PLAN_EDITABLE, true);
        model.addAttribute(PLAN_ACTION, "/h/e/" + token);
        model.addAttribute(SONG_LIST_MAX, SongList.MAX_LINES);
        return "host-plan";
    }

    /**
     * The hosts' save: the whole plan. No CSRF token is asked (SecurityConfig): the link's secret is what lets it write, no session
     * or cookie does — and a plan is written slowly; a form kept open past the session must not lose it.
     */
    @PostMapping("/e/{token}")
    public String saveEventPlan(@PathVariable String token, @RequestParam(required = false) String blocked,
                                @RequestParam(required = false) String wanted, HttpServletRequest request, Locale locale,
                                RedirectAttributes redirectAttributes) {
        hostEventOf(token);
        EventPlanService.Moments moments = EventPlanForm.moments(request, messageSource, locale);
        if (!eventPlanService.saveByHosts(token, moments.kept(), blocked, wanted)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        redirectAttributes.addFlashAttribute(PLAN_DONE, moments.dropped() > 0 || DjEventPlanController.overLimit(blocked, wanted)
                ? "saved_cut" : "saved");
        redirectAttributes.addFlashAttribute(PLAN_DONE_ARG, new String[] {String.valueOf(EventPlanService.MAX_MOMENTS),
                String.valueOf(SongList.MAX_LINES)});
        return "redirect:/h/e/" + token;
    }

    /** "Zacznij od szablonu" on the hosts' page: only while the plan has no moments. */
    @PostMapping("/e/{token}/template")
    public String eventPlanTemplate(@PathVariable String token, @RequestParam(required = false) String template, Locale locale,
                                    RedirectAttributes redirectAttributes) {
        EventPlanService.HostEvent hostEvent = hostEventOf(token);
        List<String> labels = EventPlanForm.template(template, messageSource, locale);
        redirectAttributes.addFlashAttribute(PLAN_DONE, !labels.isEmpty()
                && eventPlanService.startFrom(hostEvent.id(), labels, EventPlanService.BY_HOSTS) ? "template" : "template_not");
        redirectAttributes.addFlashAttribute(PLAN_DONE_ARG, new String[0]);
        return "redirect:/h/e/" + token;
    }

    /** The event of a hosts' link — while its party has Pro (the wishes' events are Pro); else, or unknown, a 404. */
    private EventPlanService.HostEvent hostEventOf(String token) {
        return eventPlanService.byHostToken(token)
                .filter(event -> partySettingsRepository.findByPartyCode(event.partyCode())
                        .filter(party -> planService.allows(party, PlanFeature.WISHES)).isPresent())
                .orElseThrow(HostController::notFound);
    }

    /** A 404 that tells nothing: an unknown link, an old one, a party without Pro. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private PartySettingsEntity partyOf(String token) {
        if (token == null || token.length() > 32) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // without the Pro plan (V35) the link is off, as an old one: the lists stay saved for when Pro is back; so is a link made
        // more than 30 days ago (V37)
        return partySettingsRepository.findByHostToken(token).filter(party -> party.hostLinkWorks(Instant.now()))
                .filter(party -> planService.allows(party, PlanFeature.HOST_LISTS))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
