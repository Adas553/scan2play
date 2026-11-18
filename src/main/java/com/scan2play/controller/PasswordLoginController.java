package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.DjAccountEntity;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PasswordAccountService;
import com.scan2play.service.PasswordAccountService.LoginOutcome;
import com.scan2play.service.PasswordAccountService.LoginResult;
import com.scan2play.service.PasswordAccountService.PasswordProblem;
import com.scan2play.service.SiteCounter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The login page — Google's or an e-mail address and a password (V72, {@link PasswordAccountService}) — and the password's pages:
 * a new account, its confirmation, "Nie pamiętam hasła" and the new password. Every page has a form with a CSRF token, so it makes a
 * session before it is rendered ({@code csrf.getToken()}: the trap of the landing page, a page cut after 8 KB).
 * <p>
 * A password account logs in as a Google account does — an {@link OAuth2AuthenticationToken} whose user says {@code sub},
 * {@code name}, {@code email} and {@code email_verified} ({@link #authentication}) — so the rest of the app, which reads those, knows
 * no difference; its id is {@code local:<id>} ({@link DjAccountEntity#userId()}). After the login the same as Google's: a new session
 * id, the DJ's page asked for before it or the panel, the site's counter ({@link SecurityConfig#loginSuccess}).
 */
@Controller
@Slf4j
public class PasswordLoginController {

    /** The registration id of a password account's login (Google's is "google"). */
    public static final String REGISTRATION_ID = "password";
    static final String PANEL = "redirect:/dj/dashboard";

    private final PasswordAccountService accounts;
    private final GuestRequestLimiter clientAddress;
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final AuthenticationSuccessHandler success;

    public PasswordLoginController(PasswordAccountService accounts, GuestRequestLimiter clientAddress, ObjectProvider<SiteCounter> siteCounter,
                                   ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.accounts = accounts;
        this.clientAddress = clientAddress;
        this.sessions = sessions;
        this.success = SecurityConfig.loginSuccess(SecurityConfig.djPagesRemembered(),
                () -> siteCounter.ifAvailable(counter -> counter.count(SiteCounter.Event.LOGIN)));
    }

    private static boolean loggedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }

    // ---------------------------------------------------------------- the login

    @GetMapping("/login")
    public String loginPage(Authentication authentication, Model model, CsrfToken csrf) {
        if (loggedIn(authentication)) {
            return PANEL;
        }
        csrf.getToken();
        return "login";
    }

    @PostMapping("/login/password")
    public String logIn(@RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password, Model model,
                        Locale locale, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Optional<String> address = PasswordAccountService.cleanEmail(email);
        model.addAttribute("email", email.strip());
        if (address.isEmpty()) {
            model.addAttribute("loginError", "account.login.wrong");
            return "login";
        }
        LoginResult result = accounts.logIn(address.get(), password, clientAddress.clientIp(request), locale);
        if (result.outcome() == LoginOutcome.OK) {
            logIn(result.account(), request, response);
            return null;
        }
        model.addAttribute("loginError", result.outcome() == LoginOutcome.LOCKED ? "account.login.locked" : "account.login.wrong");
        return "login";
    }

    // ---------------------------------------------------------------- a new account

    @GetMapping("/register")
    public String registerPage(Authentication authentication, CsrfToken csrf) {
        if (loggedIn(authentication)) {
            return PANEL;
        }
        csrf.getToken();
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String email,
                           @RequestParam(defaultValue = "") String password, Model model, Locale locale, HttpServletRequest request,
                           RedirectAttributes redirect) {
        Optional<String> address = PasswordAccountService.cleanEmail(email);
        PasswordProblem problem = PasswordAccountService.passwordProblem(password);
        if (address.isEmpty() || problem != null) {
            model.addAttribute("name", name.strip());
            model.addAttribute("email", email.strip());
            model.addAttribute("formError", address.isEmpty() ? "account.email.invalid" : passwordKey(problem));
            return "register";
        }
        accounts.requestRegistration(address.get(), password, name, clientAddress.clientIp(request), locale);
        return sent(redirect, "account.sent.register", address.get());
    }

    /** The same answer whatever happened: "Sprawdź skrzynkę" — the e-mail says the rest. */
    private static String sent(RedirectAttributes redirect, String text, String address) {
        redirect.addFlashAttribute("noticeTitle", "account.sent.title");
        redirect.addFlashAttribute("noticeText", text);
        redirect.addFlashAttribute("noticeEmail", address);
        return "redirect:/login/sent";
    }

    @GetMapping("/login/sent")
    public String sentPage(Model model) {
        return model.containsAttribute("noticeTitle") ? "account-notice" : "redirect:/login";
    }

    private static String passwordKey(PasswordProblem problem) {
        return "account.password." + problem.name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- the confirmation (the link of the e-mail)

    /** The link opened: the password asked — the link alone changes nothing (a mail filter may open it). */
    @GetMapping("/register/confirm")
    public String confirmPage(@RequestParam(defaultValue = "") String token, Model model, CsrfToken csrf, HttpServletResponse response) {
        Optional<DjAccountEntity> account = accounts.toConfirm(token);
        if (account.isEmpty()) {
            return oldLink(model, response, "account.confirm.old_link");
        }
        csrf.getToken();
        model.addAttribute("token", token);
        model.addAttribute("email", account.get().getEmail());
        return "account-confirm";
    }

    @PostMapping("/register/confirm")
    public String confirm(@RequestParam(defaultValue = "") String token, @RequestParam(defaultValue = "") String password, Model model,
                          HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Optional<DjAccountEntity> account = accounts.toConfirm(token);
        if (account.isEmpty()) {
            return oldLink(model, response, "account.confirm.old_link");
        }
        LoginResult result = accounts.confirm(token, password, clientAddress.clientIp(request));
        if (result.outcome() == LoginOutcome.OK) {
            logIn(result.account(), request, response);
            return null;
        }
        model.addAttribute("token", token);
        model.addAttribute("email", account.get().getEmail());
        model.addAttribute("formError", result.outcome() == LoginOutcome.LOCKED ? "account.login.locked" : "account.confirm.wrong");
        return "account-confirm";
    }

    private static String oldLink(Model model, HttpServletResponse response, String text) {
        response.setStatus(HttpServletResponse.SC_GONE);
        model.addAttribute("noticeTitle", "account.old_link.title");
        model.addAttribute("noticeText", text);
        return "account-notice";
    }

    // ---------------------------------------------------------------- "Nie pamiętam hasła" and the new password

    @GetMapping("/password/forgot")
    public String forgotPage(CsrfToken csrf) {
        csrf.getToken();
        return "password-forgot";
    }

    @PostMapping("/password/forgot")
    public String forgot(@RequestParam(defaultValue = "") String email, Model model, Locale locale, HttpServletRequest request,
                         RedirectAttributes redirect) {
        Optional<String> address = PasswordAccountService.cleanEmail(email);
        if (address.isEmpty()) {
            model.addAttribute("email", email.strip());
            model.addAttribute("formError", "account.email.invalid");
            return "password-forgot";
        }
        accounts.requestPasswordReset(address.get(), clientAddress.clientIp(request), locale);
        return sent(redirect, "account.sent.forgot", address.get());
    }

    @GetMapping("/password/reset")
    public String resetPage(@RequestParam(defaultValue = "") String token, Model model, CsrfToken csrf, HttpServletResponse response) {
        Optional<DjAccountEntity> account = accounts.toReset(token);
        if (account.isEmpty()) {
            return oldLink(model, response, "account.reset.old_link");
        }
        csrf.getToken();
        model.addAttribute("token", token);
        model.addAttribute("email", account.get().getEmail());
        return "password-reset";
    }

    /** The new password: the account's other sessions end (a stolen session ends with the password), this one is logged in. */
    @PostMapping("/password/reset")
    public String reset(@RequestParam(defaultValue = "") String token, @RequestParam(defaultValue = "") String password, Model model,
                        HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Optional<DjAccountEntity> account = accounts.toReset(token);
        if (account.isEmpty()) {
            return oldLink(model, response, "account.reset.old_link");
        }
        PasswordProblem problem = PasswordAccountService.passwordProblem(password);
        if (problem != null) {
            model.addAttribute("token", token);
            model.addAttribute("email", account.get().getEmail());
            model.addAttribute("formError", passwordKey(problem));
            return "password-reset";
        }
        Optional<DjAccountEntity> changed = accounts.resetPassword(token, password);
        if (changed.isEmpty()) {
            return oldLink(model, response, "account.reset.old_link");
        }
        endSessions(changed.get().userId());
        logIn(changed.get(), request, response);
        return null;
    }

    /** Every session of the account (Spring Session's index of the login's name); none in a test without Spring Session. */
    private void endSessions(String userId) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository != null) {
            repository.findByPrincipalName(userId).keySet().forEach(repository::deleteById);
        }
    }

    // ---------------------------------------------------------------- the login itself

    /**
     * The login of a password account: what Google's login does after Google — a new session id (no session fixation), the context
     * kept in the session, a new CSRF token, then {@link SecurityConfig#loginSuccess} (the page asked for before it, or the panel).
     */
    private void logIn(DjAccountEntity account, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        request.getSession();
        request.changeSessionId();
        request.getSession().removeAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Authentication authentication = authentication(account);
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        log.info("Password account {} logged in", account.userId());
        success.onAuthenticationSuccess(request, response, authentication);
    }

    /** What a Google login's user says, of a password account (the attributes the app reads: {@code sub}, {@code name}, {@code email}). */
    public static OAuth2AuthenticationToken authentication(DjAccountEntity account) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", account.userId());
        attributes.put("email", account.getEmail());
        attributes.put("email_verified", true);
        if (account.getDisplayName() != null) {
            attributes.put("name", account.getDisplayName());
        }
        var authorities = AuthorityUtils.createAuthorityList("OAUTH2_USER");
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities, attributes, "sub"), authorities, REGISTRATION_ID);
    }
}
