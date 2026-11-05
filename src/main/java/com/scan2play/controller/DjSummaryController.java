package com.scan2play.controller;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.service.PlanService;
import com.scan2play.util.Texts;
import com.scan2play.util.Times;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.scan2play.controller.ViewAttributes.COMPARED_EVENINGS;
import static com.scan2play.controller.ViewAttributes.DJ_LOGO;
import static com.scan2play.controller.ViewAttributes.DJ_NAME;
import static com.scan2play.controller.ViewAttributes.EVENING;
import static com.scan2play.controller.ViewAttributes.EVENINGS;
import static com.scan2play.controller.ViewAttributes.EVENING_MONTHS;
import static com.scan2play.controller.ViewAttributes.EVENING_TITLE;
import static com.scan2play.controller.ViewAttributes.HOSTS_VIEW;
import static com.scan2play.controller.ViewAttributes.LIBRARY_COPY;
import static com.scan2play.controller.ViewAttributes.LIBRARY_GAPS;
import static com.scan2play.controller.ViewAttributes.PARTY_CODE;
import static com.scan2play.controller.ViewAttributes.REFUSALS;
import static com.scan2play.controller.ViewAttributes.SHARED;
import static com.scan2play.controller.ViewAttributes.SHARE_LINK;
import static com.scan2play.controller.ViewAttributes.SHARE_UNTIL;
import static com.scan2play.controller.ViewAttributes.SUMMARY;

/**
 * "📊 Podsumowanie wieczoru" ({@link EveningSummaryService}): a page of one evening of the DJ's own party to print or save as PDF —
 * the DJ's version, or with {@code view=hosts} the one to give the couple or the client (the evening's songs without what is the
 * DJ's own: the AI's rejections, the skipped and cleared, the library, the tips) —, and the same evening's requests as CSV. In the
 * hosts' version the DJ names the evening and makes its hosts' read-only link ({@code /s/{token}}, {@link SharedSummaryController}).
 * Only the logged-in DJ's party — there is no party code to ask for.
 */
@Controller
@RequestMapping("/dj/summary")
@Slf4j
public class DjSummaryController {

    /** The value of {@code view} that shows the hosts' version; anything else is the DJ's. */
    static final String VIEW_HOSTS = "hosts";

    private final EveningSummaryService eveningSummaryService;
    private final DjSessionHelper sessionHelper;
    private final MessageSource messageSource;
    private final PlanService planService;
    private final String baseUrl;

    public DjSummaryController(EveningSummaryService eveningSummaryService, DjSessionHelper sessionHelper, MessageSource messageSource,
                               PlanService planService, @Value("${scan2play.guest-url}") String guestUrl) {
        this.eveningSummaryService = eveningSummaryService;
        this.sessionHelper = sessionHelper;
        this.messageSource = messageSource;
        this.planService = planService;
        this.baseUrl = Texts.withoutTrailingSlash(guestUrl);
    }

    /**
     * The evening {@code evening} ("2026-10-03"); none, or one that is not a date, is the latest evening with requests. {@code view}
     * "hosts": the hosts' version, with the evening's title and its hosts' link to set.
     */
    @GetMapping
    public String summary(@RequestParam(required = false) String evening, @RequestParam(required = false) String party,
                          @RequestParam(required = false) String view,
                          Model model, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = require(party, authentication, session);
        List<EveningSummaryService.Evening> evenings = eveningSummaryService.evenings(settings.getPartyCode());
        LocalDate shown = pick(evening, evenings);
        boolean hosts = VIEW_HOSTS.equals(view);
        model.addAttribute(EVENINGS, evenings);
        model.addAttribute(EVENING_MONTHS, EveningSummaryService.byMonth(evenings));
        model.addAttribute(COMPARED_EVENINGS, shown == null ? List.of() : EveningSummaryService.compared(evenings, shown));
        model.addAttribute(HOSTS_VIEW, hosts);
        model.addAttribute(SHARED, false);
        Optional<EveningSummaryEntity> named = shown == null ? Optional.empty()
                : eveningSummaryService.evening(settings.getPartyCode(), shown);
        named.filter(e -> e.shareWorks(Instant.now())).ifPresent(e -> {
            model.addAttribute(SHARE_LINK, baseUrl + "/s/" + e.getShareToken());
            model.addAttribute(SHARE_UNTIL, Times.day(e.getShareExpiresAt()));
        });
        List<SongRequestEntity> requests = fill(model, settings, shown, named, eveningSummaryService, planService);
        // "Nie masz w bibliotece" (V42): the DJ's version, with the plan that marks by the library, and a library
        List<EveningSummaryService.LibraryGap> gaps = shown != null && !hosts && planService.allows(settings, PlanFeature.LIBRARY)
                ? eveningSummaryService.libraryGaps(settings.getPartyCode(), requests).orElse(null) : null;
        model.addAttribute(LIBRARY_GAPS, gaps);
        model.addAttribute(LIBRARY_COPY, gaps == null ? null : EveningSummaryService.copyList(gaps));
        // the requests refused before the AI (V63): the DJ's version
        model.addAttribute(REFUSALS, shown == null || hosts ? Map.of() : eveningSummaryService.refusals(settings.getPartyCode(), shown));
        return "summary";
    }

