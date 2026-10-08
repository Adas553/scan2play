package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.EveningSummaryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.DJ_NAME;
import static com.scan2play.controller.ViewAttributes.EVENING;
import static com.scan2play.controller.ViewAttributes.EVENINGS;
import static com.scan2play.controller.ViewAttributes.PARTY_CODE;
import static com.scan2play.controller.ViewAttributes.SUMMARY;

/**
 * "📊 Podsumowanie wieczoru" ({@link EveningSummaryService}): a page of one evening of the DJ's own party to print or save as PDF,
 * and the same evening's requests as CSV. Only the logged-in DJ's party — there is no party code to ask for.
 */
@Controller
@RequestMapping("/dj/summary")
@RequiredArgsConstructor
public class DjSummaryController {

    private final EveningSummaryService eveningSummaryService;
    private final DjSessionHelper sessionHelper;
    private final MessageSource messageSource;

    /** The evening {@code evening} ("2026-10-03"); none, or one that is not a date, is the latest evening with requests. */
    @GetMapping
    public String summary(@RequestParam(required = false) String evening, Model model,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        List<EveningSummaryService.Evening> evenings = eveningSummaryService.evenings(settings.getPartyCode());
        LocalDate shown = pick(evening, evenings);
        model.addAttribute(PARTY_CODE, settings.getPartyCode());
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(EVENINGS, evenings);
        model.addAttribute(EVENING, shown);
        model.addAttribute(SUMMARY, shown == null ? null : eveningSummaryService.summarize(settings.getPartyCode(), shown));
        return "summary";
    }

    /** The evening's requests as a CSV file, "scan2play-2026-10-03.csv"; 404 when the party has no evening at all. */
    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String evening, Locale locale,
                                      OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        LocalDate shown = pick(evening, eveningSummaryService.evenings(settings.getPartyCode()));
        if (shown == null) {
            return ResponseEntity.notFound().build();
        }
        String csv = EveningSummaryService.toCsv(eveningSummaryService.requests(settings.getPartyCode(), shown), messageSource,
                locale);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("scan2play-" + shown + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** The asked-for evening when it is a date, else the latest one with requests; null when there is none. */
    static LocalDate pick(String evening, List<EveningSummaryService.Evening> evenings) {
        if (evening != null && !evening.isBlank()) {
            try {
                return LocalDate.parse(evening.strip());
            } catch (DateTimeParseException ignored) {
                // an address typed by hand: the latest evening instead
            }
        }
        return evenings.isEmpty() ? null : evenings.getFirst().day();
    }
}
