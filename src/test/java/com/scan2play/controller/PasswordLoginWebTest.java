package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.DjAccountEntity;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PasswordAccountService;
import com.scan2play.service.PasswordAccountService.LoginOutcome;
import com.scan2play.service.PasswordAccountService.LoginResult;
import com.scan2play.service.SiteCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login page and the password's pages (V72) through the real security (the first visit through Spring Session's filter:
 * {@link PasswordLoginSessionWebTest}): a right password logs in as Google's login does — an {@link OAuth2AuthenticationToken} named {@code local:<id>}, a new session id,
 * the DJ's page asked for before it —; a wrong one says the same as an unknown address.
 */
@WebMvcTest(PasswordLoginController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, GuestRequestLimiter.class})
class PasswordLoginWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordAccountService accounts;
    @MockitoBean
    private SiteCounter siteCounter;

    private static DjAccountEntity account() {
        return DjAccountEntity.builder().id(7L).email("ola@example.com").displayName("DJ Ola").passwordHash("{bcrypt}x")
                .createdAt(Instant.EPOCH).emailVerifiedAt(Instant.EPOCH).build();
    }

    private static String html(MvcResult result) throws Exception {
        return WishWebTest.html(result);
    }

    /** The session of the last form's page opened by {@link #from}. */
    private MockHttpSession opened;

    /** A form's page opened (its session and CSRF token): a POST of that form, as a browser sends it. */
    private MockHttpServletRequestBuilder from(String page, String action) throws Exception {
        MvcResult result = mockMvc.perform(get(page)).andReturn();
        String token = WishWebTest.csrf(html(result));
        opened = (MockHttpSession) result.getRequest().getSession();
        return post(action).session(opened).param("_csrf", token);
    }

    private MockHttpServletRequestBuilder from(String page) throws Exception {
        return from(page, page.replaceAll("\\?.*", ""));
    }

    @Test
    void aRightPassword_logsInAsGooglesLoginDoes_withANewSessionId() throws Exception {
        when(accounts.logIn(eq("ola@example.com"), eq("dobre-haslo-1"), any(), any()))
                .thenReturn(new LoginResult(LoginOutcome.OK, account()));
        MockHttpServletRequestBuilder form = from("/login", "/login/password");
        String before = opened.getId();

        MvcResult result = mockMvc.perform(form
                        .param("email", " Ola@Example.com ").param("password", "dobre-haslo-1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dj/dashboard")).andReturn();

        SecurityContext context = (SecurityContext) result.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication()).isInstanceOfSatisfying(OAuth2AuthenticationToken.class, token -> {
            assertThat(token.getName()).isEqualTo("local:7");
            assertThat(token.getPrincipal().<String>getAttribute("name")).isEqualTo("DJ Ola");
            assertThat(StaffController.verifiedAddress(token)).as("the address is a confirmed one").isEqualTo("ola@example.com");
        });
        assertThat(result.getRequest().getSession().getId()).isNotEqualTo(before);
        verify(siteCounter).count(SiteCounter.Event.LOGIN);
    }

    @Test
    void afterTheLogin_theDjsPageAskedForBeforeIt() throws Exception {
        when(accounts.logIn(anyString(), anyString(), any(), any())).thenReturn(new LoginResult(LoginOutcome.OK, account()));
        MockHttpSession session = (MockHttpSession) mockMvc.perform(get("/dj/dashboard?party=ABC12").header("Sec-Fetch-Mode", "navigate"))
                .andExpect(redirectedUrl("/login")).andReturn().getRequest().getSession();

        String token = WishWebTest.csrf(html(mockMvc.perform(get("/login").session(session)).andReturn()));
        mockMvc.perform(post("/login/password").session(session).param("_csrf", token).param("email", "ola@example.com").param("password", "x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).contains("/dj/dashboard?party=ABC12"));
    }

    @Test
    void aWrongPassword_andAnAddressThatIsNone_readTheSame_andLogNothingIn() throws Exception {
        when(accounts.logIn(anyString(), anyString(), any(), any())).thenReturn(new LoginResult(LoginOutcome.WRONG, null));

        MvcResult wrong = mockMvc.perform(from("/login", "/login/password").header("Accept-Language", "pl-PL")
                .param("email", "ola@example.com").param("password", "zle")).andExpect(status().isOk()).andReturn();
        MvcResult notAnAddress = mockMvc.perform(from("/login", "/login/password").header("Accept-Language", "pl-PL")
                .param("email", "ola").param("password", "zle")).andExpect(status().isOk()).andReturn();

        assertThat(html(wrong)).contains("Zły adres e-mail albo hasło.", "value=\"ola@example.com\"");
        assertThat(html(notAnAddress)).contains("Zły adres e-mail albo hasło.");
        assertThat(wrong.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    void aForm_withoutItsToken_logsNothingIn_andGoesBackToItsPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login/password").session(session).param("email", "ola@example.com").param("password", "x"))
                .andExpect(redirectedUrl("/login"));
        String page = html(mockMvc.perform(get("/login").session(session).header("Accept-Language", "pl-PL")).andReturn());
        assertThat(page).contains("Strona była otwarta za długo.");
        mockMvc.perform(post("/password/reset").param("token", "abc_-1").param("password", "nowe-haslo-22"))
                .andExpect(redirectedUrl("/password/reset?token=abc_-1"));
        mockMvc.perform(post("/register/confirm").param("token", "<script>").param("password", "x"))
                .andExpect(redirectedUrl("/register/confirm"));
        verify(accounts, never()).logIn(any(), any(), any(), any());
    }

    @Test
    void aNewAccount_alwaysCheckTheMailbox_whateverTheAddress() throws Exception {
        mockMvc.perform(from("/register").header("Accept-Language", "pl-PL")
                        .param("name", "DJ Ola").param("email", "Ola@Example.com").param("password", "dobre-haslo-1"))
                .andExpect(redirectedUrl("/login/sent"));
        verify(accounts).requestRegistration(eq("ola@example.com"), eq("dobre-haslo-1"), eq("DJ Ola"), any(), any());

        String page = html(mockMvc.perform(get("/login/sent").header("Accept-Language", "pl-PL")
                        .flashAttr("noticeTitle", "account.sent.title").flashAttr("noticeText", "account.sent.register")
                        .flashAttr("noticeEmail", "ola@example.com"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Sprawdź skrzynkę", "Wysłaliśmy maila na ola@example.com.");
        WishWebTest.write("account-sent.html", page);
    }

    @Test
    void aNewAccount_withAShortPassword_orNoAddress_isTheFormAgain() throws Exception {
        String shortOne = html(mockMvc.perform(from("/register").header("Accept-Language", "pl-PL")
                .param("email", "ola@example.com").param("password", "krotkie")).andExpect(status().isOk()).andReturn());
        String noAddress = html(mockMvc.perform(from("/register").header("Accept-Language", "pl-PL")
                .param("email", "ola").param("password", "dobre-haslo-1")).andExpect(status().isOk()).andReturn());

        assertThat(shortOne).contains("Hasło musi mieć co najmniej 8 znaków.", "value=\"ola@example.com\"");
        assertThat(noAddress).contains("To nie jest adres e-mail.");
        verify(accounts, never()).requestRegistration(any(), any(), any(), any(), any());
    }

    @Test
    void theConfirmationLink_asksThePassword_andThenLogsIn() throws Exception {
        when(accounts.toConfirm("good")).thenReturn(Optional.of(account()));
        when(accounts.confirm(eq("good"), eq("dobre-haslo-1"), any())).thenReturn(new LoginResult(LoginOutcome.OK, account()));
        when(accounts.confirm(eq("good"), eq("zle-haslo"), any())).thenReturn(new LoginResult(LoginOutcome.WRONG, null));

        String page = html(mockMvc.perform(get("/register/confirm?token=good").header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Potwierdź konto", "ola@example.com", "name=\"token\" value=\"good\"");
        verify(accounts, never()).confirm(any(), any(), any());

        assertThat(html(mockMvc.perform(from("/register/confirm?token=good").header("Accept-Language", "pl-PL")
                .param("token", "good").param("password", "zle-haslo")).andExpect(status().isOk()).andReturn())).contains("Złe hasło.");
        mockMvc.perform(from("/register/confirm?token=good").param("token", "good").param("password", "dobre-haslo-1"))
                .andExpect(redirectedUrl("/dj/dashboard"));
    }

    @Test
    void anOldLink_saysSo() throws Exception {
        when(accounts.toReset("good")).thenReturn(Optional.of(account()));
        String page = html(mockMvc.perform(get("/register/confirm?token=old").header("Accept-Language", "pl-PL"))
                .andExpect(status().isGone()).andReturn());
        assertThat(page).contains("Link już nie działa");
        mockMvc.perform(get("/password/reset?token=old")).andExpect(status().isGone());
        mockMvc.perform(from("/password/reset?token=good").param("token", "old").param("password", "nowe-haslo-22"))
                .andExpect(status().isGone());
    }

    @Test
    void aNewPassword_isSet_andLogsIn() throws Exception {
        when(accounts.toReset("good")).thenReturn(Optional.of(account()));
        when(accounts.resetPassword("good", "nowe-haslo-22")).thenReturn(Optional.of(account()));

        mockMvc.perform(from("/password/reset?token=good").param("token", "good").param("password", "krotkie"))
                .andExpect(status().isOk());
        verify(accounts, never()).resetPassword(any(), any());

        mockMvc.perform(from("/password/reset?token=good").param("token", "good").param("password", "nowe-haslo-22"))
                .andExpect(redirectedUrl("/dj/dashboard"));
    }

    @Test
    void forgotThePassword_alwaysCheckTheMailbox() throws Exception {
        mockMvc.perform(from("/password/forgot").param("email", "nikt@example.com"))
                .andExpect(redirectedUrl("/login/sent"));
        verify(accounts).requestPasswordReset(eq("nikt@example.com"), any(), any());
    }

    @Test
    void theSentPage_withoutAForm_isTheLoginPage() throws Exception {
        mockMvc.perform(get("/login/sent")).andExpect(redirectedUrl("/login"));
    }
}