    /**
     * What the summary page shows of an evening, the DJ's page and the hosts' link alike: the party, the evening, its title and its
     * summary. The evening's requests, for what the caller adds.
     */
    static List<SongRequestEntity> fill(Model model, PartySettingsEntity settings, LocalDate shown,
                                        Optional<EveningSummaryEntity> named, EveningSummaryService service, PlanService plans) {
        model.addAttribute(PARTY_CODE, settings.getPartyCode());
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(DJ_LOGO, plans.asPlanned(settings).logoPath());
        model.addAttribute(EVENING, shown);
        model.addAttribute(EVENING_TITLE, named.map(EveningSummaryEntity::getTitle).orElse(null));
        List<SongRequestEntity> requests = shown == null ? List.of() : service.requests(settings.getPartyCode(), shown);
        model.addAttribute(SUMMARY, shown == null ? null : EveningSummaryService.summarize(shown, requests));
        return requests;
    }

    /** The evening's title (V61), typed in the hosts' version; empty clears it. Back to the hosts' version. */
    @PostMapping("/title")
    public String saveTitle(@RequestParam String evening, @RequestParam(required = false) String party,
                            @RequestParam(required = false) String title, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = require(party, authentication, session);
        LocalDate day = existing(evening, settings);
        eveningSummaryService.saveTitle(settings.getPartyCode(), day, title);
        log.info("Party [{}]: the evening of {} named", settings.getPartyCode(), day);
        return backToTheHosts(day, party);
    }

    /** A new hosts' link of the evening (V61): {@value EveningSummaryService#SHARE_DAYS} days; an old one stops working. */
    @PostMapping("/share")
    public String share(@RequestParam String evening, @RequestParam(required = false) String party,
                        OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = require(party, authentication, session);
        LocalDate day = existing(evening, settings);
        eveningSummaryService.share(settings.getPartyCode(), day);
        log.info("Party [{}]: a hosts' link of the evening of {} made", settings.getPartyCode(), day);
        return backToTheHosts(day, party);
    }

    /** The evening's hosts' link switched off. */
    @PostMapping("/share/off")
    public String stopSharing(@RequestParam String evening, @RequestParam(required = false) String party,
                              OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = require(party, authentication, session);
        LocalDate day = existing(evening, settings);
        eveningSummaryService.stopSharing(settings.getPartyCode(), day);
        log.info("Party [{}]: the hosts' link of the evening of {} switched off", settings.getPartyCode(), day);
        return backToTheHosts(day, party);
    }

    /** The evening's requests as a CSV file, "scan2play-2026-10-03.csv"; 404 when the party has no evening at all. */
    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String evening, @RequestParam(required = false) String party,
                                      Locale locale, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = require(party, authentication, session);
        LocalDate shown = pick(evening, eveningSummaryService.evenings(settings.getPartyCode()));
        if (shown == null) {
            return ResponseEntity.notFound().build();
        }
        List<SongRequestEntity> requests = eveningSummaryService.requests(settings.getPartyCode(), shown);
        Map<Long, LibraryMark> marks = planService.allows(settings, PlanFeature.LIBRARY)
                ? eveningSummaryService.libraryMarks(settings.getPartyCode(), requests) : Map.of();
        String csv = EveningSummaryService.toCsv(requests, marks, messageSource, locale);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("scan2play-" + shown + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private PartySettingsEntity require(String party, OAuth2AuthenticationToken authentication, HttpSession session) {
        return planService.require(sessionHelper.require(party, StaffPermission.SUMMARY, authentication, session), PlanFeature.SUMMARY);
    }

    /**
     * One of the party's evenings, by its day; 404 for an evening it has not (a title or a link only for an evening there is). Asked
     * of that evening alone: the list of evenings reads the party's 13 months of rows.
     */
    private LocalDate existing(String evening, PartySettingsEntity settings) {
        LocalDate day = parse(evening);
        if (day == null || !eveningSummaryService.hasRequests(settings.getPartyCode(), day)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return day;
    }

    private static String backToTheHosts(LocalDate evening, String party) {
        return "redirect:" + UriComponentsBuilder.fromPath("/dj/summary").queryParam("evening", evening)
                .queryParamIfPresent("party", Optional.ofNullable(party).filter(p -> !p.isBlank()))
                .queryParam("view", VIEW_HOSTS).encode().build().toUriString();
    }

    /** The asked-for evening when it is a date, else the latest one with requests; null when there is none. */
    static LocalDate pick(String evening, List<EveningSummaryService.Evening> evenings) {
        LocalDate day = parse(evening);
        if (day != null) {
            return day;
        }
        return evenings.isEmpty() ? null : evenings.getFirst().day();
    }

    private static LocalDate parse(String evening) {
        if (evening != null && !evening.isBlank()) {
            try {
                return LocalDate.parse(evening.strip());
            } catch (DateTimeParseException ignored) {
                // an address typed by hand: the latest evening instead
            }
        }
        return null;
    }
}
