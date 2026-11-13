package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DjLibraryService;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.WishService;
import com.scan2play.util.LibraryKey;
import com.scan2play.util.SongList;
import com.scan2play.util.Texts;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The DJ's page of "Życzenia przed imprezą" (V68, Pro; whoever has the permission WISHES): the party's events, each with its guests'
 * link to send or print as an invitation ({@code /dj/qr-print?wish=}), and the wishes of the one opened — counted, the most wished
 * first, each marked by the DJ's library when there is one — with "⭐ Do „Koniecznie zagrać”", which adds it to the hosts' wish
 * list (V29): the list the AI lets through and the queue marks. A full page load after each form, the page back where it was.
 * <p>
 * Without Pro the page offers it: no event is made and the guests' links open nothing ({@link WishController}); an event made before
 * may still be deleted.
 */
@Controller
@RequestMapping("/dj/wishes")
@RequiredArgsConstructor
@Slf4j
public class DjWishesController {

    /** "⭐ Dodaj najczęstsze": the most wished songs at once. */
    public static final int TOP = 10;

    private final WishService wishService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final DjService djService;
    private final DjLibraryService djLibraryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final MessageSource messageSource;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    /** One song of the event as the page lists it: the library's mark (null without a library), and whether it is on "⭐" already. */
    public record Row(String songName, int wishes, LibraryMark mark, String bpm, String tonality, boolean wanted) {
    }

