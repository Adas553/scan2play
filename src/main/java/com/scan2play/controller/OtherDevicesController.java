package com.scan2play.controller;

import com.scan2play.service.AccountSessions;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * "Wyloguj na innych urządzeniach" of the account menu: every other session of the logged-in account ends, this one stays. A
 * session may live 14 days without a request ("Nie wylogowuj mnie na tym urządzeniu", StayLoggedIn), and a Google account has no
 * password of ours to change — without this a lost phone or a club's computer left logged in would stay so until the account is
 * deleted.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OtherDevicesController {

    private final AccountSessions sessions;

    @PostMapping("/dj/logout-others")
    public String logOutOthers(Authentication authentication, HttpSession session) {
        sessions.endAllBut(authentication.getName(), session.getId());
        log.info("Account {}: the other devices logged out", authentication.getName());
        return "redirect:/dj/dashboard";
    }
}
