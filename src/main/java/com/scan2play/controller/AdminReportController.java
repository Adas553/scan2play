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

/**
 * The owner's daily report ({@link AdminReportService}) by hand: {@code GET /dj/stats/report} shows it as it would go now, the button
 * sends it at once. As {@code /dj/stats}: only the account named by {@code scan2play.admin-id}; anyone else, and everyone while it is
 * unset, a 404.
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
    public String report(@RequestParam(required = false) String sent, OAuth2AuthenticationToken authentication, Model model) {
        requireOwner(authentication);
        AdminReportService.Report report = adminReportService.preview();
        model.addAttribute("subject", report.subject());
        model.addAttribute("text", report.text());
        model.addAttribute("address", adminReportService.address());
        model.addAttribute("canSend", adminReportService.canSend());
        model.addAttribute("sent", sent);
        return "stats-report";
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