    /** The page: the events, and the one {@code event} names opened with its wishes. */
    @GetMapping
    public String page(@RequestParam(required = false) String party, @RequestParam(required = false) Long event, Model model,
                       OAuth2AuthenticationToken authentication, HttpSession session) {
        PartyStaffService.Access access = sessionHelper.access(party, authentication, session);
        PartySettingsEntity settings = sessionHelper.require(access.party().getPartyCode(), StaffPermission.WISHES, authentication, session);
        String partyCode = settings.getPartyCode();
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());   // the heading of every DJ's page (dj-nav)
        model.addAttribute(IS_OWNER, access.owner());
        model.addAttribute(ACCESS, access);
        model.addAttribute(PARTY_NAME, PartyStaffService.nameOf(settings));
        model.addAttribute(PLAN, planService.view(settings, access.owner()));
        List<WishService.Event> events = wishService.eventsOf(partyCode);
        Map<Long, String> links = new LinkedHashMap<>();
        events.forEach(one -> links.put(one.id(), linkOf(one)));
        model.addAttribute(WISH_EVENTS, events);
        model.addAttribute(WISH_LINKS, links);
        model.addAttribute(WISH_OPEN, wishService.openEvents(partyCode));
        model.addAttribute(WISH_MAX_OPEN, WishService.MAX_OPEN_EVENTS);
        model.addAttribute(WISH_NAME_MAX, WishService.NAME_MAX);
        model.addAttribute(WISH_DATE_MIN, WishService.today().toString());
        model.addAttribute(WISH_DATE_MAX, WishService.today().plusDays(WishService.MAX_DAYS_AHEAD).toString());
        model.addAttribute(WISH_LEFT, WishController.PER_GUEST);
        if (event != null) {
            wishService.event(partyCode, event).ifPresent(opened -> addEvent(model, settings, opened));
        }
        return "wishes";
    }

    /** The opened event: its wishes, the named ones and the ones to check apart, each marked by the library. */
    private void addEvent(Model model, PartySettingsEntity settings, WishService.Event event) {
        List<WishService.Wish> wishes = wishService.wishes(event.id());
        Map<LibraryKey, DjLibraryService.Match> matches = libraryMatches(settings, wishes);
        SongList wanted = SongList.of(settings.getHostWanted());
        List<Row> named = new ArrayList<>();
        List<Row> check = new ArrayList<>();
        int missing = 0;
        for (WishService.Wish wish : wishes) {
            DjLibraryService.Match match = wish.named() ? matches.get(wish.libraryKey()) : null;
            Row row = new Row(wish.songName(), wish.wishes(), match == null ? null : match.mark(), match == null ? null : match.bpmText(),
                    match == null ? null : match.tonality(), wanted.matches(wish.songName()));
            (wish.named() ? named : check).add(row);
            if (match != null && match.mark() == LibraryMark.MISSING) {
                missing++;
            }
        }
        model.addAttribute(WISH_EVENT, event);
        model.addAttribute(WISH_DATE, WishController.dateText(event.date(), org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        model.addAttribute(WISH_ROWS, named);
        model.addAttribute(WISH_CHECK, check);
        model.addAttribute(WISH_SONGS, wishService.songs(event.id()));
        model.addAttribute(WISH_MISSING, matches.isEmpty() ? null : missing);
        model.addAttribute(WISH_LINK, linkOf(event));
    }

    /** The wishes against the DJ's library — none when the party has no library, or no Pro for it. */
    private Map<LibraryKey, DjLibraryService.Match> libraryMatches(PartySettingsEntity settings, List<WishService.Wish> wishes) {
        String partyCode = settings.getPartyCode();
        if (wishes.isEmpty() || !planService.allows(settings, PlanFeature.LIBRARY) || djService.libraryOf(partyCode).isEmpty()) {
            return Map.of();
        }
        return djLibraryService.matches(partyCode, wishes.stream().filter(WishService.Wish::named).map(WishService.Wish::libraryKey).toList());
    }

    /** "Utwórz": a new event with its guests' link — its name and day as the guests read them. */
    @PostMapping("/create")
    public String create(@RequestParam String partyCode, @RequestParam(required = false) String name,
                         @RequestParam(required = false) String date, OAuth2AuthenticationToken authentication, HttpSession session,
                         RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        String kept = WishService.tidyName(name);
        LocalDate day = dayOf(date);
        if (kept == null || day == null || day.isBefore(WishService.today()) || day.isAfter(WishService.today().plusDays(WishService.MAX_DAYS_AHEAD))) {
            done(redirectAttributes, "invalid");
            return back(partyCode, null);
        }
        return wishService.create(partyCode, kept, day).map(made -> {
            done(redirectAttributes, "created", made.name());
            return back(partyCode, made.id());
        }).orElseGet(() -> {
            done(redirectAttributes, "too_many", String.valueOf(WishService.MAX_OPEN_EVENTS));
            return back(partyCode, null);
        });
    }

    /** "Zakończ zbieranie" ({@code closed=true}) or "Wznów zbieranie". Reopened only with Pro. */
    @PostMapping("/{id}/closed")
    public String closed(@PathVariable long id, @RequestParam String partyCode, @RequestParam boolean closed,
                         OAuth2AuthenticationToken authentication, HttpSession session, RedirectAttributes redirectAttributes) {
        PartySettingsEntity party = sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session);
        if (!closed) {
            planService.require(party, PlanFeature.WISHES);
        }
        found(wishService.setClosed(partyCode, id, closed));
        done(redirectAttributes, closed ? "closed" : "reopened");
        return back(partyCode, id);
    }

    /** "Nowy link": the old one (a photo of an invitation online) opens nothing; the wishes stay. Pro. */
    @PostMapping("/{id}/link")
    public String newLink(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                          HttpSession session, RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        found(wishService.newLink(partyCode, id));
        done(redirectAttributes, "new_link");
        return back(partyCode, id);
    }

    /** "Usuń": the event and its wishes, at once — any plan, as the library and the logo. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam String partyCode, OAuth2AuthenticationToken authentication,
                         HttpSession session, RedirectAttributes redirectAttributes) {
        sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session);
        found(wishService.delete(partyCode, id));
        log.info("Party [{}]: wishes event {} deleted", partyCode, id);
        done(redirectAttributes, "deleted");
        return back(partyCode, null);
    }

    /**
     * "⭐ Do „Koniecznie zagrać”": the songs named ({@code song}), or the {@value #TOP} most wished ({@code top}), added to the hosts'
     * wish list — the AI lets a guest's request for one through, the queue marks it. The list keeps {@value SongList#MAX_LINES}: what
     * does not fit is said, not dropped in silence.
     */
    @PostMapping("/{id}/must-play")
    public String mustPlay(@PathVariable long id, @RequestParam String partyCode, @RequestParam(required = false) List<String> song,
                           @RequestParam(defaultValue = "false") boolean top, OAuth2AuthenticationToken authentication,
                           HttpSession session, RedirectAttributes redirectAttributes) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.WISHES, authentication, session), PlanFeature.WISHES);
        WishService.Event event = wishService.event(partyCode, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // only songs the event has, named "Artist - Title" (the ones to check the DJ reads first): the form is no way to write to the
        // hosts' list
        List<String> eventSongs = wishService.wishes(event.id()).stream().filter(WishService.Wish::named).map(WishService.Wish::songName)
                .toList();
        List<String> chosen = top ? eventSongs.stream().limit(TOP).toList()
                : (song == null ? List.<String>of() : song.stream().filter(eventSongs::contains).distinct().toList());
        int[] counts = new int[2];   // added, did not fit
        partySettingsCommandService.updateSettings(partyCode, s -> {
            String before = s.getHostWanted();
            SongList list = SongList.of(before);
            // a song the list names already ("Akcent" names every song of Akcent) is not written again
            List<String> adding = chosen.stream().filter(one -> !list.matches(one)).toList();
            String joined = before == null || before.isBlank() ? String.join("\n", adding)
                    : before + (adding.isEmpty() ? "" : "\n" + String.join("\n", adding));
            String kept = SongList.tidy(joined);
            s.setHostWanted(kept);
            counts[0] = Math.max(0, SongList.entries(kept) - SongList.entries(before));
            counts[1] = Math.max(0, SongList.entries(joined) - SongList.entries(kept));
        });
        log.info("Party [{}]: {} wish(es) of event {} added to the hosts' wish list, {} did not fit", partyCode, counts[0], id, counts[1]);
        done(redirectAttributes, counts[1] > 0 ? "must_play_full" : "must_play", String.valueOf(counts[0]), String.valueOf(counts[1]));
        return back(partyCode, id);
    }

    /** The event's wishes as a CSV file, every song. */
    @GetMapping("/{id}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable long id, @RequestParam String party, OAuth2AuthenticationToken authentication,
                                      HttpSession session, Locale locale) {
        PartySettingsEntity settings = sessionHelper.require(party, StaffPermission.WISHES, authentication, session);
        WishService.Event event = wishService.event(settings.getPartyCode(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<WishService.Wish> wishes = wishService.wishes(event.id(), WishService.MAX_WISHES);
        Map<LibraryKey, LibraryMark> marks = new LinkedHashMap<>();
        libraryMatches(settings, wishes).forEach((key, match) -> marks.put(key, match.mark()));
        String csv = WishService.toCsv(wishes, marks, messageSource, locale);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("scan2play-zyczenia-" + event.date() + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** The guests' link of the event: {@code https://…/w/{token}}. */
    private String linkOf(WishService.Event event) {
        return Texts.withoutTrailingSlash(rawBaseUrl) + "/w/" + event.token();
    }

    private static LocalDate dayOf(String date) {
        try {
            return date == null || date.isBlank() ? null : LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void found(boolean changed) {
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** What the page says after a form: {@code wishes.done.<key>}, with its arguments. */
    private static void done(RedirectAttributes redirectAttributes, String key, String... args) {
        redirectAttributes.addFlashAttribute(WISH_DONE, key);
        redirectAttributes.addFlashAttribute(WISH_DONE_ARG, args.length == 0 || args[0] == null ? new String[0] : args);
    }

    private static String back(String partyCode, Long event) {
        return "redirect:/dj/wishes?party=" + partyCode + (event == null ? "" : "&event=" + event + "#wishEvent");
    }
}
