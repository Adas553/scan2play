package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.util.SongList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * The hosts' page (V29): the couple at a wedding, the host of a party or the pub's owner fill in the songs not to play and the
 * songs to play, without an account — the DJ gives them the link {@code /h/{token}} (a secret of 128 bits, made and taken away in
 * the DJ's panel). An unknown or old secret is a 404: nothing tells it from a page that never was.
 */
@Controller
@RequestMapping("/h")
@RequiredArgsConstructor
@Slf4j
public class HostController {

    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsCommandService partySettingsCommandService;

    @GetMapping("/{token}")
    public String hostLists(@PathVariable String token, Model model) {
        PartySettingsEntity settings = partyOf(token);
        model.addAttribute(HOST_TOKEN, token);
        model.addAttribute(DJ_NAME, settings.getDjName());
        model.addAttribute(HOST_BLOCKED, settings.getHostBlocked());
        model.addAttribute(HOST_WANTED, settings.getHostWanted());
        return "host";
    }

    @PostMapping("/{token}")
    public String saveHostLists(@PathVariable String token, @RequestParam(required = false) String blocked,
                                @RequestParam(required = false) String wanted, RedirectAttributes redirectAttributes) {
        PartySettingsEntity settings = partyOf(token);
        String blockedList = SongList.tidy(blocked);
        String wantedList = SongList.tidy(wanted);
        partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> {
            s.setHostBlocked(blockedList);
            s.setHostWanted(wantedList);
        });
        log.info("Party [{}]: the hosts saved their lists", settings.getPartyCode());
        redirectAttributes.addFlashAttribute(HOST_SAVED, true);
        return "redirect:/h/" + token;
    }

    private PartySettingsEntity partyOf(String token) {
        if (token == null || token.length() > 32) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return partySettingsRepository.findByHostToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
