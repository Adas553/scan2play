package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestRequestService;
import com.scan2play.service.PlanService;
import com.scan2play.service.WishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The guests' page of "Życzenia przed imprezą" (V68, Pro): before an event a guest opens its link ({@code /w/{token}}, on the
 * invitation, in an e-mail or a chat) and writes the songs they want to hear — up to {@value #PER_GUEST}; the DJ sees them counted
 * ({@link DjWishesController}). Without a login and without anything of the guest kept: the song and how many wrote it. Nobody sees
 * another guest's wishes here — they are not checked by the AI, so they are the DJ's alone.
 * <p>
 * Against one person writing in a loop: {@value #PER_GUEST} wishes a session, {@value GuestRequestLimiter#WISHES_PER_ADDRESS} an
 * address in its window, {@value WishService#MAX_WISHES} an event. An unknown or old secret, or a party without Pro, is a 404.
 */
@Controller
@RequestMapping("/w")
@RequiredArgsConstructor
@Slf4j
public class WishController {

    /** The songs one guest writes to one event: enough for their favourites, few enough that the count means something. */
    public static final int PER_GUEST = 5;

    /** This session's wishes to the event of that id, by {@link #SESSION_PREFIX} + id. */
    static final String SESSION_PREFIX = "s2p.wishes.";

    private final WishService wishService;
    private final PartySettingsRepository partySettingsRepository;
    private final PlanService planService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final MessageSource messageSource;

    /** The page: the event's name and date, and the field — or that the collecting has ended. */
    @GetMapping("/{token}")
    public String page(@PathVariable String token, Model model, HttpSession session) {
        WishService.Event event = event(token);
        PartySettingsEntity party = party(event);
        Locale locale = LocaleContextHolder.getLocale();
        model.addAttribute(WISH_TOKEN, token);
        model.addAttribute(WISH_EVENT, event);
        model.addAttribute(WISH_DATE, dateText(event.date(), locale));
        model.addAttribute(WISH_OPEN, event.open(WishService.today()) && !event.full());
        model.addAttribute(WISH_LEFT, PER_GUEST - written(session, event.id()));
        PartySettingsEntity planned = planService.asPlanned(party);
        model.addAttribute(DJ_NAME, party.getDjName());
        model.addAttribute(DJ_LOGO, planned.logoPath());
        model.addAttribute(GUEST_THEME, party.getGuestTheme());   // the party's look (V59), as its other guests' pages
        return "wish";
    }

    /** A guest's wish: kept, or why not — back to the page either way (it says which). */
    @PostMapping("/{token}")
    public String write(@PathVariable String token, @RequestParam(required = false) String song, HttpSession session,
                        HttpServletRequest request, RedirectAttributes redirectAttributes) {
        WishService.Event event = event(token);
        party(event);
        Locale locale = LocaleContextHolder.getLocale();
        String back = "redirect:/w/" + token;
        if (written(session, event.id()) >= PER_GUEST) {
            redirectAttributes.addFlashAttribute(WISH_NOTE, messageSource.getMessage("wish.note.per_guest",
                    new Object[]{PER_GUEST}, locale));
            return back;
        }
        if (WishService.tidySong(song).isEmpty()) {
            redirectAttributes.addFlashAttribute(WISH_NOTE, messageSource.getMessage("wish.note.empty", null, locale));
            return back;
        }
        Optional<Long> wait = guestRequestLimiter.tryAcquireWish(guestRequestLimiter.clientIp(request), token);
        if (wait.isPresent()) {
            redirectAttributes.addFlashAttribute(WISH_NOTE, messageSource.getMessage("wish.note.too_many",
                    new Object[]{GuestRequestService.waitText(wait.get())}, locale));
            return back;
        }
        WishService.Added added = wishService.add(token, song);
        switch (added) {
            case ADDED -> {
                session.setAttribute(SESSION_PREFIX + event.id(), written(session, event.id()) + 1);
                redirectAttributes.addFlashAttribute(WISH_ADDED, WishService.tidySong(song));
                log.info("Wishes event {}: a guest's wish", event.id());
            }
            case FULL -> redirectAttributes.addFlashAttribute(WISH_NOTE, messageSource.getMessage("wish.note.full", null, locale));
            case EMPTY -> redirectAttributes.addFlashAttribute(WISH_NOTE, messageSource.getMessage("wish.note.empty", null, locale));
            case CLOSED -> { }   // the page says the collecting has ended
        }
        return back;
    }

    /** How many wishes this session wrote to the event. */
    private static int written(HttpSession session, long eventId) {
        return session.getAttribute(SESSION_PREFIX + eventId) instanceof Integer count ? count : 0;
    }

    /** "14 czerwca 2027" / "14 June 2027": the day as the page's language writes it. */
    static String dateText(LocalDate date, Locale locale) {
        Locale written = "pl".equals(locale.getLanguage()) ? Locale.forLanguageTag("pl") : Locale.ENGLISH;
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", written));
    }

    private WishService.Event event(String token) {
        return wishService.byToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The event's party, with the plan that has the wishes — else a 404, as a link that never was. */
    private PartySettingsEntity party(WishService.Event event) {
        return partySettingsRepository.findByPartyCode(event.partyCode())
                .filter(party -> planService.allows(party, PlanFeature.WISHES))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
