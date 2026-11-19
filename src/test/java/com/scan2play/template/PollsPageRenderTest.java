package com.scan2play.template;

import com.scan2play.controller.DjPollsController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.PollService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DJ's page of "Pytanie do gości" (V74, {@code polls.html}, {@code GET /dj/polls}): the real controller with a mocked service and
 * the real message bundles — the form, the question asked now with its results and its live fragment, the saved and the ended ones with
 * their buttons — and what "Zadaj" makes of the form: no answer typed is "Tak" / "Nie", one answer alone is refused. Leaves
 * {@code polls*.html} in {@code target/browser-harness/} for the browser tests.
 */
class PollsPageRenderTest {

    private static final String PARTY = "POLL1";
    private static final Path OUT = Path.of("target", "browser-harness");
    private static final Locale PL = Locale.forLanguageTag("pl");
    private static SpringTemplateEngine engine;
    private static ResourceBundleMessageSource messages;

    private final PollService polls = mock(PollService.class);
    private PartySettingsEntity party;
    private final PollService.Poll asked = new PollService.Poll(3, PARTY, "Disco polo — tak czy nie?", List.of("Tak", "Nie"), 12,
            Instant.now(), Instant.now(), null);
    private final PollService.Poll saved = new PollService.Poll(2, PARTY, "Kto pierwszy na parkiet?", List.of("Ania", "Kuba"), 0,
            Instant.now(), null, null);
    private final PollService.Poll ended = new PollService.Poll(1, PARTY, "Wolniej czy szybciej?", List.of("Wolniej", "Szybciej", "Tak jak jest"),
            9, Instant.now(), Instant.now(), Instant.now(), true, null, false);

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    @BeforeEach
    void setUp() {
        party = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).build();
        when(polls.pollsOf(PARTY)).thenReturn(List.of(asked, saved, ended));
        when(polls.poll(PARTY, 3L)).thenReturn(Optional.of(asked));
        when(polls.results(asked)).thenReturn(new PollService.Results(asked, List.of(new PollService.Row(0, "Tak", 7, 58, true),
                new PollService.Row(1, "Nie", 5, 42, false)), 12));
        when(polls.results(saved)).thenReturn(new PollService.Results(saved, List.of(new PollService.Row(0, "Ania", 0, 0, false),
                new PollService.Row(1, "Kuba", 0, 0, false)), 0));
        when(polls.results(ended)).thenReturn(new PollService.Results(ended, List.of(new PollService.Row(0, "Wolniej", 2, 22, false),
                new PollService.Row(1, "Szybciej", 6, 67, true), new PollService.Row(2, "Tak jak jest", 1, 11, false)), 9));
        when(polls.create(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean())).thenReturn(Optional.of(asked));
    }

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    private DjPollsController controller(PlanService plans) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.access(any(), any(), any())).thenReturn(new PartyStaffService.Access(party, true, StaffPermission.all()));
        when(sessionHelper.require(eq(PARTY), eq(StaffPermission.POLLS), any(), any())).thenReturn(party);
        return new DjPollsController(polls, sessionHelper, plans, messages);
    }

    private static String process(String view, Map<String, Object> model) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model);
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        if (view.contains("::")) {
            String[] parts = view.split("::");
            return engine.process(parts[0].strip(), java.util.Set.of(parts[1].strip()), context);
        }
        return engine.process(view, context);
    }

    private String render(PlanService plans, Map<String, Object> flash) {
        LocaleContextHolder.setLocale(PL);
        ConcurrentModel model = new ConcurrentModel();
        model.addAllAttributes(flash);
        String view = controller(plans).page(PARTY, model, user("owner"), new MockHttpSession());
        assertThat(view).isEqualTo("polls");
        return process(view, model.asMap());
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    @Test
    void thePage_showsTheForm_theQuestionAskedWithItsResultsLive_andTheOthers() throws IOException {
        String html = render(Plans.off(), Map.of());
        assertThat(html).doesNotContain("??");
        assertThat(html).contains(">Pytanie do gości<", "id=\"pollNewForm\"", "action=\"/dj/polls/create\"", "Pytania: 3 z 30.",
                "placeholder=\"Tak\"", "placeholder=\"Nie\"", ">Zadaj teraz<", ">Zapisz na później<");
        // the question asked: its results, fetched again by polls.js, "Zakończ pytanie" and the TV
        assertThat(html).contains(">Disco polo — tak czy nie?<", ">zadane teraz<", "data-poll-live=\"/dj/polls/3/results?party=POLL1\"",
                ">58%<", "value=\"58\"", ">Głosy: 12<", "action=\"/dj/polls/3/end\"", ">Zakończ pytanie<", "href=\"/p/POLL1/tv?lang=pl\"");
        // saved: its answers and "Zadaj teraz"; ended: its results and "Zadaj ponownie"; each "Usuń" asks first
        assertThat(html).contains(">Kto pierwszy na parkiet?<", ">na później<", ">Ania · Kuba<", "action=\"/dj/polls/2/ask\"",
                ">Wolniej czy szybciej?<", ">zakończone<", ">Zadaj ponownie<", "action=\"/dj/polls/1/ask\"",
                "data-confirm=\"Usunąć to pytanie razem z głosami?\"");
        assertThat(html).containsOnlyOnce("data-poll-live=");
        // the guests' results (V75): picked when made; on each question asked or ended, whether they see them and the switch
        assertThat(html).contains("name=\"hideResults\"", ">Od razu po ich głosie<", ">Dopiero gdy je pokażę (np. zabawa)<",
                "Goście i ekran TV widzą wyniki.", "action=\"/dj/polls/3/results\"", ">Ukryj wyniki przed gośćmi<",
                "Wyniki ukryte — goście i ekran TV widzą tylko liczbę głosów.", "action=\"/dj/polls/1/results\"", ">👀 Pokaż wyniki gościom<");
        assertThat(html).doesNotContain("action=\"/dj/polls/2/results\"");
        // the TV screen (V76): the question asked is on it, the ended one left the songs there; switched each way
        assertThat(html).contains("Ekran TV: pytanie.", "Ekran TV: lista piosenek — pytanie widać tylko na telefonach gości.",
                "action=\"/dj/polls/3/tv\"", ">📺 TV: pokaż listę piosenek<", "action=\"/dj/polls/1/tv\"", ">📺 TV: pokaż pytanie<",
                ">📺 Otwórz ekran TV<");
        DashboardPageRenderTest.assertNothingInline(html);
        write("polls.html", html);
    }

    @Test
    void theResultsFragment_isTheRowsAndTheTotal() throws IOException {
        ConcurrentModel model = new ConcurrentModel();
        LocaleContextHolder.setLocale(PL);
        String view = controller(Plans.off()).results(3L, PARTY, model, user("owner"), new MockHttpSession());
        String html = process(view, model.asMap()).strip();
        assertThat(html).startsWith("<ul").contains(">Tak<", ">58%<", "(7)", "s2p-poll-lead", ">Głosy: 12<").doesNotContain("<form");
        write("polls-results.html", html);
        // a few guests later: what polls.js puts in place
        when(polls.results(asked)).thenReturn(new PollService.Results(asked, List.of(new PollService.Row(0, "Tak", 9, 45, false),
                new PollService.Row(1, "Nie", 11, 55, true)), 20));
        ConcurrentModel later = new ConcurrentModel();
        controller(Plans.off()).results(3L, PARTY, later, user("owner"), new MockHttpSession());
        write("polls-results-next.html", process(view, later.asMap()).strip());
    }

    @Test
    void withoutPro_theOrganiserSeesTheOffer_noFormAndNoAsk_butMayEndAndDelete() throws IOException {
        String html = render(Plans.enforced(), Map.of());
        assertThat(html).contains("data-pro-offer").doesNotContain("id=\"pollNewForm\"", "action=\"/dj/polls/2/ask\"");
        assertThat(html).contains("action=\"/dj/polls/3/end\"", "action=\"/dj/polls/1/delete\"");
        write("polls-free.html", html);
    }

    @Test
    void theToast_saysWhatTheFormDid() throws IOException {
        assertThat(render(Plans.off(), Map.of("pollDone", "asked"))).contains("id=\"pollDone\"", "Pytanie zadane — goście już je widzą.");
        String refused = render(Plans.off(), Map.of("pollDone", "too_many"));
        assertThat(refused).contains("s2p-done-toast-warning", "Masz już 30 pytań");
        write("polls-done.html", refused);
    }

    @Test
    void askingWithNoAnswerTyped_isYesOrNo_inTheDjsLanguage_andOneAnswerIsRefused() {
        DjPollsController controller = controller(Plans.off());
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        assertThat(controller.create(PARTY, "Disco polo?", List.of("", " ", "", ""), false, true, user("owner"), new MockHttpSession(), flash, PL))
                .isEqualTo("redirect:/dj/polls?party=POLL1");
        verify(polls).create(PARTY, "Disco polo?", List.of("Tak", "Nie"), false, true);
        assertThat(flash.getFlashAttributes().get("pollDone")).isEqualTo("asked");

        RedirectAttributesModelMap refused = new RedirectAttributesModelMap();
        controller.create(PARTY, "Disco polo?", List.of("Tak", "", "", ""), false, false, user("owner"), new MockHttpSession(), refused, PL);
        assertThat(refused.getFlashAttributes().get("pollDone")).isEqualTo("invalid");
        controller.create(PARTY, "   ", List.of(), false, false, user("owner"), new MockHttpSession(), refused, PL);
        verify(polls, never()).create(eq(PARTY), anyString(), eq(List.of("Tak")), anyBoolean(), anyBoolean());
    }

    @Test
    void withoutPro_nothingIsMadeOrAsked() {
        DjPollsController controller = controller(Plans.enforced());
        assertThatThrownBy(() -> controller.create(PARTY, "Disco polo?", List.of(), false, true, user("owner"), new MockHttpSession(),
                new RedirectAttributesModelMap(), PL)).isInstanceOf(com.scan2play.service.PlanRequiredException.class);
        assertThatThrownBy(() -> controller.ask(2L, PARTY, user("owner"), new MockHttpSession(), new RedirectAttributesModelMap()))
                .isInstanceOf(com.scan2play.service.PlanRequiredException.class);
        verify(polls, never()).ask(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
