package com.scan2play.controller;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.scan2play.controller.ViewAttributes.EVENINGS;
import static com.scan2play.controller.ViewAttributes.HOSTS_VIEW;
import static com.scan2play.controller.ViewAttributes.LIBRARY_GAPS;
import static com.scan2play.controller.ViewAttributes.SHARED;

/**
 * The hosts' read-only link of an evening summary (V61, {@code /s/{token}}): the couple, the host or the club open the hosts'
 * version of the evening without an account — the DJ makes the link in the summary ({@link DjSummaryController}) and it works
 * {@value EveningSummaryService#SHARE_DAYS} days. An unknown, old or switched-off secret, or a party whose plan no longer has the
 * summary, is a 404: nothing tells it from a page that never was. Never indexed ({@code NoIndexFilter}), never sent on as a
 * referrer, never cached.
 */
@Controller
@RequiredArgsConstructor
public class SharedSummaryController {

    private final EveningSummaryService eveningSummaryService;
    private final PartySettingsRepository partySettingsRepository;
    private final PlanService planService;

    @GetMapping("/s/{token}")
    public String shared(@PathVariable String token, Model model, HttpServletResponse response) {
        EveningSummaryEntity evening = eveningSummaryService.shared(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        PartySettingsEntity settings = partySettingsRepository.findByPartyCode(evening.getPartyCode())
                .filter(party -> planService.allows(party, PlanFeature.SUMMARY))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute(SHARED, true);
        model.addAttribute(HOSTS_VIEW, true);
        model.addAttribute(EVENINGS, List.of());
        model.addAttribute(LIBRARY_GAPS, null);
        DjSummaryController.fill(model, settings, evening.getEvening(), Optional.of(evening), eveningSummaryService, planService);
        return "summary";
    }
}
