package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.config.StayLoggedIn;
import com.scan2play.entity.DjAccountEntity;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PasswordAccountService;
import com.scan2play.service.PasswordAccountService.LoginOutcome;
import com.scan2play.service.PasswordAccountService.LoginResult;
import com.scan2play.service.SiteCounter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login page and the password's pages (V72) on a first visit — no session yet — through Spring Session's filter (sessions in
 * memory, as the JDBC ones in production; the session is the {@code SESSION} cookie): each page whole, with its form's CSRF token and
 * its footer (CLAUDE.md's trap (1): the token is kept in the session, which can be made only before the response
 * is committed). And the login itself: a new session — a new cookie — that is logged in, the old one not.
 */
@WebMvcTest(PasswordLoginController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, GuestRequestLimiter.class, PasswordLoginSessionWebTest.Sessions.class})
class PasswordLoginSessionWebTest {

    @TestConfiguration
    static class Sessions {
        /** The sessions, to read a session's life after the login. */
        static final Map<String, Session> STORE = new ConcurrentHashMap<>();

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 50)
        SessionRepositoryFilter<MapSession> springSessionRepositoryFilter() {
            MapSessionRepository repository = new MapSessionRepository(STORE);
            repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
            SessionRepositoryFilter<MapSession> filter = new SessionRepositoryFilter<>(repository);
            // the cookie as Spring Boot's serializer writes it in production: persistent after a remember-me login (StayLoggedInIT
            // checks Boot's own)
            DefaultCookieSerializer cookies = new DefaultCookieSerializer();
            cookies.setRememberMeRequestAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);
            CookieHttpSessionIdResolver resolver = new CookieHttpSessionIdResolver();
            resolver.setCookieSerializer(cookies);
            filter.setHttpSessionIdResolver(resolver);
            return filter;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordAccountService accounts;
    @MockitoBean
    private SiteCounter siteCounter;
    @MockitoBean
    private com.scan2play.service.AccountSessions accountSessions;

    private static DjAccountEntity account() {
        return DjAccountEntity.builder().id(7L).email("ola@example.com").passwordHash("{bcrypt}x").createdAt(Instant.EPOCH)
                .emailVerifiedAt(Instant.EPOCH).build();
    }

    @Test
    void theLoginPage_onAFirstVisit_isWhole_withGooglesButtonAndThePasswordsFormAndItsToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/login").header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn();

