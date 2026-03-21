package com.scan2play.controller;

import com.scan2play.service.SpotifyAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

@Controller
@RequiredArgsConstructor
public class SpotifyAuthController {

    private final SpotifyAuthService spotifyAuthService;

    @GetMapping("/spotify/login")
    public String spotifyLogin(@RequestParam("partyCode") String partyCode) {
        String authorizationUrl = spotifyAuthService.getAuthorizationUrl(partyCode);
        return "redirect:" + authorizationUrl;
    }

    @GetMapping("/spotify/callback")
    public String spotifyCallback(@RequestParam("code") String code, @RequestParam("state") String partyCode) throws IOException {
        spotifyAuthService.exchangeCodeForToken(code, partyCode);
        return "redirect:dj/dashboard";
    }
}
