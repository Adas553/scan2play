package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Nie wysyłaj mi tego" in the morning summary's e-mail (V64) and "Nie wysyłaj mi nowości" in the news e-mail ({@code ?what=news},
 * V80), without a login: the secret of the address ({@code email_token}) says
 * whose. The link ({@code GET}) only asks — mail filters open the links of an e-mail to check them, and a link that switched the
 * summary off would switch it off for every DJ behind such a filter, unseen. Its "Tak, wyłącz" posts to the same address, and so does
 * the mail program's own "Unsubscribe" ({@code List-Unsubscribe-Post}, RFC 8058): that {@code POST} switches "Poranne podsumowanie"
 * off. The path is free of CSRF (a mail program has no token), and the question's form carries none — a token would make a session for
 * every link opened. An unknown secret is a 404, as for a page that is not there.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class EmailStopController {

    /** {@code what} of the news e-mail's link (V80): its e-mails, not the morning summary's. */
    static final String NEWS = "news";

    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsCommandService partySettingsCommandService;

    /**
     * The question, "Wyłączyć poranne podsumowanie?" — or, with {@code what=news}, "Nie wysyłać Ci nowości?" (the news e-mail's link,
     * V80); e-mails already off — the page that says they are. Changes nothing.
     */
    @GetMapping("/email/stop/{token}")
    public String ask(@PathVariable String token, @RequestParam(required = false) String what, Model model) {
        PartySettingsEntity party = find(token);
        boolean news = NEWS.equals(what);
        model.addAttribute(ViewAttributes.NEWS, news);
        return (news ? party.isNewsEmails() : party.isMorningSummary()) ? "email-stop" : "email-stopped";
    }

    /**
     * The answer "Tak, wyłącz", or the mail program's one click: the morning summaries off — or, with {@code what=news}, the news
     * e-mails (V80, with the time: the consent taken back). Once: again, nothing is written.
     */
    @PostMapping("/email/stop/{token}")
    public String stop(@PathVariable String token, @RequestParam(required = false) String what, Model model) {
        PartySettingsEntity party = find(token);
        boolean news = NEWS.equals(what);
        model.addAttribute(ViewAttributes.NEWS, news);
        if (news && party.isNewsEmails()) {
            partySettingsCommandService.setNewsEmails(party.getPartyCode(), false);
            log.info("Party [{}]: the news e-mails switched off from an e-mail", party.getPartyCode());
        } else if (!news && party.isMorningSummary()) {
            partySettingsCommandService.updateSettings(party.getPartyCode(), s -> s.setMorningSummary(false));
            log.info("Party [{}]: the morning summaries switched off from an e-mail", party.getPartyCode());
        }
        return "email-stopped";
    }

    private PartySettingsEntity find(String token) {
        if (token == null || token.length() != 32) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return partySettingsRepository.findByEmailToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
