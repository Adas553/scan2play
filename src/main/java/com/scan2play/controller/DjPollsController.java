package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.PollService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Stream;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The DJ's page of "Pytanie do gości" (V74, Pro; whoever has the permission POLLS): a new question with two to four answers — asked
 * at once or saved for later —, the one asked now with its results live (polls.js fetches them every few seconds), and the party's
 * other questions to ask (again), end or delete. A full page load after each form, the page back where it was (scroll-restore.js),
 * what was done said at the bottom of the screen.
 * <p>
 * Without Pro the page offers it: nothing is made or asked; a question asked before may still be ended or deleted.
 */
@Controller
@RequestMapping("/dj/polls")
@RequiredArgsConstructor
@Slf4j
public class DjPollsController {

    private final PollService pollService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final MessageSource messageSource;

    /** The page: the form, and the party's questions with their results, the newest first. */
    @GetMapping
    public String page(@RequestParam(required = false) String party, Model model, OAuth2AuthenticationToken authentication,
                       HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = sessionHelper.require(access.party().getPartyCode(), StaffPermission.POLLS, authentication, session);
        String partyCode = settings.getPartyCode();
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());   // the heading of every DJ's page (dj-nav)
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(PLAN, planService.view(settings, access.owner()));
        List<PollService.Results> polls = pollService.resultsOf(pollService.pollsOf(partyCode));
        model.addAttribute(POLLS, polls);
        model.addAttribute(POLL_COUNT, polls.size());   // pollsOf lists every question a party keeps
        model.addAttribute(POLL_MAX, PollService.MAX_POLLS);
        model.addAttribute(POLL_QUESTION_MAX, PollService.QUESTION_MAX);
        model.addAttribute(POLL_ANSWER_MAX, PollService.ANSWER_MAX);
        model.addAttribute(POLL_MAX_ANSWERS, PollService.MAX_ANSWERS);
        model.addAttribute(POLL_ASKED_HOURS, PollService.ASKED_FOR.toHours());
        return "polls";
    }

    /**
     * The results of the question asked, alone (the fragment {@code polls :: pollResults}) — fetched again by the page while it is
     * asked. 404 for another party's question.
     */
    @GetMapping("/{id}/results")
    public String results(@PathVariable long id, @RequestParam String party, Model model, OAuth2AuthenticationToken authentication,
                          HttpSession session) {
        PartySettingsEntity settings = sessionHelper.require(party, StaffPermission.POLLS, authentication, session);
        PollService.Poll poll = pollService.poll(settings.getPartyCode(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("p", pollService.results(poll));
        return "polls :: pollResults";
    }

    /**
     * "Zadaj teraz" ({@code ask=true}) or "Zapisz na później": a new question with the answers given — none typed is "Tak" / "Nie" in
     * the DJ's language —, its results shown to the guests after their vote or kept for later ({@code hideResults}, V75). Pro.
     */
    @PostMapping("/create")
    public String create(@RequestParam String partyCode, @RequestParam(required = false) String question,
                         @RequestParam(name = "answer", required = false) List<String> answers,
                         @RequestParam(defaultValue = "false") boolean hideResults,
                         @RequestParam(defaultValue = "false") boolean ask, OAuth2AuthenticationToken authentication,
                         HttpSession session, RedirectAttributes redirectAttributes, Locale locale) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session), PlanFeature.POLLS);
        String kept = PollService.tidyQuestion(question);
        boolean noneTyped = answers == null || answers.stream().allMatch(one -> one == null || one.isBlank());
        List<String> keptAnswers = PollService.tidyAnswers(noneTyped
                ? Stream.of("polls.yes", "polls.no").map(key -> messageSource.getMessage(key, null, locale)).toList()
                : answers);
        if (kept == null || keptAnswers == null) {
            done(redirectAttributes, "invalid");
            return back(partyCode);
        }
        return pollService.create(partyCode, kept, keptAnswers, hideResults, ask).map(made -> {
            done(redirectAttributes, ask ? "asked" : "saved");
            return back(partyCode);
        }).orElseGet(() -> {
            done(redirectAttributes, "too_many");
            return back(partyCode);
        });
    }

    /** "Zadaj teraz" / "Zadaj ponownie": the question asked — the one asked before ends. Pro. */
    @PostMapping("/{id}/ask")
    public String ask(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                      HttpSession session, RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session), PlanFeature.POLLS);
        found(pollService.ask(partyCode, id));
        done(redirectAttributes, "asked");
        return back(partyCode);
    }

    /** "Zakończ pytanie": the guests answer it no more; its results stay a while on their page and the TV. Any plan. */
    @PostMapping("/{id}/end")
    public String end(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                      HttpSession session, RedirectAttributes redirectAttributes) {
        sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session);
        found(pollService.end(partyCode, id));
        done(redirectAttributes, "ended");
        return back(partyCode);
    }

    /**
     * "👀 Pokaż wyniki (goście i TV)" ({@code hidden=false}) or "🙈 Ukryj wyniki" (V75): whether the guests' page and the TV show the
     * numbers — the DJ keeps them for a game's end, and shows them when they say so. Any plan, as ending: it asks nothing new.
     */
    @PostMapping("/{id}/results")
    public String results(@PathVariable long id, @RequestParam String partyCode, @RequestParam boolean hidden,
                          OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session);
        found(pollService.setResultsHidden(partyCode, id, hidden));
        done(redirectAttributes, hidden ? "results_hidden" : "results_shown");
        return back(partyCode);
    }

    /**
     * "Na TV: Pytanie" ({@code onTv=true}) or "Piosenki" (V76): whether the TV screen shows the question or keeps the guests' most
     * wanted songs; the phones are not changed. Any plan, as ending: it asks nothing new.
     */
    @PostMapping("/{id}/tv")
    public String tv(@PathVariable long id, @RequestParam String partyCode, @RequestParam boolean onTv,
                     OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session);
        found(pollService.setOnTv(partyCode, id, onTv));
        done(redirectAttributes, onTv ? "tv_question" : "tv_songs");
        return back(partyCode);
    }

    /** "Usuń": the question and its votes, at once — any plan. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                         HttpSession session, RedirectAttributes redirectAttributes) {
        sessionHelper.require(partyCode, StaffPermission.POLLS, authentication, session);
        found(pollService.delete(partyCode, id));
        log.info("Party [{}]: question {} deleted", partyCode, id);
        done(redirectAttributes, "deleted");
        return back(partyCode);
    }

    private static void found(boolean changed) {
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** What the page says after a form: {@code polls.done.<key>}. */
    private static void done(RedirectAttributes redirectAttributes, String key) {
        redirectAttributes.addFlashAttribute(POLL_DONE, key);
    }

    private static String back(String partyCode) {
        // no anchor: the page loads again where the DJ was (scroll-restore.js), the answer at the bottom of the screen
        return "redirect:/dj/polls?party=" + partyCode;
    }
}
