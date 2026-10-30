package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.PartyScheduleService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlanService;
import com.scan2play.service.QrCodeService;
import com.scan2play.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The screen for a TV in the hall or the pub (Next 7 c, an idea from Rekwest; the owner, 2026-10-18: public, the ten most wanted,
 * plan Free): the party's QR code and the guests' most wanted songs with their votes — what the guests see under their form anyway,
 * never their words or dedications, and no order of play (the DJ plays from their own software). Public like the guest's page, so
 * a smart TV opens it without a login; it has no form and makes no session — a TV keeps it open all night. {@code js/tv.js} fetches
 * the list again every few seconds.
 */
@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
public class TvScreenController {

    /** The side of the QR code on the screen, in pixels: sharp on a TV across the hall. */
    static final int QR_SIZE = 600;

    private final PartySettingsQueryService partySettingsQueryService;
    private final GuestQueueService guestQueueService;
    private final QrCodeService qrCodeService;
    private final PlanService planService;
    private final PartyScheduleService partyScheduleService;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    @GetMapping("/{partyCode}/tv")
    public String screen(@PathVariable String partyCode, Model model) {
        PartySettingsEntity settings = party(partyCode);
        String guestUrl = Texts.withoutTrailingSlash(rawBaseUrl) + "/p/" + partyCode;
        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(DJ_LOGO, planService.asPlanned(settings).logoPath());
        model.addAttribute(QR_CODE_BASE64, qrCodeService.generateQrCodeBase64(guestUrl, QR_SIZE, QR_SIZE));
        model.addAttribute(PERMANENT_LINK, guestUrl.replaceFirst("^https?://", ""));   // to read and type: the code holds the rest
        addList(model, settings);
        return "tv";
    }

    /** The list alone, fetched again by the screen: the songs, the empty list or the party ended. */
    @GetMapping("/{partyCode}/tv/list")
    public String list(@PathVariable String partyCode, Model model) {
        model.addAttribute(PARTY_CODE, partyCode);
        addList(model, party(partyCode));
        return "tv :: tvList";
    }

    private void addList(Model model, PartySettingsEntity settings) {
        model.addAttribute(IS_ACTIVE, settings.isActive());
        if (settings.isActive()) {
            model.addAttribute(TV_SONGS, guestQueueService.mostWanted(settings.getPartyCode()));
        } else {
            // "Prośby od 20:00" while it opens by itself later (V55), in both of the screen's languages
            model.addAttribute(OPENS_AT_PL, partyScheduleService.guestOpens(settings, Locale.forLanguageTag("pl")));
            model.addAttribute(OPENS_AT_EN, partyScheduleService.guestOpens(settings, Locale.ENGLISH));
        }
    }

    private PartySettingsEntity party(String partyCode) {
        try {
            return partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
