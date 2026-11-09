package com.scan2play.controller;

import com.scan2play.service.AdminReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The owner's daily report ({@link AdminReportService}) by hand: {@code GET /dj/stats/report} shows it as it would go now, the button
 * sends it at once, and the form sets when it goes by itself (V66). As {@code /dj/stats}: only the account named by
 * {@code scan2play.admin-id}; anyone else, and everyone while it is unset, a 404.
 */
@Controller
public class AdminReportController {

    private final AdminReportService adminReportService;
    private final String adminId;

    public AdminReportController(AdminReportService adminReportService, @Value("${scan2play.admin-id:}") String adminId) {
        this.adminReportService = adminReportService;
        this.adminId = adminId == null ? "" : adminId.strip();
    }

    @GetMapping("/dj/stats/report")
    public String report(@RequestParam(required = false) String sent, @RequestParam(required = false) String saved,
                         OAuth2AuthenticationToken authentication, Model model) {
        requireOwner(authentication);
        AdminReportService.Report report = adminReportService.preview();
        model.addAttribute("subject", report.subject());
        model.addAttribute("text", report.text());
        model.addAttribute("view", report.view());
        model.addAttribute("address", adminReportService.address());
        model.addAttribute("canSend", adminReportService.canSend());
        model.addAttribute("sent", sent);
        model.addAttribute("saved", saved);
        model.addAttribute("schedule", adminReportService.schedule());
        model.addAttribute("weekDays", WEEK_DAYS);
        return "stats-report";
    }

    /** The days of the schedule's form, Monday first, with their Polish short names. */
    public record WeekDay(DayOfWeek day, String name) {
    }

    static final List<WeekDay> WEEK_DAYS = Arrays.stream(DayOfWeek.values())
            .map(day -> new WeekDay(day, day.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("pl")))).toList();

    /**
     * When the report goes (V66): on or off, the time ("HH:mm", Polish time) and the days. A time that is not one is a 400; no day
     * ticked keeps it on paper but sends nothing.
     */
    @PostMapping("/dj/stats/report/schedule")
    public String schedule(@RequestParam(defaultValue = "false") boolean on, @RequestParam String time,
                           @RequestParam(required = false) List<DayOfWeek> days, OAuth2AuthenticationToken authentication) {
        requireOwner(authentication);
        LocalTime at;
        try {
            at = LocalTime.parse(time.strip());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "time");
        }
        adminReportService.saveSchedule(new AdminReportService.Schedule(on, at, days == null ? Set.of() : Set.copyOf(days)));
        return "redirect:/dj/stats/report?saved=yes";
    }

    @PostMapping("/dj/stats/report")
    public String send(OAuth2AuthenticationToken authentication) {
        requireOwner(authentication);
        return "redirect:/dj/stats/report?sent=" + (adminReportService.sendNow() ? "yes" : "no");
    }

    private void requireOwner(OAuth2AuthenticationToken authentication) {
        if (adminId.isEmpty() || authentication == null || !adminId.equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
