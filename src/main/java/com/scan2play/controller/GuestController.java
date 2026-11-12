package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestRequestService;
import com.scan2play.service.GuestRequestService.Sent;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.LibraryHintService;
import com.scan2play.service.PartyScheduleService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.service.PlanService;
import com.scan2play.util.BookingContact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.scan2play.controller.ViewAttributes.*;

@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
@Slf4j
public class GuestController {

    private final GuestQueueService guestQueueService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final GuestSessionService guestSessionService;
    private final GuestVoteService guestVoteService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final MessageSource messageSource;
    private final PlanService planService;
    private final GuestRequestService guestRequestService;
    private final PartyScheduleService partyScheduleService;
    private final LibraryHintService libraryHintService;

    /**
     * The party page. It takes no {@code HttpSession} of its own (review 2026-10-14 W3): a parameter of that type made a session — a
     * row in PostgreSQL for 30 minutes — for every {@code GET /p/{anything}}, a code that is no party too. The guest's session is read
     * only when they have one; an open party's page makes one anyway (its form carries the CSRF token), a code that is no party and
     * a party that has ended make none.
     */
    @GetMapping("/{partyCode}")
    public String partyIndex(@PathVariable String partyCode, Model model, HttpServletRequest request) {
        try {
            // as the organiser's plan lets it work (V35): without Pro no profiles and no tip button
            PartySettingsEntity settings = planService.asPlanned(partySettingsQueryService.getSettings(partyCode));

            if (!settings.isActive()) {
                return partyEnded(model, partyCode, settings);
            }

            model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
            model.addAttribute(VIBE_NOTE, settings.getVibeNote());
            model.addAttribute(DJ_NAME, settings.getDjName());
            model.addAttribute(DEDICATIONS_ON, settings.isDedicationsOn());
            model.addAttribute(LIBRARY_HINTS, libraryHintService.on(settings));   // the suggestions marked "✓ DJ ma" (V58)
            addProfiles(model, settings);
            model.addAttribute(TIP_URL, settings.getTipUrl());
            model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, request.getSession(false)));
            model.addAttribute(PARTY_CODE, partyCode);
            return "index";
        } catch (IllegalArgumentException e) {
            // DEBUG, not WARN: anyone can ask for any code, and a line for each filled the log (review 2026-10-14, M-2)
            log.debug("Invalid party code access attempt: {}", partyCode);
            // "Ten kod imprezy nie istnieje" (the error page's 404), not the landing page for DJs: a guest with a mistyped code or an
            // old poster learned nothing there (the review of 2026-10-21, C3)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Which of the guest's suggestions the DJ has (V58, {@link LibraryHintService}): {@code song} once for each, "Artist - Title";
     * the answer a list of as many true / false, in their order (song-autocomplete.js puts those first, "✓ DJ ma"). 404 when the party
     * has none to tell — ended, no library, no plan, switched off — or is no party; at most {@value LibraryHintService#MOST_SONGS}
     * songs are looked at; a 429 past {@value LibraryHintService#ASKS_A_MINUTE} asks a minute of the address (the suggestions are then
     * shown unmarked). No session is made: it is asked as the guest types.
     */
    @GetMapping("/{partyCode}/library/has")
    @ResponseBody
    public List<Boolean> libraryHas(@PathVariable String partyCode, @RequestParam(name = "song", required = false) List<String> songs,
                                    HttpServletRequest request) {
        PartySettingsEntity settings;
        try {
            settings = partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!libraryHintService.on(settings)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!libraryHintService.mayAsk(partyCode, guestRequestLimiter.clientIp(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        return libraryHintService.have(partyCode, songs == null ? List.of() : songs);
    }

    /**
     * The list under the request form alone (the requests sent lately, where the guest's song waits): the party page
     * fetches it again when the guest comes back to it and on "↻ Odśwież" — no timer, so a room of phones asks only when someone
     * looks. Empty when the party has ended or there is nothing to show.
     */
    @GetMapping("/{partyCode}/queue")
    public String partyQueue(@PathVariable String partyCode, Model model, HttpServletRequest request) {
        try {
            if (partySettingsQueryService.getSettings(partyCode).isActive()) {
                model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, request.getSession(false)));
                model.addAttribute(PARTY_CODE, partyCode);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "fragments/guest-queue :: guestQueue";
    }

    /**
     * The folded rest of the list ("Pokaż pozostałe prośby"): fetched by the page only when the guest unfolds it — with 300 requests
     * waiting, the list every guest's phone fetches again stays the first five. Empty when the party has ended.
     */
    @GetMapping("/{partyCode}/queue/more")
    public String partyQueueMore(@PathVariable String partyCode, Model model, HttpServletRequest request) {
        try {
            if (partySettingsQueryService.getSettings(partyCode).isActive()) {
                model.addAttribute(GUEST_QUEUE, guestQueue(partyCode, request.getSession(false)));
                model.addAttribute(PARTY_CODE, partyCode);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "fragments/guest-queue :: moreList";
    }

    /**
     * A guest's 👍 on a waiting song of the list ({@code on=true}), or taking it back ({@code on=false}) — one vote per song, the
     * guest's own request is their vote already ({@link GuestVoteService}). Sent by guest-party.js in the background
     * ({@code X-Requested-With: fetch}): the answer is that song's row with its new votes (none when it no longer waits) and a note
     * when the 👍 did not count — the page puts only the votes in place, nothing moves.
     * Without the script (a plain form post): back to the party page.
     */
    @PostMapping("/{partyCode}/vote")
    public String vote(@PathVariable String partyCode, @RequestParam long id, @RequestParam(defaultValue = "true") boolean on,
                       @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                       Model model, HttpSession session, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        PartySettingsEntity settings;
        try {
            settings = partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String note = null;
        if (!settings.isActive()) {
            note = "guest.vote.party_ended";
        } else if (!guestSessionService.myRequestIds(session, partyCode).contains(id)) {   // the guest's own song: no 👍 on it
            Optional<Long> wait = guestRequestLimiter.tryAcquireVote(guestRequestLimiter.clientIp(request), partyCode);
            if (wait.isPresent()) {
                note = "guest.vote.too_many";
            } else {
                GuestVoteService.Result result = on ? guestVoteService.vote(session, partyCode, id)
                        : guestVoteService.takeBack(session, partyCode, id);
                if (result == GuestVoteService.Result.GONE) {
                    note = "guest.vote.gone";
                }
            }
        }
        String noteText = note == null ? null : messageSource.getMessage(note, null, LocaleContextHolder.getLocale());
        if (!"fetch".equals(requestedWith)) {
            if (noteText != null) {
                redirectAttributes.addFlashAttribute(ERROR_MESSAGE, noteText);
            }
            return "redirect:/p/" + partyCode;
        }
        if (settings.isActive()) {
            GuestQueueService.GuestQueue queue = guestQueue(partyCode, session);
            model.addAttribute(GUEST_QUEUE, queue);
            model.addAttribute(PARTY_CODE, partyCode);
            model.addAttribute(VOTE_SONG, queue.song(id).orElse(null));
        }
        model.addAttribute(VOTE_NOTE, noteText);
        return "fragments/guest-queue :: voteAnswer";
    }

    /** What the guest sees of the requests: their own marked, their 👍 shown — none without a session yet. */
    private GuestQueueService.GuestQueue guestQueue(String partyCode, HttpSession session) {
        if (session == null) {
            return guestQueueService.view(partyCode, Set.of(), Set.of());
        }
        return guestQueueService.view(partyCode, guestSessionService.myRequestIds(session, partyCode),
                guestVoteService.myVotes(session, partyCode));
    }

    /**
     * Processes a song request asynchronously using {@link Callable} to release the Tomcat thread
     * during the AI evaluation (~2-4 seconds).
     * The HTTP connection stays open; the guest sees the result when processing completes.
     */
    @PostMapping("/{partyCode}/request")
    public Callable<String> requestSong(@PathVariable String partyCode,
                              @RequestParam String songName,
                              @RequestParam(required = false) String dedication,
                              Model model,
                              HttpSession session,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        String clientIp = guestRequestLimiter.clientIp(request);
        Locale locale = LocaleContextHolder.getLocale();
        return () -> {
            try {
                // the guest's limit and the tip button as the organiser's plan has them (V35)
                PartySettingsEntity settings = planService.asPlanned(partySettingsQueryService.getSettings(partyCode));

                if (!settings.isActive()) {
                    return partyEnded(model, partyCode, settings);
                }

                Sent sent = guestRequestService.fromParty(partyCode, settings, songName, dedication, session, clientIp, locale);
                if (sent instanceof Sent.Refused refused) {
                    return refuse(redirectAttributes, partyCode, refused.messageKey(), refused.args());
                }
                if (sent instanceof Sent.Mood mood) {
                    // A mood, not a song (the DJ sets the mood): nothing was saved. Back to the form with the text, asking for a song.
                    redirectAttributes.addFlashAttribute(LAST_REQUEST, mood.songName());
                    redirectAttributes.addFlashAttribute(LAST_DEDICATION, mood.dedication());
                    songOnlyHint(redirectAttributes, settings, locale, messageSource);
                    return refuse(redirectAttributes, partyCode, "guest.error.song_only");
                }
                DjResponse response = ((Sent.Answered) sent).response();
                model.addAttribute(RESPONSE, response);
                model.addAttribute(PARTY_CODE, partyCode);
                // the DJ's tip link under an accepted request: the moment a guest is glad (V27)
                model.addAttribute(TIP_URL, settings.getTipUrl());
                // who plays, at the top as on the party page: the result is the same party, not another app (the design review, 2026-10-09)
                model.addAttribute(DJ_NAME, settings.getDjName());
                // "Zarezerwuj tego DJ-a" under an accepted request: the guest writes to the DJ on a profile (an idea from Rekwest), or
                // by e-mail or phone (V67) — the subject names the evening, so the DJ knows where the guest saw them
                addProfiles(model, settings);
                addBooking(model, settings, messageSource.getMessage("party.book.subject.evening", new Object[]{
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
                                .format(EveningSummaryService.eveningOf(Instant.now()))}, locale));
                return "result";
            } catch (IllegalArgumentException e) {
                log.warn("Song request for unknown party code: {}", partyCode);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);   // the party's account deleted meanwhile, or a code made up
            }
        };
    }

    /**
     * The page "the DJ is not taking requests": "check again" opens the party's link, and under it the DJ's profiles with "Zarezerwuj
     * tego DJ-a na swoją imprezę" (an idea from Rekwest, 2026-10-16) — the guest who liked the night writes to the DJ there; Scan2Play
     * keeps no guest's address. As the organiser's plan has the profiles (V35).
     */
    private String partyEnded(Model model, String partyCode, PartySettingsEntity settings) {
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(DJ_NAME, settings.getDjName());
        // "Prośby od 20:00" in place of "DJ nie przyjmuje teraz próśb" while the party opens by itself later (V55)
        model.addAttribute(OPENS_AT, partyScheduleService.guestOpens(settings, LocaleContextHolder.getLocale()));
        addProfiles(model, settings);
        // no date in the e-mail's subject here: the page is also opened days after the party
        addBooking(model, settings, messageSource.getMessage("party.book.subject", null, LocaleContextHolder.getLocale()));
        return "party_ended";
    }

    /**
     * The DJ's contact for bookings (V67) under "Zarezerwuj tego DJ-a": the e-mail link with {@code subject} filled in and the phone
     * link, or null when the DJ gave neither (or the plan has no profiles — {@code settings} is as planned).
     */
    private static void addBooking(Model model, PartySettingsEntity settings, String subject) {
        model.addAttribute(BOOKING, BookingContact.of(settings.getBookingEmail(), settings.getBookingPhone(), subject));
    }

    /**
     * The DJ's profiles (V24): https addresses as SocialLinks wrote them, or null; the DJ's logo (V45); and the look the DJ picked
     * for the guests' pages (V59) — every page that shows the profiles is a guest's page of the party.
     */
    private static void addProfiles(Model model, PartySettingsEntity settings) {
        model.addAttribute(GUEST_THEME, settings.getGuestTheme());
        model.addAttribute(INSTAGRAM_URL, settings.getInstagramUrl());
        model.addAttribute(FACEBOOK_URL, settings.getFacebookUrl());
        model.addAttribute(TIKTOK_URL, settings.getTiktokUrl());
        model.addAttribute(DJ_LOGO, settings.logoPath());
    }

    /**
     * Under "the DJ takes songs here" (a mood or a message, not a song): where the guest's words can go — a dedication with a song
     * ({@code guest.error.song_only.hint}). Only while the DJ takes dedications: the hint names their field. A wish ("sto lat dla
     * Ani") reaches the DJ that way, checked by the AI as every dedication — no message of its own to the DJ.
     */
    static void songOnlyHint(RedirectAttributes redirectAttributes, PartySettingsEntity settings, Locale locale,
                             MessageSource messageSource) {
        if (settings.isDedicationsOn()) {
            redirectAttributes.addFlashAttribute(ERROR_HINT, messageSource.getMessage("guest.error.song_only.hint", null, locale));
        }
    }

    /** Back to the party page with the message of the limit that refused the request. */
    private String refuse(RedirectAttributes redirectAttributes, String partyCode, String messageKey, Object... args) {
        redirectAttributes.addFlashAttribute(ERROR_MESSAGE,
                messageSource.getMessage(messageKey, args, LocaleContextHolder.getLocale()));
        return "redirect:/p/" + partyCode;
    }
}
