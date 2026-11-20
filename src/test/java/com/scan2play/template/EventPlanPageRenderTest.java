package com.scan2play.template;

import com.scan2play.controller.DjEventPlanController;
import com.scan2play.controller.DjSessionHelper;
import com.scan2play.controller.ViewAttributes;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.EventPlanService;
import com.scan2play.service.EventPlanService.Moment;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanRequiredException;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.WishService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Plan imprezy" on the DJ's side (V77, {@code event-plan.html}, {@code event-plan-print.html}): the real controller with mocked
 * services and the real message bundles — the plan with its moments and lists, the hosts' link, "Użyj na imprezie" only with the
 * hosts' lists' permission, read only without Pro — and what the forms do: a save in the page's order, the event's lists over the
 * party's. Leaves the pages in {@code target/browser-harness/} for the pages' audit.
 */
class EventPlanPageRenderTest {

    private static final String PARTY = "PLAN1";
    private static final Path OUT = Path.of("target", "browser-harness");
    private static final Locale PL = Locale.forLanguageTag("pl");
    private static SpringTemplateEngine engine;
    private static ResourceBundleMessageSource messages;

    private final WishService wishes = mock(WishService.class);
    private final EventPlanService plans = mock(EventPlanService.class);
    private final PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
    private PartySettingsEntity party;
    private final WishService.Event event = new WishService.Event(7L, PARTY, "Wish_tok3n-AbCdEfGhIjKl", "Wesele Ani i Kuby",
            WishService.today().plusDays(40), false, 23, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC);
    private final EventPlanService.Plan plan = new EventPlanService.Plan(List.of(
            new Moment("Wejście pary", LocalTime.of(17, 30), "Przykładowy Wykonawca - Tytuł Wejścia", "wolno, dopiero gdy wszyscy wstaną"),
            new Moment("Podziękowania dla rodziców", LocalTime.of(21, 0), null, "imiona: Ewa i Marek, Jola i Piotr"),
            new Moment("Oczepiny", null, null, null)), "Akcent\nDisco polo", "Wilki - Baśka", Instant.parse("2026-11-20T18:03:00Z"), "hosts");

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
        party = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).djName("DJ Koko").hostBlocked("Stare")
                .hostWanted("Stare").build();
        when(wishes.event(PARTY, 7L)).thenReturn(Optional.of(event));
        when(plans.plan(7L)).thenReturn(plan);
        when(plans.hostToken(PARTY, 7L)).thenReturn(Optional.of("Host_tok3n-AbCdEfGhIjKl"));
        // the update runs on the party, as the service does under the row's lock
        doAnswer(call -> {
            Consumer<PartySettingsEntity> change = call.getArgument(1);
            change.accept(party);
            return party;
        }).when(commands).updateSettings(eq(PARTY), any());
    }

    private static OAuth2AuthenticationToken user() {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    /** Whether the page is the organiser's (the Pro offer is shown to the organiser only). */
    private boolean owner;

    private DjEventPlanController controller(PlanService planService, java.util.Set<StaffPermission> may) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.access(any(), any(), any())).thenReturn(new PartyStaffService.Access(party, owner, may));
        for (StaffPermission permission : may) {
            when(sessionHelper.require(eq(PARTY), eq(permission), any(), any())).thenReturn(party);
        }
        DjEventPlanController controller = new DjEventPlanController(wishes, plans, sessionHelper, planService, commands, messages);
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "https://www.scan2play.com.pl/");
        return controller;
    }

    private String render(String view, ConcurrentModel model) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        return engine.process(view, context);
    }

    private String page(PlanService planService, java.util.Set<StaffPermission> may, Map<String, Object> flash) {
        ConcurrentModel model = new ConcurrentModel();
        model.addAllAttributes(flash);
        String view = controller(planService, may).page(7L, PARTY, model, user(), new MockHttpSession(), PL);
        assertThat(view).isEqualTo("event-plan");
        return render(view, model);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    @Test
    void thePage_showsThePlan_theHostsLink_andTheNightsButtons() throws IOException {
        String html = page(Plans.off(), StaffPermission.all(), Map.of(ViewAttributes.PLAN_DONE, "saved",
                ViewAttributes.PLAN_DONE_ARG, new String[] {"30", "100"}));

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("<title>Plan imprezy - Scan2Play</title>", ">Plan imprezy<", "Wesele Ani i Kuby · ",
                "href=\"/dj/wishes?party=PLAN1&amp;event=7\"", ">✓ Plan zapisany<");
        // the hosts' link, to copy, with "Nowy link" and "Wyłącz link" asking first
        assertThat(html).contains("value=\"https://www.scan2play.com.pl/h/e/Host_tok3n-AbCdEfGhIjKl\"", ">Nowy link<", ">Wyłącz link<",
                "Obecny link przestanie działać. Utworzyć nowy?");
        // the plan in its order, the spare rows after it, the lists, who saved last
        assertThat(html).contains("action=\"/dj/wishes/7/plan\"", "name=\"_csrf\" value=\"token\"", "name=\"partyCode\" value=\"PLAN1\"",
                "value=\"17:30\"", "value=\"Wejście pary\"", "value=\"imiona: Ewa i Marek, Jola i Piotr\"", ">Akcent\nDisco polo<",
                ">Wilki - Baśka<", "Ostatni zapis: gospodarze, 20.11.2026 19:03:00", ">Zapisz plan<");
        assertThat(html.indexOf("Wejście pary")).isLessThan(html.indexOf("Podziękowania dla rodziców"));
        assertThat(html.split("data-plan-row", -1)).as("three moments and three spare rows").hasSize(7);
        assertThat(html).as("a plan with moments offers no template").doesNotContain("id=\"planTemplateForm\"");
        // on the night: the lists over the party's, asked first; the print
        assertThat(html).contains("action=\"/dj/wishes/7/plan/use-lists\"", "zostaną zastąpione listami tego wydarzenia",
                "href=\"/dj/wishes/7/plan/print?party=PLAN1\"");
        DashboardPageRenderTest.assertNothingInline(html);
        write("event-plan.html", html);
    }

    @Test
    void withoutTheHostsListsPermission_theListsAreNotOffered_andWithoutPro_nothingChanges() throws IOException {
        String staff = page(Plans.off(), EnumSet.of(StaffPermission.WISHES), Map.of());
        assertThat(staff).contains(">Zapisz plan<").doesNotContain("action=\"/dj/wishes/7/plan/use-lists\"");

        owner = true;
        String free = page(Plans.enforced(), StaffPermission.all(), Map.of());
        assertThat(free).contains("value=\"Wejście pary\"", "przejrzeć, ale nie zmienić").containsPattern("<fieldset[^>]*disabled")
                .doesNotContain(">Zapisz plan<", "id=\"planHostCard\"", "action=\"/dj/wishes/7/plan/use-lists\"");
        assertThatThrownBy(() -> controller(Plans.enforced(), StaffPermission.all()).save(7L, PARTY, null, null,
                new MockHttpServletRequest(), PL, user(), new MockHttpSession(), new RedirectAttributesModelMap()))
                .isInstanceOf(PlanRequiredException.class);
        verify(plans, never()).save(any(Long.class), any(), any(), any(), any());
    }

    @Test
    void anEmptyPlan_offersTheTemplates() throws IOException {
        when(plans.plan(7L)).thenReturn(new EventPlanService.Plan(List.of(), null, null, null, null));
        when(plans.hostToken(PARTY, 7L)).thenReturn(Optional.empty());

        String html = page(Plans.off(), StaffPermission.all(), Map.of());

        assertThat(html).contains("id=\"planTemplateForm\"", "action=\"/dj/wishes/7/plan/template\"", ">Wesele<", ">Impreza firmowa<",
                ">🔗 Utwórz link<", "Plan nie jest jeszcze zapisany.");
        assertThat(html.split("data-plan-row", -1)).as("three spare rows").hasSize(4);
        write("event-plan-empty.html", html);
    }

    @Test
    void aSave_keepsTheRowsInThePagesOrder_andTheListsAsTyped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("label", "Tort", "", "Wejście pary, potem toast");
        request.addParameter("time", "23:00", "", "");
        request.addParameter("song", "", "", "");
        request.addParameter("note", "", "", "");
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String back = controller(Plans.off(), StaffPermission.all()).save(7L, PARTY, "Akcent", null, request, PL, user(),
                new MockHttpSession(), flash);

        assertThat(back).isEqualTo("redirect:/dj/wishes/7/plan?party=PLAN1");
        verify(plans).save(7L, List.of(new Moment("Tort", LocalTime.of(23, 0), null, null),
                new Moment("Wejście pary, potem toast", null, null, null)), "Akcent", null, EventPlanService.BY_DJ);
        assertThat(flash.getFlashAttributes().get(ViewAttributes.PLAN_DONE)).isEqualTo("saved");
    }

    @Test
    void usingTheLists_putsTheEventsListsOverTheParties() {
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller(Plans.off(), StaffPermission.all()).useLists(7L, PARTY, user(), new MockHttpSession(), flash);

        assertThat(party.getHostBlocked()).isEqualTo("Akcent\nDisco polo");
        assertThat(party.getHostWanted()).isEqualTo("Wilki - Baśka");
        assertThat(flash.getFlashAttributes().get(ViewAttributes.PLAN_DONE)).isEqualTo("lists_used");
    }

    @Test
    void thePrint_hasTheMomentsWithTheirTimes_andTheLists() throws IOException {
        ConcurrentModel model = new ConcurrentModel();
        String view = controller(Plans.off(), StaffPermission.all()).print(7L, PARTY, model, user(), new MockHttpSession(), PL);

        String html = render(view, model);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains(">Wesele Ani i Kuby<", ">DJ Koko<", ">17:30<", ">Wejście pary<", ">wolno, dopiero gdy wszyscy wstaną<",
                ">Przykładowy Wykonawca - Tytuł Wejścia<", ">—<", ">Oczepiny<", "Akcent\nDisco polo", ">Drukuj<", "/js/plan-form.js");
        DashboardPageRenderTest.assertNothingInline(html);
        write("event-plan-print.html", html);
    }
}
