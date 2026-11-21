package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EventPlanService;
import com.scan2play.service.EventPlanService.Moment;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.WishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Plan imprezy" for the hosts (V77, {@code /h/e/{token}}) through the real security and Spring Session's filter (sessions in memory,
 * as the JDBC ones in production): the page opens without an account and makes no session — its forms carry no CSRF token, which a
 * long page would have to make a session for too late —, a save needs no token and no session (the link's secret is what lets it
 * write; a plan written slowly must not be refused), the rows come in their order with their commas, and an unknown link is a 404.
 * Writes the page for the pages' audit ({@code target/browser-harness/host-plan.html}).
 */
@WebMvcTest(HostController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, EventPlanHostWebTest.Sessions.class})
class EventPlanHostWebTest {

    private static final String TOKEN = "Plan_123-xyzPlan_123-xy";
    private static final String PAGE = "/h/e/" + TOKEN;
    private static final long EVENT = 7;

    @TestConfiguration
    static class Sessions {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 50)
        SessionRepositoryFilter<MapSession> springSessionRepositoryFilter() {
            return new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private PartySettingsCommandService partySettingsCommandService;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;
    @MockitoBean
    private WishService wishService;
    @MockitoBean
    private EventPlanService eventPlanService;

    @BeforeEach
    void setUp() {
        when(partySettingsRepository.findByPartyCode("PLN01")).thenReturn(Optional.of(
                PartySettingsEntity.builder().partyCode("PLN01").ownerId("owner").djName("DJ Koko").build()));
        when(eventPlanService.byHostToken(anyString())).thenReturn(Optional.empty());
        when(eventPlanService.byHostToken(TOKEN)).thenReturn(Optional.of(new EventPlanService.HostEvent(EVENT, "PLN01")));
        when(wishService.event("PLN01", EVENT)).thenReturn(Optional.of(new WishService.Event(EVENT, "PLN01", "wish-token",
                "Wesele Ani i Kuby", java.time.LocalDate.of(2099, 6, 14), false, 0, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC)));
        when(eventPlanService.saveByHosts(eq(TOKEN), any(), any(), any(), any())).thenReturn(EventPlanService.Saved.SAVED);
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void thePage_opensWithoutAnAccount_andMakesNoSession() throws Exception {
        when(eventPlanService.plan(EVENT)).thenReturn(new EventPlanService.Plan(List.of(
                new Moment("Pierwszy taniec", LocalTime.of(18, 15), "Ktoś - Nasza Piosenka", "wersja z płyty"),
                new Moment("Oczepiny", null, null, null)), "Akcent", null, Instant.parse("2099-06-01T19:03:00Z"), "dj"));

        MvcResult result = mockMvc.perform(get(PAGE).header("Accept-Language", "pl-PL")).andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow")).andReturn();

        assertThat(result.getResolvedException()).isNull();
        String html = html(result);
        assertThat(html).contains("<title>Plan imprezy - Scan2Play</title>", ">Plan imprezy dla DJ-a<", "Wesele Ani i Kuby · 14 czerwca 2099",
                "🎧 Gra:", "action=\"" + PAGE + "\"", "value=\"18:15\"", "value=\"Pierwszy taniec\"", "value=\"wersja z płyty\"",
                "value=\"Oczepiny\"", ">Akcent<", "Ostatni zapis: DJ, 01.06.2099 21:03:00", ">Zapisz plan<", "/js/plan-form.js", "<footer",
                "</html>");
        assertThat(html).as("the plan has moments: no template offered").doesNotContain("id=\"planTemplateForm\"");
        assertThat(html).as("no token: no session to make").doesNotContain("name=\"_csrf\"", "name=\"partyCode\"");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("SESSION="));
        WishWebTest.write("host-plan.html", html);
    }

    @Test
    void anEmptyPlan_offersTheTemplates() throws Exception {
        when(eventPlanService.plan(EVENT)).thenReturn(new EventPlanService.Plan(List.of(), null, null, null, null));

        String html = html(mockMvc.perform(get(PAGE).header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"planTemplateForm\"", "action=\"" + PAGE + "/template\"", ">Wesele<", ">Osiemnastka<",
                "Plan nie jest jeszcze zapisany.");
        WishWebTest.write("host-plan-empty.html", html);
    }

    @Test
    void aSave_needsNoTokenAndNoSession_andKeepsTheRowsInTheirOrder() throws Exception {
        mockMvc.perform(post(PAGE).header("Accept-Language", "pl-PL")
                        .param("label", "Wejście pary, potem toast", "", "Tort")
                        .param("time", "17:30", "", "")
                        .param("song", "", "", "")
                        .param("note", "", "", "DJ wybiera")
                        .param("blocked", "Akcent").param("wanted", ""))
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute(ViewAttributes.PLAN_DONE, "saved"));

        verify(eventPlanService).saveByHosts(TOKEN, List.of(new Moment("Wejście pary, potem toast", LocalTime.of(17, 30), null, null),
                new Moment("Tort", null, null, "DJ wybiera")), "Akcent", "", null);
    }

    @Test
    void anUnknownLink_isA404_andWritesNothing() throws Exception {
        mockMvc.perform(get("/h/e/nobodys-link")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h/e/nobodys-link").param("label", "Tort")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h/e/nobodys-link/template").param("template", "wedding")).andExpect(status().isNotFound());
        verify(eventPlanService, never()).saveByHosts(anyString(), any(), any(), any(), any());
    }

