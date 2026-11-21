package com.scan2play.controller;

import com.scan2play.service.EventPlanService;
import com.scan2play.util.SongList;
import com.scan2play.util.Times;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * What the DJ's plan page and the hosts' page share: the form's rows as the page draws them, and the moments as the form sends them.
 * The columns are read with {@link HttpServletRequest#getParameterValues}: a {@code List<String>} parameter of one value is split at
 * its commas by Spring ("Wejście pary, potem toast" would be two moments).
 */
final class EventPlanForm {

    /** Empty rows after the plan's moments: room to add some without the script (plan-form.js adds more). */
    static final int SPARE_ROWS = 3;

    private EventPlanForm() {
    }

    /**
     * The plan and the form's rows: its moments, then up to {@value #SPARE_ROWS} empty ones (never past the most a plan keeps). After a
     * refused save ({@code planDraft}, a flash attribute) the rows and the lists are what that form had, so nothing typed is lost — and
     * the version is the plan's as it is now, so saving again replaces it on purpose.
     */
    static void addPlan(Model model, EventPlanService.Plan plan) {
        EventPlanService.Draft draft = model.getAttribute(PLAN_DRAFT) instanceof EventPlanService.Draft d ? d : null;
        List<EventPlanService.Moment> rows = new ArrayList<>(draft == null ? plan.moments() : draft.moments());
        int spare = Math.min(SPARE_ROWS, EventPlanService.MAX_MOMENTS - rows.size());
        for (int i = 0; i < spare; i++) {
            rows.add(new EventPlanService.Moment(null, null, null, null));
        }
        model.addAttribute(EVENT_PLAN, plan);
        model.addAttribute(PLAN_ROWS, rows);
        model.addAttribute(PLAN_BLOCKED_TEXT, draft == null ? plan.blocked() : draft.blocked());
        model.addAttribute(PLAN_WANTED_TEXT, draft == null ? plan.wanted() : draft.wanted());
        model.addAttribute(PLAN_VERSION, plan.version());
        model.addAttribute(PLAN_TEMPLATES, plan.moments().isEmpty() && draft == null ? EventPlanService.TEMPLATES : List.of());
        model.addAttribute(PLAN_MAX, EventPlanService.MAX_MOMENTS);
    }

    /** The moments the form sent, as {@link EventPlanService#moments} keeps them. */
    static EventPlanService.Moments moments(HttpServletRequest request, MessageSource messages, Locale locale) {
        return EventPlanService.moments(request.getParameterValues("label"), request.getParameterValues("time"),
                request.getParameterValues("song"), request.getParameterValues("note"),
                messages.getMessage("eventplan.moment.unnamed", null, locale));
    }

    /**
     * What the page says after a save ({@code eventplan.done.*}): saved (cut, when a limit left something out); refused because someone
     * saved the plan meanwhile — who and when — or because the link saved too often, the form's content kept as a draft. A link or an
     * event gone is a 404.
     */
    static void saved(RedirectAttributes redirectAttributes, EventPlanService.Saved saved, EventPlanService.Moments moments,
                      String blocked, String wanted, EventPlanService.Plan now, MessageSource messages, Locale locale) {
        String max = String.valueOf(EventPlanService.MAX_MOMENTS);
        String lines = String.valueOf(SongList.MAX_LINES);
        switch (saved) {
            case SAVED -> done(redirectAttributes, moments.dropped() > 0 || overLimit(blocked, wanted) ? "saved_cut" : "saved", max, lines);
            case CHANGED -> {
                String by = messages.getMessage("eventplan.by." + (now.savedBy() == null ? EventPlanService.BY_DJ : now.savedBy()), null, locale);
                done(redirectAttributes, "changed", by, now.savedAt() == null ? "" : Times.display(now.savedAt()));
                redirectAttributes.addFlashAttribute(PLAN_DRAFT, new EventPlanService.Draft(new ArrayList<>(moments.kept()), blocked, wanted));
            }
            case TOO_MANY -> {
                done(redirectAttributes, "too_many");
                redirectAttributes.addFlashAttribute(PLAN_DRAFT, new EventPlanService.Draft(new ArrayList<>(moments.kept()), blocked, wanted));
            }
            case GONE -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** Whether a list was longer than the party's lists keep: the first ones were saved. */
    static boolean overLimit(String blocked, String wanted) {
        return SongList.entries(blocked) > SongList.MAX_LINES || SongList.entries(wanted) > SongList.MAX_LINES;
    }

    /** What the page says after a form: {@code eventplan.done.<key>}, with its arguments. */
    static void done(RedirectAttributes redirectAttributes, String key, String... args) {
        redirectAttributes.addFlashAttribute(PLAN_DONE, key);
        redirectAttributes.addFlashAttribute(PLAN_DONE_ARG, args);
    }

    /** A template's moments' names in the page's language ({@code plan.template.<key>}, "|" between); none for an unknown key. */
    static List<String> template(String key, MessageSource messages, Locale locale) {
        if (key == null || !EventPlanService.TEMPLATES.contains(key)) {
            return List.of();
        }
        return Arrays.stream(messages.getMessage("eventplan.template." + key, null, locale).split("\\|")).map(String::strip)
                .filter(label -> !label.isEmpty()).toList();
    }
}
