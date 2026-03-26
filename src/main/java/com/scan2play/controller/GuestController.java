package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static com.scan2play.controller.ViewAttributes.*;

@Controller
@RequestMapping("/p")
@RequiredArgsConstructor
@Slf4j
public class GuestController {

    private final DjService djService;
    private final PartySettingsQueryService partySettingsQueryService;

    @GetMapping("/{partyCode}")
    public String partyIndex(@PathVariable String partyCode, Model model) {
        try {
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
            
            if (!settings.isActive()) {
                return "party_ended";
            }
            
            model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
            model.addAttribute(PUBLIC_QUEUE, djService.getPublicQueue(partyCode));
            model.addAttribute(PARTY_CODE, partyCode);
            return "index";
        } catch (IllegalArgumentException e) {
            log.warn("Invalid party code access attempt: {}", partyCode);
            return "error/404"; 
        }
    }

    @PostMapping("/{partyCode}/request")
    public String requestSong(@PathVariable String partyCode,
                              @RequestParam String songName,
                              @RequestParam(defaultValue = "90s Rock") String style,
                              Model model) {
        
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        if (!settings.isActive()) {
             throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Party has ended");
        }

        DjResponse response = djService.evaluateAndSaveSong(partyCode, songName, style);
        model.addAttribute(RESPONSE, response);
        model.addAttribute(PARTY_CODE, partyCode);
        return "result";
    }
}
