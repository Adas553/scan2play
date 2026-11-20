package com.scan2play.controller;

import com.scan2play.service.EventPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;

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

    /** The plan and the form's rows: its moments, then up to {@value #SPARE_ROWS} empty ones (never past the most a plan keeps). */
    static void addPlan(Model model, EventPlanService.Plan plan) {
        List<EventPlanService.Moment> rows = new ArrayList<>(plan.moments());
        int spare = Math.min(SPARE_ROWS, EventPlanService.MAX_MOMENTS - rows.size());
        for (int i = 0; i < spare; i++) {
            rows.add(new EventPlanService.Moment(null, null, null, null));
        }
        model.addAttribute(EVENT_PLAN, plan);
        model.addAttribute(PLAN_ROWS, rows);
        model.addAttribute(PLAN_TEMPLATES, plan.moments().isEmpty() ? EventPlanService.TEMPLATES : List.of());
        model.addAttribute(PLAN_MAX, EventPlanService.MAX_MOMENTS);
    }

    /** The moments the form sent, as {@link EventPlanService#moments} keeps them. */
    static EventPlanService.Moments moments(HttpServletRequest request, MessageSource messages, Locale locale) {
        return EventPlanService.moments(request.getParameterValues("label"), request.getParameterValues("time"),
                request.getParameterValues("song"), request.getParameterValues("note"),
                messages.getMessage("eventplan.moment.unnamed", null, locale));
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
