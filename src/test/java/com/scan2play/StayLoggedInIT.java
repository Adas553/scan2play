package com.scan2play;

import com.scan2play.config.SecurityConfig;
import com.scan2play.config.StayLoggedIn;
import com.scan2play.entity.DjAccountEntity;
import com.scan2play.repository.DjAccountRepository;
import com.scan2play.service.AccountSessions;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Nie wylogowuj mnie na tym urządzeniu" on the application as it runs — Spring Session's JDBC sessions (V11), Spring Boot's cookie
 * serializer, the security filters —: the session's own {@code MAX_INACTIVE_INTERVAL} written to {@code SPRING_SESSION} (14 days,
 * else the usual 30 minutes), the cookie persistent only then and otherwise the same as the usual one; a long session ends with the
 * account's sessions (a new password, a deleted account) as any other.
 * <p>
 * The cookie's {@code Secure}, {@code HttpOnly} and {@code SameSite} themselves are not seen here: without an embedded server (this
 * test's mock servlet context) Spring Boot configures the serializer from the servlet context's cookie settings, all off, where the
 * running app (embedded Tomcat) keeps the serializer's own — {@code Secure} by the request, {@code HttpOnly}, {@code SameSite=Lax},
 * as production sends them ({@code curl /demo}, 2026-11-18). {@code PasswordLoginSessionWebTest} checks them with that serializer.
 */
class StayLoggedInIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "dobre-haslo-1";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Autowired WebApplicationContext context;
    @Autowired DjAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountSessions accountSessions;

    private MockMvc mockMvc;
    private DjAccountEntity account;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                // as the servlet container orders them: Spring Session's filter, then Spring Security's
                .addFilters(context.getBean("springSessionRepositoryFilter", Filter.class),
                        context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        account = accounts.saveAndFlush(DjAccountEntity.builder().email("stay-" + UUID.randomUUID() + "@example.com")
                .passwordHash(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD))
                .createdAt(Instant.now()).emailVerifiedAt(Instant.now()).build());
    }

    /** The login page's session and its form's CSRF token. */
    private record Page(Cookie session, String csrf) {
    }

    private Page loginPage() throws Exception {
        MvcResult page = mockMvc.perform(get("/login").secure(true)).andExpect(status().isOk()).andReturn();
        Matcher csrf = CSRF.matcher(page.getResponse().getContentAsString());
        assertThat(csrf.find()).as("the form's token").isTrue();
        return new Page(page.getResponse().getCookie("SESSION"), csrf.group(1));
    }

    private MockHttpServletResponse logIn(boolean stay) throws Exception {
        Page page = loginPage();
        var login = post("/login/password").secure(true).cookie(page.session()).param("_csrf", page.csrf())
                .param("email", account.getEmail()).param("password", PASSWORD);
        if (stay) {
            login.param(StayLoggedIn.PARAMETER, "true");
        }
        return mockMvc.perform(login).andExpect(redirectedUrl("/dj/dashboard")).andReturn().getResponse();
    }

    /** The account's session row as the database keeps it. */
    private Map<String, Object> sessionRow() {
        return jdbc.queryForMap("SELECT max_inactive_interval, expiry_time, last_access_time FROM spring_session WHERE principal_name = ?",
                account.userId());
    }

    private static String sessionCookie(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith("SESSION=")).findFirst().orElseThrow();
    }

    /** A cookie's attributes but its value and its life. */
    private static String attributes(String cookie) {
        return cookie.replaceFirst("^SESSION=[^;]*", "").replaceAll("; (Max-Age|Expires)=[^;]*", "");
    }

    @Test
    void theBoxTicked_theSessionLives14DaysInTheDatabase_andItsCookieOutlivesTheBrowser() throws Exception {
        MockHttpServletResponse response = logIn(true);

        Map<String, Object> row = sessionRow();
        assertThat(((Number) row.get("max_inactive_interval")).intValue()).isEqualTo(14 * 24 * 60 * 60);
        assertThat(((Number) row.get("expiry_time")).longValue() - ((Number) row.get("last_access_time")).longValue())
                .isEqualTo(14L * 24 * 60 * 60 * 1000);
        String cookie = sessionCookie(response);
        assertThat(cookie).contains("Max-Age=" + Integer.MAX_VALUE, "Expires=");
        assertThat(attributes(cookie)).as("else as the usual session's cookie").isEqualTo(attributes(sessionCookie(logInAnother(false))));
    }

    /** Another account's login, for its cookie. */
    private MockHttpServletResponse logInAnother(boolean stay) throws Exception {
        account = accounts.saveAndFlush(DjAccountEntity.builder().email("stay-" + UUID.randomUUID() + "@example.com")
                .passwordHash(account.getPasswordHash()).createdAt(Instant.now()).emailVerifiedAt(Instant.now()).build());
        return logIn(stay);
    }

    @Test
    void theBoxClear_theSessionIsTheUsualOne_andItsCookieEndsWithTheBrowser() throws Exception {
        MockHttpServletResponse response = logIn(false);

        Map<String, Object> row = sessionRow();
        assertThat(((Number) row.get("max_inactive_interval")).intValue()).isEqualTo(30 * 60);
        String cookie = sessionCookie(response);
        assertThat(cookie).doesNotContain("Max-Age", "Expires").contains("Path=/");
    }

    /** A new password or a deleted account ends every session of the account (AccountSessions) — a long one too. */
    @Test
    void aLongSession_endsWithTheAccountsSessions() throws Exception {
        logIn(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, account.userId()))
                .isEqualTo(1);

        accountSessions.endAll(account.userId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, account.userId()))
                .isZero();
    }

    /** "Zaloguj się przez Google" with the box: the choice kept in the session (the database's) for Google's return, then Google. */
    @Test
    void googlesButton_withTheBox_keepsTheChoice_andGoesToGoogle() throws Exception {
        Page page = loginPage();

        MockHttpServletResponse response = mockMvc.perform(post("/login/google").secure(true).cookie(page.session())
                        .param("_csrf", page.csrf()).param(StayLoggedIn.PARAMETER, "true"))
                .andExpect(redirectedUrl(SecurityConfig.GOOGLE_LOGIN)).andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie")).as("the anonymous session's cookie as it was").isEmpty();
        String id = new String(Base64.getDecoder().decode(page.session().getValue()), StandardCharsets.UTF_8);
        assertThat(jdbc.queryForList("SELECT a.attribute_name FROM spring_session_attributes a JOIN spring_session s ON s.primary_id = "
                + "a.session_primary_id WHERE s.session_id = ?", String.class, id))
                .contains(StayLoggedIn.class.getName() + ".CHOSEN_FOR_GOOGLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, account.userId()))
                .as("no login by the button").isZero();
    }

    /** Without the form's CSRF token another site cannot make a browser's next Google login a long one. */
    @Test
    void googlesButton_withoutTheFormsToken_isRefused() throws Exception {
        Page page = loginPage();

        mockMvc.perform(post("/login/google").secure(true).cookie(page.session()).param(StayLoggedIn.PARAMETER, "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theLongSession_keepsItsLifeAtTheNextRequest() throws Exception {
        MockHttpServletResponse response = logIn(true);
        Cookie session = response.getCookie("SESSION");

        mockMvc.perform(get("/login").secure(true).cookie(session)).andExpect(redirectedUrl("/dj/dashboard"));

        assertThat(((Number) sessionRow().get("max_inactive_interval")).intValue()).isEqualTo(14 * 24 * 60 * 60);
        assertThat(((Number) sessionRow().get("expiry_time")).longValue()).isCloseTo(
                Instant.now().plusSeconds(14L * 24 * 60 * 60).toEpochMilli(), within(60_000L));
    }
}
