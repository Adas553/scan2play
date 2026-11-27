package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DeckSetService;
import com.scan2play.service.DeckWatchService;
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
 * JSON for play-history.js, which draws the card. With Pro (PlanFeature.DECK), but "Odłącz". Each computer's line says whether its
 * program sees songs, and "Ostrzeż po" sets when the organiser is warned that it stopped (DeckWatchService, V81); "Zapisuj set
 * wieczoru" whether the night's set is kept (DeckSetService, V83).
 */
@RestController
@RequestMapping(value = "/dj/devices", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DjDeviceController {

    private final DjDeviceService devices;
    private final DeckWatchService watch;
    private final DjSessionHelper sessionHelper;
    private final PlanService plans;
    private final DeckSetService deckSet;

    /** The card's content: the computers, the minutes of "Ostrzeż po" and "Zapisuj set wieczoru" (V83). */
    public record Card(List<DeckWatchService.Computer> computers, int silenceMinutes, boolean setKept) {
    }

    /**
     * The party's computers, the one seen last first, with their state, and the minutes of "Ostrzeż po": {@code {"computers": [{id,
     * name, createdAt, lastSeenAt, lastPlayAt, program, state, warn}], "silenceMinutes": 20}}.
     */
    @GetMapping
    public Card list(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                     HttpSession session) {
        PartySettingsEntity party = plans.require(sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session),
                PlanFeature.DECK);
        DeckWatchService.Watch seen = watch.watch(party);
        return new Card(seen.computers(), seen.silenceMinutes(), deckSet.kept(party.getPartyCode()));
    }

    /** "Zapisuj set wieczoru" (V83): on or off — 204; off deletes the party's kept set ({@link DeckSetService#setKept}). */
    @PostMapping("/set")
    public ResponseEntity<Void> set(@RequestParam boolean kept, @RequestParam(required = false) String partyCode,
                                    OAuth2AuthenticationToken authentication, HttpSession session) {
        String party = plans.require(sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session),
                PlanFeature.DECK).getPartyCode();
        deckSet.setKept(party, kept);
        return ResponseEntity.noContent().build();
    }

    /** "Ostrzeż po": 0 (never), 10, 20 or 30 minutes — 204; any other value 400. */
    @PostMapping("/silence")
    public ResponseEntity<Void> silence(@RequestParam int minutes, @RequestParam(required = false) String partyCode,
                                        OAuth2AuthenticationToken authentication, HttpSession session) {
        String party = plans.require(sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session),
                PlanFeature.DECK).getPartyCode();
        return watch.setSilenceMinutes(party, minutes) ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
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
