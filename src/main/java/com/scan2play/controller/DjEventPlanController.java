package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.EventPlanService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.WishService;
import com.scan2play.util.SongList;
import com.scan2play.util.Texts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * "Plan imprezy" on the DJ's side (V77, Pro; whoever has the permission WISHES): the plan of one wishes' event — its moments and its
 * two lists, written here or by the hosts on the event's link ({@code /h/e/{token}}, {@link HostController}), the link made and taken
 * away here, a print for the console, and "Użyj na imprezie": the event's lists over the party's (the permission HOST_LISTS too).
 * Plain forms, a page load each, the page back where it was (scroll-restore.js), what was done said at the bottom of the screen.
 * <p>
 * Without Pro the page shows the plan as it was and changes nothing; deleting the event (its page) takes the plan with it.
 */
@Controller
@RequestMapping("/dj/wishes/{id}/plan")
@RequiredArgsConstructor
@Slf4j
public class DjEventPlanController {

    private final WishService wishService;
    private final EventPlanService eventPlanService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final MessageSource messageSource;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    @GetMapping
    public String page(@PathVariable long id, @RequestParam(required = false) String party, Model model,
                       OAuth2AuthenticationToken authentication, HttpSession session, Locale locale) {
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = sessionHelper.require(access.party().getPartyCode(), StaffPermission.WISHES, authentication, session);
        String partyCode = settings.getPartyCode();
        WishService.Event event = eventOf(partyCode, id);
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());   // the heading of every DJ's page (dj-nav)
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(PLAN, planService.view(settings, access.owner()));
        model.addAttribute(WISH_EVENT, event);
        model.addAttribute(WISH_DATE, WishController.dateText(event.date(), locale));
        EventPlanForm.addPlan(model, eventPlanService.plan(id));
        boolean pro = planService.allows(settings, PlanFeature.WISHES);
        model.addAttribute(PLAN_EDITABLE, pro);
        model.addAttribute(PLAN_ACTION, "/dj/wishes/" + id + "/plan");
        model.addAttribute(PLAN_USE_LISTS, pro && access.may(StaffPermission.HOST_LISTS));
        model.addAttribute(PLAN_HOST_LINK, eventPlanService.hostToken(partyCode, id).map(this::hostLinkOf).orElse(null));
        model.addAttribute(SONG_LIST_MAX, SongList.MAX_LINES);
        return "event-plan";
    }

    /** "Zapisz plan": the whole plan, the moments in the page's order. */
    @PostMapping
    public String save(@PathVariable long id, @RequestParam String partyCode, @RequestParam(required = false) String blocked,
                       @RequestParam(required = false) String wanted, HttpServletRequest request, Locale locale,
                       OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        eventOf(partyCode, id);
        EventPlanService.Moments moments = EventPlanForm.moments(request, messageSource, locale);
        EventPlanService.Saved saved = eventPlanService.save(id, moments.kept(), blocked, wanted, EventPlanService.BY_DJ,
                request.getParameter("version"));
        EventPlanForm.saved(redirectAttributes, saved, moments, blocked, wanted,
                saved == EventPlanService.Saved.CHANGED ? eventPlanService.plan(id) : null, messageSource, locale);
        return back(partyCode, id);
    }

    /** "Zacznij od szablonu": the template's moments, only while the plan has none. */
    @PostMapping("/template")
    public String template(@PathVariable long id, @RequestParam String partyCode, @RequestParam(required = false) String template,
                           Locale locale, OAuth2AuthenticationToken authentication, HttpSession session,
                           RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        eventOf(partyCode, id);
        List<String> labels = EventPlanForm.template(template, messageSource, locale);
        done(redirectAttributes, !labels.isEmpty() && eventPlanService.startFrom(id, labels, EventPlanService.BY_DJ)
                ? "template" : "template_not");
        return back(partyCode, id);
    }

    /**
     * "Wyczyść plan": the moments and both lists gone (asks first, data-confirm) — a plan started from the wrong template, an evening
     * called off, the hosts' notes not kept till the event goes. The hosts' link stays; Pro, as any change of the plan.
     */
    @PostMapping("/clear")
    public String clear(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                        HttpSession session, RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        eventOf(partyCode, id);
        found(eventPlanService.clear(id));
        log.info("Party [{}]: the plan of wishes event {} cleared", partyCode, id);
        done(redirectAttributes, "cleared");
        return back(partyCode, id);
    }

    /** "Utwórz link dla gospodarzy" / "Nowy link" (the old one opens nothing then). */
    @PostMapping("/host-link")
    public String hostLink(@PathVariable long id, @RequestParam String partyCode, @RequestParam(defaultValue = "true") boolean on,
                           OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        PartySettingsEntity party = sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session);
        if (on) {
            planService.require(party, PlanFeature.WISHES);
            found(eventPlanService.newHostLink(partyCode, id));
            log.info("Party [{}]: hosts' link of wishes event {} made", partyCode, id);
            done(redirectAttributes, "link");
        } else {
            found(eventPlanService.removeHostLink(partyCode, id));
            log.info("Party [{}]: hosts' link of wishes event {} taken away", partyCode, id);
            done(redirectAttributes, "link_off");
        }
        return back(partyCode, id);
    }

    /**
     * "Użyj na imprezie": the event's "don't play" and "must play" replace the party's — the lists the AI and the queue use tonight.
     * The permission HOST_LISTS too: it is the party's lists that change. From the panel's card ({@code back=panel}) back to the panel.
     */
    @PostMapping("/use-lists")
    public String useLists(@PathVariable long id, @RequestParam String partyCode, @RequestParam(required = false) String back,
                           OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        PartySettingsEntity party = sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session);
        sessionHelper.require(partyCode, StaffPermission.HOST_LISTS, authentication, session);
        planService.require(party, PlanFeature.WISHES);
        planService.require(party, PlanFeature.HOST_LISTS);
        eventOf(partyCode, id);
        EventPlanService.Plan plan = eventPlanService.plan(id);
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setHostBlocked(SongList.tidy(plan.blocked()));
            s.setHostWanted(SongList.tidy(plan.wanted()));
        });
        log.info("Party [{}]: the lists of wishes event {} put over the party's", partyCode, id);
        if ("panel".equals(back)) {
            return "redirect:/dj/dashboard?party=" + partyCode;
        }
        done(redirectAttributes, "lists_used");
        return back(partyCode, id);
    }

    /** The plan on paper, for the console: a page of its own, printed by the browser. */
    @GetMapping("/print")
    public String print(@PathVariable long id, @RequestParam String party, Model model, OAuth2AuthenticationToken authentication,
                        HttpSession session, Locale locale) {
        PartySettingsEntity settings = sessionHelper.require(party, StaffPermission.WISHES, authentication, session);
        WishService.Event event = eventOf(settings.getPartyCode(), id);
        model.addAttribute(WISH_EVENT, event);
        model.addAttribute(WISH_DATE, WishController.dateText(event.date(), locale));
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(EVENT_PLAN, eventPlanService.plan(id));
        return "event-plan-print";
    }

    private WishService.Event eventOf(String partyCode, long id) {
        return wishService.event(partyCode, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String hostLinkOf(String token) {
        return Texts.withoutTrailingSlash(rawBaseUrl) + "/h/e/" + token;
    }

    private static void found(boolean changed) {
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void done(RedirectAttributes redirectAttributes, String key, String... args) {
        EventPlanForm.done(redirectAttributes, key, args);
    }

    private static String back(String partyCode, long id) {
        return "redirect:/dj/wishes/" + id + "/plan?party=" + partyCode;
    }
}
