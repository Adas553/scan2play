package com.scan2play.controller;

import com.scan2play.model.PanelNews;
import com.scan2play.service.AdminReportService;
import com.scan2play.service.Mailer;
import com.scan2play.service.NewsEmailService;
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
 * The news e-mail by hand ({@link NewsEmailService}, V80): {@code GET /dj/stats/news} shows the news now, how many organisers said yes
 * to it and how many of them have had it, the e-mail's words in Polish and English; "Wyślij próbę do mnie" sends it to the owner's
 * address ({@code ADMIN_EMAIL}) to see it as a DJ will; "Wyślij" sends it to the organisers it is due to. As {@code /dj/stats}: only
 * the account named by {@code scan2play.admin-id}; anyone else, and everyone while it is unset, a 404.
 */
@Controller
public class AdminNewsController {

    private final NewsEmailService newsEmailService;
    private final AdminReportService adminReportService;
    private final Mailer mailer;
    private final String adminId;

    public AdminNewsController(NewsEmailService newsEmailService, AdminReportService adminReportService, Mailer mailer,
                               @Value("${scan2play.admin-id:}") String adminId) {
        this.newsEmailService = newsEmailService;
        this.adminReportService = adminReportService;
        this.mailer = mailer;
        this.adminId = adminId == null ? "" : adminId.strip();
    }

    @GetMapping("/dj/stats/news")
    public String page(@RequestParam(required = false) String done, OAuth2AuthenticationToken authentication, Model model) {
        requireOwner(authentication);
        model.addAttribute("number", PanelNews.NUMBER);
        model.addAttribute("counts", newsEmailService.counts());
        model.addAttribute("sending", newsEmailService.isSending());
        model.addAttribute("canSend", mailer.isOn());
        model.addAttribute("address", adminReportService.address());
        model.addAttribute("textPl", newsEmailService.previewText("pl"));
        model.addAttribute("textEn", newsEmailService.previewText("en"));
        model.addAttribute("done", done);
        return "stats-news";
    }

    /** The e-mail in {@code lang} to the owner's own address, as a DJ will get it (without a working "Nie wysyłaj mi nowości"). */
    @PostMapping("/dj/stats/news/try")
    public String sendTry(@RequestParam(defaultValue = "pl") String lang, OAuth2AuthenticationToken authentication) {
        requireOwner(authentication);
        boolean sent = newsEmailService.sendTry(adminReportService.address(), "en".equals(lang) ? "en" : "pl");
        return "redirect:/dj/stats/news?done=" + (sent ? "try" : "try-failed");
    }

    /** The news to every organiser who said yes and has not had it; the page says how many went once it is reloaded. */
    @PostMapping("/dj/stats/news/send")
    public String send(OAuth2AuthenticationToken authentication) {
        requireOwner(authentication);
        return "redirect:/dj/stats/news?done=" + (newsEmailService.sendLater() ? "sending" : "not-sent");
    }

    private void requireOwner(OAuth2AuthenticationToken authentication) {
        if (adminId.isEmpty() || authentication == null || !adminId.equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
