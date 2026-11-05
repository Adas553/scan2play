package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Nie wysyłaj mi tego" in the morning summary's e-mail (V64), without a login: the secret of the address ({@code email_token}) says
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

    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsCommandService partySettingsCommandService;

    /** The question, "Wyłączyć poranne podsumowanie?"; summaries already off — the page that says they are. Changes nothing. */
    @GetMapping("/email/stop/{token}")
    public String ask(@PathVariable String token) {
        return find(token).isMorningSummary() ? "email-stop" : "email-stopped";
    }

    /** The answer "Tak, wyłącz", or the mail program's one click: the morning summaries off (once — again, nothing is written). */
    @PostMapping("/email/stop/{token}")
    public String stop(@PathVariable String token) {
        PartySettingsEntity party = find(token);
        if (party.isMorningSummary()) {
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
