package com.scan2play.controller;

import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DjDeviceService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The panel's side of "Program na komputerze" (V78, docs/ideas.md item 19): a code to pair the DJ's program with the party, the
 * party's paired computers and "Odłącz" — for whom may work the queue (the program marks the queue played, as "Zagrane" does).
 * JSON for play-history.js, which draws the card. With Pro (PlanFeature.DECK), but "Odłącz".
 */
@RestController
@RequestMapping(value = "/dj/devices", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DjDeviceController {

    private final DjDeviceService devices;
    private final DjSessionHelper sessionHelper;
    private final PlanService plans;

    /** The party's computers, the one seen last first: {@code [{id, name, createdAt, lastSeenAt}]}. */
    @GetMapping
    public List<DjDeviceService.Device> list(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                                             HttpSession session) {
        return devices.devices(plans.require(sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session),
                PlanFeature.DECK).getPartyCode());
    }

    /** "Połącz komputer": a new code, {@code {"code": "123456", "minutes": "10"}}. */
    @PostMapping("/code")
    public Map<String, String> code(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                                    HttpSession session) {
        String party = plans.require(sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session),
                PlanFeature.DECK).getPartyCode();
        return Map.of("code", devices.newCode(party), "minutes", String.valueOf(DjDeviceService.CODE_MINUTES));
    }

    /** "Odłącz": 204, or 404 for a computer not of this party. Needs no plan: a computer is taken away after Pro ended too. */
    @PostMapping("/remove")
    public ResponseEntity<Void> remove(@RequestParam long id, @RequestParam(required = false) String partyCode,
                                       OAuth2AuthenticationToken authentication, HttpSession session) {
        String party = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        return devices.remove(party, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
