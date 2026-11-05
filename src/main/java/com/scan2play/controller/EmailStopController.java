package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * "Nie wysyłaj mi tego" in every automatic e-mail (V64): one click on the link ({@code GET}) — or the mail program's own
 * "Unsubscribe" ({@code POST}, {@code List-Unsubscribe-Post}, RFC 8058) — switches "Poranne podsumowanie" off, without a login: the
 * secret of the address ({@code email_token}, 128 bits) says whose. A mail filter that opens links to check them switches it off too;
 * the page says where to switch it on again. An unknown secret is a 404, as for a page that is not there.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class EmailStopController {

    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsCommandService partySettingsCommandService;

    @GetMapping("/email/stop/{token}")
    public String stop(@PathVariable String token) {
        switchOff(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return "email-stopped";
    }

    @PostMapping("/email/stop/{token}")
    public ResponseEntity<Void> stopByMailProgram(@PathVariable String token) {
        return switchOff(token).isPresent() ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    private Optional<PartySettingsEntity> switchOff(String token) {
        if (token == null || token.length() != 32) {
            return Optional.empty();
        }
        Optional<PartySettingsEntity> party = partySettingsRepository.findByEmailToken(token);
        party.filter(PartySettingsEntity::isMorningSummary).ifPresent(found -> {
            partySettingsCommandService.updateSettings(found.getPartyCode(), s -> s.setMorningSummary(false));
            log.info("Party [{}]: the morning summaries switched off from an e-mail", found.getPartyCode());
        });
        return party;
    }
}
