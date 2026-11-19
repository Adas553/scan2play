package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.PlanService;
import com.scan2play.service.PollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.Optional;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * What a guest's page and the TV show of "Pytanie do gości" (V74): the question asked now — or the one ended a moment ago, with its
 * results —, its results once the guest answered (none while the DJ keeps them hidden, V75), and the guest's answer. Nothing without
 * the plan's {@link PlanFeature#POLLS}.
 */
@Component
@RequiredArgsConstructor
class PollView {

    private final PollService pollService;
    private final PlanService planService;

    /**
     * For a guest's page ({@code request}: their session, if they have one). A question they may answer needs a form, whose CSRF token
     * lives in the session: the session is made here, before the page is drawn — made while the response is written, it came too late
     * once (the landing page, 2026-10-18).
     */
    void addForGuest(Model model, PartySettingsEntity settings, HttpServletRequest request) {
        if (!add(model, settings, false)) {
            return;
        }
        PollService.Poll poll = (PollService.Poll) model.getAttribute(POLL);
        if (poll == null) {
            return;
        }
        HttpSession session = poll.asked() ? request.getSession() : request.getSession(false);
        Optional<Integer> mine = session == null ? Optional.empty() : pollService.choiceOf(poll.id(), PollService.voterOf(session));
        model.addAttribute(POLL_MINE, mine.orElse(null));
    }

    /** For the TV: the question and its results, no guest — none when the DJ keeps the most wanted songs on the screen (V76). */
    void addForScreen(Model model, PartySettingsEntity settings) {
        add(model, settings, true);
    }

    private boolean add(Model model, PartySettingsEntity settings, boolean screen) {
        boolean on = planService.allows(settings, PlanFeature.POLLS);
        model.addAttribute(POLL_ON, on);
        if (!on) {
            return false;
        }
        pollService.shown(settings.getPartyCode()).filter(poll -> !screen || poll.onTv()).ifPresent(poll -> {
            model.addAttribute(POLL, poll);
            // results the DJ keeps hidden (V75) are not in the page at all — not hidden by the look: its source would tell them
            if (!poll.resultsHidden()) {
                model.addAttribute(POLL_RESULTS, pollService.results(poll));
            }
        });
        return true;
    }
}
