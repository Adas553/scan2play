package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.SiteCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Nie wysyłaj mi tego" (V64) through the real security: the e-mail's link, opened by a person or by a mail filter that checks links,
 * shows a question and switches nothing off; its "Wyłącz" and the mail program's one-click "Unsubscribe" (RFC 8058) are a POST without
 * a CSRF token and without a login — and none of it makes a session. Both pages are written for the browser tests
 * ({@code target/browser-harness/email-stop*.html}, {@code scenarios/email-stop.js}: readable on the real styles).
 */
@WebMvcTest(EmailStopController.class)
@Import(SecurityConfig.class)
class EmailStopWebTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private PartySettingsCommandService partySettingsCommandService;
    @MockitoBean
    private SiteCounter siteCounter;

    @BeforeEach
    void setUp() {
        when(partySettingsRepository.findByEmailToken(anyString())).thenReturn(Optional.empty());
        when(partySettingsRepository.findByEmailToken(TOKEN))
                .thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("ABC12").ownerId("o").build()));
    }

    private static void noSession(MvcResult result) {
        assertThat(result.getRequest().getSession(false)).as("a session was made").isNull();
    }

    /** A page of the browser tests: the stand-in serves the files at /js/, /css/ — not under the deploy's version ("/dev/css/"). */
    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }

    @Test
    void theLink_asks_switchesNothingOff_andSendsTheAnswerToItself() throws Exception {
        MvcResult result = mockMvc.perform(get("/email/stop/" + TOKEN).param("lang", "pl")).andExpect(status().isOk()).andReturn();

        String page = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Wyłączyć poranne podsumowanie?", "id=\"emailStopForm\"", "method=\"post\"")
                .as("no CSRF token: the path is exempt, and a token would make a session").doesNotContain("_csrf");
        assertThat(page).as("the form posts to its own address, the secret and the language with it").doesNotContain("action=");
        verify(partySettingsCommandService, never()).updateSettings(any(), any());
        noSession(result);
        write("email-stop.html", page);
    }

    @Test
    void theAnswer_orTheMailProgramsOneClick_switchesItOff_withoutACsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/email/stop/" + TOKEN).param("lang", "en")
                        .contentType("application/x-www-form-urlencoded").content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk()).andReturn();

        String page = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Done — no more morning summaries");
        write("email-stopped-en.html", page);
        verify(partySettingsCommandService).updateSettings(eq("ABC12"), any());
        noSession(result);
    }

    @Test
    void anUnknownSecret_isA404_eitherWay() throws Exception {
        mockMvc.perform(get("/email/stop/ffffffffffffffffffffffffffffffff")).andExpect(status().isNotFound());
        mockMvc.perform(post("/email/stop/ffffffffffffffffffffffffffffffff")).andExpect(status().isNotFound());
        verify(partySettingsCommandService, never()).updateSettings(any(), any());
    }
}
