package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.SiteCounter;
import com.scan2play.service.SiteCounter.Event;
import com.scan2play.util.Times;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The site's counter (V40, {@link SiteCounter}) for the owner of the site: the last {@value #DAYS} days, one line a day, as text; under
 * it the organisers new in the last {@value #NEW_DAYS} days with their address (V48), to write to them by hand. Only
 * the account named by {@code scan2play.admin-id} (env {@code ADMIN_GOOGLE_ID}) sees it; anyone else — and everyone, while it is
 * unset — gets a 404, as for a page that is not there.
 */
@RestController
public class StatsController {

    /** How many days the page shows. */
    static final int DAYS = 30;
    /** How far back an organiser is new, and how many of them the page lists at most. */
    static final int NEW_DAYS = 7;
    static final int NEW_MAX = 50;

    private final SiteCounter siteCounter;
    private final PartySettingsRepository partySettingsRepository;
    private final String adminId;

    public StatsController(SiteCounter siteCounter, PartySettingsRepository partySettingsRepository,
                           @Value("${scan2play.admin-id:}") String adminId) {
        this.siteCounter = siteCounter;
        this.partySettingsRepository = partySettingsRepository;
        this.adminId = adminId == null ? "" : adminId.strip();
    }

    @GetMapping(value = "/dj/stats", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String stats(OAuth2AuthenticationToken authentication) {
        if (adminId.isEmpty() || authentication == null || !adminId.equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Instant since = Instant.now().minus(Duration.ofDays(NEW_DAYS));
        return render(siteCounter.lastDays(DAYS)) + "\n" + renderNew(partySettingsRepository.newOrganisers(since, Limit.of(NEW_MAX)));
    }

    /** The new organisers, one line each: when the account came (Polish time), the party's code, the address, the name. */
    static String renderNew(List<PartySettingsEntity> organisers) {
        StringBuilder text = new StringBuilder("New organisers — the last " + NEW_DAYS + " days, the newest first (only to write to them"
                + " about their account and help; the message: docs/welcome-email.md)\n\n");
        for (PartySettingsEntity organiser : organisers) {
            text.append(String.format("%-19s  %-5s  %s  %s%n", Times.display(organiser.getCreatedAt()), organiser.getPartyCode(),
                    organiser.getOwnerEmail(), organiser.getOwnerName() == null ? "" : organiser.getOwnerName()));
        }
        if (organisers.isEmpty()) {
            text.append("(nobody new)\n");
        }
        return text.toString();
    }

    /** The days as lines: the date, then each count; a header naming them. */
    static String render(List<SiteCounter.Day> days) {
        StringBuilder text = new StringBuilder("Scan2Play — the last " + DAYS + " days (Polish time), people only, no robots\n\n");
        text.append(String.format("%-10s  %10s  %10s  %6s  %6s  %6s  %14s  %6s%n", "day", "landing PL", "landing EN", "start", "login",
                "new DJ", "guest requests", "demo"));
        for (SiteCounter.Day day : days) {
            text.append(String.format("%-10s  %10d  %10d  %6d  %6d  %6d  %14d  %6d%n", day.day(), day.counts().get(Event.LANDING_PL),
                    day.counts().get(Event.LANDING_EN), day.counts().get(Event.START), day.counts().get(Event.LOGIN),
                    day.counts().get(Event.NEW_DJ), day.counts().get(Event.GUEST_REQUEST), day.counts().get(Event.DEMO)));
        }
        if (days.isEmpty()) {
            text.append("(nothing counted yet)\n");
        }
        return text.toString();
    }
}
