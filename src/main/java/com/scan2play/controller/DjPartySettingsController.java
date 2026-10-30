package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.PosterLanguage;
import com.scan2play.model.PosterTheme;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.VibeType;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartyCodeService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlanService;
import com.scan2play.util.CodeGenerator;
import com.scan2play.util.SocialLinks;
import com.scan2play.util.SongList;
import com.scan2play.util.Texts;
import com.scan2play.util.TipLinks;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * Controller responsible for party configuration and lifecycle management.
 * <p>
 * Handles:
 * <ul>
 *     <li>Start / end party</li>
 *     <li>Vibe, who plays, and rate limits</li>
 *     <li>Account deletion (required by Google API Services User Data Policy)</li>
 * </ul>
 * Dashboard views are handled by {@link DjDashboardController}.
 * Song queue actions are handled by {@link DjSongController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjPartySettingsController {

    /** The most songs the duplicate check looks back at (each is a line of the AI's prompt for every guest's request). */
    static final int MAX_DUPLICATE_CHECK_WINDOW = 50;
    static final int MAX_REQUEST_LIMIT = 100;
    /** A day. */
    static final int MAX_COOLDOWN_MINUTES = 1440;

    private final PartySettingsCommandService partySettingsCommandService;
    private final AccountDeletionService accountDeletionService;
    private final PartyCodeService partyCodeService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;

    /**
     * Re-activates the party session.
     */
    @PostMapping("/start-party")
    public String startParty(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        PartySettingsEntity settings = sessionHelper.require(partyCode, StaffPermission.OPEN_CLOSE, authentication, session);
        partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setActive(true));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Ends the current party session without logging out the DJ.
     */
    @PostMapping("/end-party")
    public String endParty(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                           HttpSession session) {
        PartySettingsEntity settings = sessionHelper.require(partyCode, StaffPermission.OPEN_CLOSE, authentication, session);
        log.info("Ending party {} by {}", settings.getPartyCode(), authentication.getName());
        partySettingsCommandService.updateSettings(settings.getPartyCode(), p -> p.setActive(false));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the global music vibe/theme for the event.
     */
    @PostMapping("/dashboard/vibe")
    public String updateGlobalVibe(@RequestParam String partyCode, @RequestParam VibeType newVibe,
                                   OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.require(partyCode, StaffPermission.VIBE, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setGlobalVibe(newVibe));
        return REDIRECT_DASHBOARD;
    }

    /**
     * How the AI words its comment to the guest (V22): classic, funny, lightly sarcastic, sarcastic or short. An unknown value is a
     * 400 (the enum binding), as for the vibe.
     */
    @PostMapping("/dashboard/comment-style")
    public String updateCommentStyle(@RequestParam String partyCode, @RequestParam CommentStyle commentStyle,
                                     OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity party = sessionHelper.require(partyCode, StaffPermission.VIBE, authentication, session);
        if (commentStyle != CommentStyle.CLASSIC) {
            planService.require(party, PlanFeature.COMMENT_STYLES);   // "Klasyczne" is everyone's (V35)
        }
        partySettingsCommandService.updateSettings(partyCode, s -> s.setCommentStyle(commentStyle));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Whether the guests may add a dedication to a request (V38): saved as soon as the DJ picks, as the vibe. Off, the party page has
     * no field and the server takes none.
     */
    @PostMapping("/dashboard/dedications")
    public String updateDedications(@RequestParam String partyCode, @RequestParam boolean on,
                                    OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.require(partyCode, StaffPermission.VIBE, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setDedicationsOn(on));
        return REDIRECT_DASHBOARD;
    }

    /**
     * The card "Impreza"'s words, under one "Zapisz" (the design review, 2026-10-10: each field had its own): the DJ's own words
     * about the vibe (V16) — one line, at most {@value PartySettingsEntity#VIBE_NOTE_MAX} characters; the AI is given it with every
     * request, the guests see it on the party page — and who plays (V17), e.g. "DJ Koko" — at most
     * {@value PartySettingsEntity#DJ_NAME_MAX}; the guests see "🎧 Gra: DJ Koko". Empty clears either.
     */
    @PostMapping("/dashboard/party-words")
    public String updatePartyWords(@RequestParam String partyCode, @RequestParam(required = false) String vibeNote,
                                   @RequestParam(required = false) String djName,
                                   OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.require(partyCode, StaffPermission.VIBE, authentication, session);
        String note = Texts.oneLine(vibeNote, PartySettingsEntity.VIBE_NOTE_MAX);
        String name = Texts.oneLine(djName, PartySettingsEntity.DJ_NAME_MAX);
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setVibeNote(note.isEmpty() ? null : note);
            s.setDjName(name.isEmpty() ? null : name);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * The QR print as the DJ sets it (V46, V47), from the print page (qr-print.js, in the background — 204): the DJ's own heading and
     * line (one line each, cut to {@value PartySettingsEntity#POSTER_HEADING_MAX} / {@value PartySettingsEntity#POSTER_LINE_MAX}; empty =
     * ours / none), the language, the look (the theme; the DJ's colour "#rrggbb", anything else the theme's own; the heading's font, V51,
     * anything else the theme's; the heading's size, V54, SMALL / NORMAL / LARGE, anything else NORMAL; the paper's colour, V53, "#rrggbb", anything else the theme's), the number of tables
     * (V50: 1 … {@value PartySettingsEntity#POSTER_TABLES_MAX}, more is the most; anything else none) and what of the DJ's it shows.
     * Whoever may change the party's card "Impreza" (VIBE), any plan: the words on paper are the party's, as its vibe note; the logo
     * shown stays the plan's (PlanService.asPlanned).
     */
    @PostMapping("/qr-print/settings")
    @ResponseBody
    public ResponseEntity<Void> updatePoster(@RequestParam String partyCode, @ModelAttribute PosterForm form,
                                             OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.require(partyCode, StaffPermission.VIBE, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, form::applyTo);
        return ResponseEntity.noContent().build();
    }

    /**
     * The print page's editor as sent (qr-print.html #posterForm): every field a text or a box, read leniently by {@link #applyTo} — a
     * box not sent is off. A new field of the print is a component here and a line in {@code applyTo}.
     */
    public record PosterForm(String heading, String line, String language, String theme, String accent, String font, String size,
                             String paper, String tables, Boolean showLogo, Boolean showLinks, Boolean showTip, Boolean showAddress) {

        /** The party's print as the form says (one line each, cut to their columns; anything not understood is the default). */
        void applyTo(PartySettingsEntity s) {
            String ownHeading = Texts.oneLine(heading, PartySettingsEntity.POSTER_HEADING_MAX);
            String ownLine = Texts.oneLine(line, PartySettingsEntity.POSTER_LINE_MAX);
            s.setPosterHeading(ownHeading.isEmpty() ? null : ownHeading);
            s.setPosterLine(ownLine.isEmpty() ? null : ownLine);
            s.setPosterLanguage(PosterLanguage.of(language));
            s.setPosterTheme(PosterTheme.of(theme));
            s.setPosterAccent(PosterTheme.accent(accent));
            s.setPosterFont(com.scan2play.model.PosterFont.of(font));
            s.setPosterSize(com.scan2play.model.PosterSize.of(size));
            s.setPosterPaper(PosterTheme.accent(paper));
            s.setPosterTables(posterTables(tables));
            s.setPosterShowLogo(Boolean.TRUE.equals(showLogo));
            s.setPosterShowLinks(Boolean.TRUE.equals(showLinks));
            s.setPosterShowTip(Boolean.TRUE.equals(showTip));
            s.setPosterShowAddress(Boolean.TRUE.equals(showAddress));
        }
    }

    /** The number of tables as kept (V50): a whole number from 1, more than the most is the most; anything else none. */
    static Integer posterTables(String value) {
        if (value == null || !value.trim().matches("\\d{1,9}")) {
            return null;
        }
        int tables = Integer.parseInt(value.trim());
        return tables < 1 ? null : Math.min(tables, PartySettingsEntity.POSTER_TABLES_MAX);
    }

    /**
     * The DJ's profiles (V24) the guests see under "🎧 Gra: …" and on the QR print: each one "@name", a name, or a link copied from
     * the site, kept as an https address on that site ({@link SocialLinks}); empty clears it. 400 and nothing saved when one of them
     * is not a profile on its site.
     */
    @PostMapping("/dashboard/dj-links")
    public String updateDjLinks(@RequestParam String partyCode, @RequestParam(required = false) String instagram,
                                @RequestParam(required = false) String facebook, @RequestParam(required = false) String tiktok,
                                OAuth2AuthenticationToken authentication, HttpSession session) {
        planService.require(sessionHelper.requireOwner(partyCode, authentication, session), PlanFeature.PROFILES);
        String instagramUrl;
        String facebookUrl;
        String tiktokUrl;
        try {
            instagramUrl = SocialLinks.instagram(Texts.oneLine(instagram, PartySettingsEntity.LINK_MAX));
            facebookUrl = SocialLinks.facebook(Texts.oneLine(facebook, PartySettingsEntity.LINK_MAX));
            tiktokUrl = SocialLinks.tiktok(Texts.oneLine(tiktok, PartySettingsEntity.LINK_MAX));
        } catch (IllegalArgumentException e) {
            log.info("Party [{}]: the DJ's links not saved — {}", partyCode, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setInstagramUrl(instagramUrl);
            s.setFacebookUrl(facebookUrl);
            s.setTiktokUrl(tiktokUrl);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * The DJ's tip link (V27): the guests see "💸 Napiwek dla DJ-a" on the party page and under an accepted request, and the link
     * on the QR print. Kept as an https address on one of the services ({@link TipLinks}); empty clears it. 400 and nothing saved
     * when it is not a page there.
     */
    @PostMapping("/dashboard/tip-link")
    public String updateTipLink(@RequestParam String partyCode, @RequestParam(required = false) String tip,
                                OAuth2AuthenticationToken authentication, HttpSession session) {
        planService.require(sessionHelper.requireOwner(partyCode, authentication, session), PlanFeature.TIPS);
        String tipUrl;
        try {
            tipUrl = TipLinks.tipUrl(Texts.oneLine(tip, PartySettingsEntity.LINK_MAX));
        } catch (IllegalArgumentException e) {
            log.info("Party [{}]: the tip link not saved — {}", partyCode, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        partySettingsCommandService.updateSettings(partyCode, s -> s.setTipUrl(tipUrl));
        return REDIRECT_DASHBOARD;
    }

    /**
     * The hosts' lists (V29), as the DJ edits them: "🚫 nie grać" and "⭐ koniecznie zagrać", one song or artist per line
     * ({@link SongList#tidy}); an empty one clears it.
     */
    @PostMapping("/dashboard/host-lists")
    public String updateHostLists(@RequestParam String partyCode, @RequestParam(required = false) String blocked,
                                  @RequestParam(required = false) String wanted,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.HOST_LISTS, authentication, session),
                PlanFeature.HOST_LISTS);
        String blockedList = SongList.tidy(blocked);
        String wantedList = SongList.tidy(wanted);
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setHostBlocked(blockedList);
            s.setHostWanted(wantedList);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * The hosts' link (V29, {@code /h/{token}}): {@code link=new} makes one — a new secret, so a link given for the last event stops
     * working —, {@code link=off} takes it away.
     */
    @PostMapping("/dashboard/host-link")
    public String updateHostLink(@RequestParam String partyCode, @RequestParam String link,
                                 OAuth2AuthenticationToken authentication, HttpSession session) {
        planService.require(sessionHelper.require(partyCode, StaffPermission.HOST_LISTS, authentication, session),
                PlanFeature.HOST_LISTS);
        String token = switch (link) {
            case "new" -> CodeGenerator.generateSecret();
            case "off" -> null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        Instant madeAt = token == null ? null : Instant.now();
        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setHostToken(token);
            s.setHostTokenMadeAt(madeAt);   // it works 30 days (V37)
        });
        // a full page load: the page "Ustawienia imprezy" of the same party again (a co-organiser's too), at the card
        return REDIRECT_SETTINGS + "?party=" + partyCode + "#hostListsCard";
    }

    /**
     * Updates the rate limiting and duplicate checking parameters for the party.
     */
    @PostMapping("/dashboard/limits")
    public String updateLimits(@RequestParam String partyCode,
                               @RequestParam double requestLimit,
                               @RequestParam double cooldownMinutes,
                               @RequestParam double duplicateCheckWindow,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        // without Pro every party has the defaults (V35): the form is not shown, and the server does not take it
        planService.require(sessionHelper.require(partyCode, StaffPermission.LIMITS, authentication, session),
                PlanFeature.GUEST_LIMITS);

        // Whole numbers within bounds (review item 5.4): the duplicate window is read from the database and sent to the AI with
        // every guest's request, so it has a ceiling; the guest limit's own ceilings only keep the numbers sensible. All three
        // the same way: a number with a fraction is rounded, not refused.
        int safeRequestLimit = wholeWithin(requestLimit, 1, MAX_REQUEST_LIMIT);
        int safeCooldownMinutes = wholeWithin(cooldownMinutes, 1, MAX_COOLDOWN_MINUTES);
        int safeDuplicateCheckWindow = wholeWithin(duplicateCheckWindow, 0, MAX_DUPLICATE_CHECK_WINDOW);

        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setRequestLimit(safeRequestLimit);
            s.setCooldownMinutes(safeCooldownMinutes);
            s.setDuplicateCheckWindow(safeDuplicateCheckWindow);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * "Zmień kod imprezy" (V49, the organiser's alone — it makes their posters and cards dead): the party gets a new code, the old
     * one stops working for good ({@link PartyCodeService}) — against a guest who keeps the code and spams every party. A full page
     * load: "Ustawienia imprezy" of the new code, its card unfolded with the new code and the way to print it again. This browser's
     * panel follows at once; the organiser's other devices and the staff's open panels find the old code gone at their next poll
     * (a 403) and load their panel again, which is the new code's.
     */
    @PostMapping("/dashboard/party-code")
    public String changePartyCode(@RequestParam String partyCode, OAuth2AuthenticationToken authentication, HttpSession session,
                                  RedirectAttributes redirectAttributes) {
        sessionHelper.requireOwner(partyCode, authentication, session);
        String newCode = partyCodeService.changeCode(partyCode);
        session.setAttribute(DjSessionHelper.SESSION_PARTY_CODE, newCode);
        redirectAttributes.addFlashAttribute(CODE_CHANGED, newCode);
        return REDIRECT_SETTINGS + "?party=" + newCode + "#partyCodeCard";
    }

    /**
     * Deletes all data associated with the currently logged-in DJ account.
     * Required by Google API Services User Data Policy — users must be able to delete their data.
     * After deletion, the session is invalidated and the user is redirected to the home page.
     */
    @PostMapping("/delete-account")
    public String deleteAccount(OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerId = authentication.getName();
        log.info("Account deletion requested by ownerId={}", ownerId);
        accountDeletionService.deleteAllUserData(ownerId);
        session.invalidate();
        return REDIRECT_HOME;
    }

    /** {@code value} rounded to a whole number within {@code min}..{@code max}; not a number at all is {@code min}. */
    static int wholeWithin(double value, int min, int max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return (int) Math.round(Math.min(max, Math.max(min, value)));
    }
}