        String page = WishWebTest.html(result);
        WishWebTest.csrf(page);
        assertThat(page).contains("formaction=\"/login/google\"", "Zaloguj się przez Google", "id=\"loginForm\"",
                "action=\"/login/password\"", "name=\"remember-me\"", "Nie wylogowuj mnie na tym urządzeniu", "href=\"/register\"", "href=\"/password/forgot\"", "<footer", "</html>");
        WishWebTest.write("login.html", page);
    }

    @Test
    void everyPasswordsPage_onAFirstVisit_isWhole_withItsFormsToken() throws Exception {
        when(accounts.toConfirm("good")).thenReturn(Optional.of(account()));
        when(accounts.toReset("good")).thenReturn(Optional.of(account()));
        String[][] pages = {{"/register", "register.html"}, {"/password/forgot", "password-forgot.html"},
                {"/register/confirm?token=good", "account-confirm.html"}, {"/password/reset?token=good", "password-reset.html"}};
        for (String[] page : pages) {
            String html = WishWebTest.html(mockMvc.perform(get(page[0]).header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn());
            WishWebTest.csrf(html);
            assertThat(html).as(page[0]).contains("<footer", "</html>");
            WishWebTest.write(page[1], html);
        }
    }

    @Test
    void theLogin_givesANewSession_thatIsLoggedIn() throws Exception {
        when(accounts.logIn(anyString(), anyString(), any(), any())).thenReturn(new LoginResult(LoginOutcome.OK, account()));
        MvcResult page = mockMvc.perform(get("/login")).andReturn();
        Cookie before = page.getResponse().getCookie("SESSION");
        assertThat(before).as("the page made its session").isNotNull();

        MvcResult login = mockMvc.perform(post("/login/password").cookie(before).param("_csrf", WishWebTest.csrf(WishWebTest.html(page)))
                        .param("email", "ola@example.com").param("password", "dobre-haslo-1"))
                .andExpect(redirectedUrl("/dj/dashboard")).andReturn();
        Cookie after = login.getResponse().getCookie("SESSION");

        assertThat(after).isNotNull();
        assertThat(after.getValue()).as("a new session id").isNotEqualTo(before.getValue());
        mockMvc.perform(get("/login").cookie(after)).andExpect(redirectedUrl("/dj/dashboard"));   // logged in
        mockMvc.perform(get("/login").cookie(before)).andExpect(status().isOk());                  // the old id is nobody's
    }

    // ------------------------------------------------ "Nie wylogowuj mnie na tym urządzeniu" (StayLoggedIn)

    /** The session a cookie names. */
    private static Session session(Cookie cookie) {
        return Sessions.STORE.get(new String(Base64.getDecoder().decode(cookie.getValue())));
    }

    private static String sessionCookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream().filter(header -> header.startsWith("SESSION=")).findFirst()
                .orElseThrow();
    }

    private MvcResult passwordLogin(boolean stay) throws Exception {
        when(accounts.logIn(anyString(), anyString(), any(), any())).thenReturn(new LoginResult(LoginOutcome.OK, account()));
        MvcResult page = mockMvc.perform(get("/login").secure(true)).andReturn();
        var login = post("/login/password").secure(true).cookie(page.getResponse().getCookie("SESSION"))
                .param("_csrf", WishWebTest.csrf(WishWebTest.html(page))).param("email", "ola@example.com").param("password", "dobre-haslo-1");
        if (stay) {
            login.param("remember-me", "true");
        }
        return mockMvc.perform(login).andExpect(redirectedUrl("/dj/dashboard")).andReturn();
    }

    @Test
    void theLogin_withTheBox_lives14Days_withAPersistentCookie() throws Exception {
        MvcResult login = passwordLogin(true);

        assertThat(session(login.getResponse().getCookie("SESSION")).getMaxInactiveInterval()).isEqualTo(Duration.ofDays(14));
        assertThat(sessionCookie(login)).contains("Max-Age=" + Integer.MAX_VALUE, "Secure", "HttpOnly", "SameSite=Lax");
    }

    /** The box clear (the default): the session and its cookie as before the box — 30 minutes, a cookie of the browser's session. */
    @Test
    void theLogin_withoutTheBox_isAsBefore() throws Exception {
        MvcResult login = passwordLogin(false);

        assertThat(session(login.getResponse().getCookie("SESSION")).getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(sessionCookie(login)).doesNotContain("Max-Age", "Expires").contains("Secure", "HttpOnly", "SameSite=Lax");
    }

    /** The login page's box is clear until ticked. */
    @Test
    void theBox_isClearOnTheLoginPage() throws Exception {
        String page = WishWebTest.html(mockMvc.perform(get("/login")).andExpect(status().isOk()).andReturn());
        String box = page.substring(page.indexOf("<input type=\"checkbox\""), page.indexOf(">", page.indexOf("<input type=\"checkbox\"")));

        assertThat(box).contains("name=\"remember-me\"", "value=\"true\"").doesNotContain("checked");
    }

    /** The Google button: the box's state kept in the session, then Google's login — with the form's CSRF token only. */
    @Test
    void googlesButton_keepsTheBox_thenGoogle() throws Exception {
        MvcResult page = mockMvc.perform(get("/login")).andReturn();
        Cookie cookie = page.getResponse().getCookie("SESSION");
        String csrf = WishWebTest.csrf(WishWebTest.html(page));

        mockMvc.perform(post("/login/google").cookie(cookie).param("remember-me", "true")).andExpect(status().isForbidden());
        assertThat(session(cookie).<Object>getAttribute(StayLoggedIn.class.getName() + ".CHOSEN_FOR_GOOGLE")).as("refused, nothing kept")
                .isNull();

        mockMvc.perform(post("/login/google").cookie(cookie).param("_csrf", csrf).param("remember-me", "true"))
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
        assertThat(session(cookie).<Object>getAttribute(StayLoggedIn.class.getName() + ".CHOSEN_FOR_GOOGLE")).isEqualTo(Boolean.TRUE);
        assertThat(session(cookie).getMaxInactiveInterval()).as("no login yet: the session as it was").isEqualTo(Duration.ofMinutes(30));

        mockMvc.perform(post("/login/google").cookie(cookie).param("_csrf", csrf))
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
        assertThat(session(cookie).<Object>getAttribute(StayLoggedIn.class.getName() + ".CHOSEN_FOR_GOOGLE")).isNull();
    }
}
