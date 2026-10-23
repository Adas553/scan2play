package com.scan2play.controller;

import com.scan2play.service.SiteCounter;
import com.scan2play.service.SiteCounter.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The site's counter (V40, {@link SiteCounter}) for the owner of the site: the last {@value #DAYS} days, one line a day, as text. Only
 * the account named by {@code scan2play.admin-id} (env {@code ADMIN_GOOGLE_ID}) sees it; anyone else — and everyone, while it is
 * unset — gets a 404, as for a page that is not there.
 */
@RestController
public class StatsController {

    /** How many days the page shows. */
    static final int DAYS = 30;

    private final SiteCounter siteCounter;
    private final String adminId;

    public StatsController(SiteCounter siteCounter, @Value("${scan2play.admin-id:}") String adminId) {
        this.siteCounter = siteCounter;
        this.adminId = adminId == null ? "" : adminId.strip();
    }

    @GetMapping(value = "/dj/stats", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String stats(OAuth2AuthenticationToken authentication) {
        if (adminId.isEmpty() || authentication == null || !adminId.equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return render(siteCounter.lastDays(DAYS));
    }

    /** The days as lines: the date, then each count; a header naming them. */
    static String render(List<SiteCounter.Day> days) {
        StringBuilder text = new StringBuilder("Scan2Play — the last " + DAYS + " days (Polish time), people only, no robots\n\n");
        text.append(String.format("%-10s  %10s  %10s  %6s  %6s  %6s  %14s%n", "day", "landing PL", "landing EN", "start", "login",
                "new DJ", "guest requests"));
        for (SiteCounter.Day day : days) {
            text.append(String.format("%-10s  %10d  %10d  %6d  %6d  %6d  %14d%n", day.day(), day.counts().get(Event.LANDING_PL),
                    day.counts().get(Event.LANDING_EN), day.counts().get(Event.START), day.counts().get(Event.LOGIN),
                    day.counts().get(Event.NEW_DJ), day.counts().get(Event.GUEST_REQUEST)));
        }
        if (days.isEmpty()) {
            text.append("(nothing counted yet)\n");
        }
        return text.toString();
    }
}