    @Test
    void aTemplate_startsThePlan_withItsNamesInThePagesLanguage() throws Exception {
        when(eventPlanService.startFrom(eq(EVENT), any(), eq(EventPlanService.BY_HOSTS))).thenReturn(true);

        mockMvc.perform(post(PAGE + "/template").header("Accept-Language", "pl-PL").param("template", "wedding"))
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute(ViewAttributes.PLAN_DONE, "template"));

        verify(eventPlanService).startFrom(EVENT, List.of("Wejście pary", "Pierwszy taniec", "Podziękowania dla rodziców", "Tort",
                "Oczepiny", "Ostatni taniec"), EventPlanService.BY_HOSTS);
    }

    /**
     * A save made from a page opened before someone else saved the plan writes nothing: the page says who saved and when, and shows the
     * form's content again (the draft, a flash attribute in the session), so nothing typed is lost; "Zapisz plan" then replaces the
     * other save on purpose (the plan's current version in the form).
     */
    @Test
    void aSaveOverAPlanChangedMeanwhile_isRefused_andWhatWasTypedComesBack() throws Exception {
        when(eventPlanService.saveByHosts(eq(TOKEN), any(), any(), any(), eq("111"))).thenReturn(EventPlanService.Saved.CHANGED);
        EventPlanService.Plan now = new EventPlanService.Plan(List.of(new Moment("Od DJ-a", null, null, null)), null, null,
                Instant.parse("2099-06-01T19:03:00Z"), "dj");
        when(eventPlanService.plan(EVENT)).thenReturn(now);

        MvcResult saved = mockMvc.perform(post(PAGE).header("Accept-Language", "pl-PL").param("version", "111")
                        .param("label", "Nasz moment").param("time", "").param("song", "").param("note", "")
                        .param("blocked", "Akcent").param("wanted", ""))
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute(ViewAttributes.PLAN_DONE, "changed")).andReturn();

        // the page the redirect leads to, with the session the save made for its flash attributes (as a browser: by its cookie)
        String session = saved.getResponse().getHeaders("Set-Cookie").stream().filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst().orElseThrow().split(";")[0].substring("SESSION=".length());
        String html = html(mockMvc.perform(get(PAGE).header("Accept-Language", "pl-PL")
                        .cookie(new jakarta.servlet.http.Cookie("SESSION", session))).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("Nic nie zostało zapisane: w międzyczasie plan zapisano jeszcze raz (DJ, 01.06.2099 21:03:00)",
                "alert-warning", "value=\"Nasz moment\"", ">Akcent<", "name=\"version\" value=\"" + now.version() + "\"",
                "data-plan-draft=\"true\"").doesNotContain("value=\"Od DJ-a\"");
    }

    @Test
    void tooManySaves_onALink_areRefused_keepingWhatWasTyped() throws Exception {
        when(eventPlanService.saveByHosts(eq(TOKEN), any(), any(), any(), any())).thenReturn(EventPlanService.Saved.TOO_MANY);

        mockMvc.perform(post(PAGE).param("label", "Tort"))
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute(ViewAttributes.PLAN_DONE, "too_many"))
                .andExpect(flash().attribute(ViewAttributes.PLAN_DRAFT, new EventPlanService.Draft(
                        List.of(new Moment("Tort", null, null, null)), null, null)));
    }
}
