package com.scan2play.template;

import com.scan2play.controller.DjSessionHelper;
import com.scan2play.controller.DjWishesController;
import com.scan2play.controller.ViewAttributes;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DjLibraryService;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanRequiredException;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.WishService;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.thymeleaf.context.WebContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DJ's page of "Życzenia przed imprezą" (V68, {@code wishes.html}, {@code GET /dj/wishes}): the real controller with mocked
 * services and the real message bundles — the events, the one opened with its wishes marked by the library, the forms' answers — and
 * what the forms do: "Utwórz" only with a name and a day, "⭐ Do „Koniecznie zagrać”" adds the event's songs (never another text) to the
 * hosts' wish list, its 100 lines said when full. Leaves {@code wishes*.html} in {@code target/browser-harness/} for the browser tests.
 */
class WishesPageRenderTest {

    private static final String PARTY = "WISH1";
    private static final Path OUT = Path.of("target", "browser-harness");
    private static final Locale PL = Locale.forLanguageTag("pl");
    private static SpringTemplateEngine engine;

    private final WishService wishes = mock(WishService.class);
    private final DjService djService = mock(DjService.class);
    private final DjLibraryService library = mock(DjLibraryService.class);
    private final PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
    private PartySettingsEntity party;
    private final WishService.Event event = new WishService.Event(7L, PARTY, "Wish_tok3n-AbCdEfGhIjKl", "Wesele Ani i Kuby",
            WishService.today().plusDays(40), false, 23, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC);

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    @BeforeEach
    void setUp() {
        party = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").active(true).hostWanted("Akcent").build();
        WishService.Event closed = new WishService.Event(8L, PARTY, "Wish_tok3n-Zzzzzzzzzzzz", "Osiemnastka Oli",
                WishService.today().plusDays(9), true, 4, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC);
        when(wishes.eventsOf(PARTY)).thenReturn(List.of(closed, event));
        when(wishes.openEvents(PARTY)).thenReturn(1);
        when(wishes.event(PARTY, 7L)).thenReturn(Optional.of(event));
        List<WishService.Wish> list = List.of(new WishService.Wish("Wilki - Baśka", 7, true),
                new WishService.Wish("Sanah - Szampan", 4, true), new WishService.Wish("Akcent - Przekorny los", 3, true),
                new WishService.Wish("Dawid Podsiadło - Małomiasteczkowy (a long name that wraps on a phone, to see it does)", 1, true),
                new WishService.Wish("coś od zenka", 2, false));
        when(wishes.wishes(7L)).thenReturn(list);
        when(wishes.wishes(eq(7L), any(Integer.class))).thenReturn(list);
        when(wishes.songs(7L)).thenReturn(5);
        when(djService.libraryOf(PARTY)).thenReturn(Optional.of(new DjLibraryService.Info(1200, null, Instant.now(), null, true)));
        when(library.matches(eq(PARTY), anyCollection())).thenReturn(Map.of(
                LibraryKey.of("Wilki - Baśka"), new DjLibraryService.Match(LibraryMark.HAVE, new BigDecimal("128.00"), "5A"),
                LibraryKey.of("Sanah - Szampan"), new DjLibraryService.Match(LibraryMark.MISSING, null, null),
                LibraryKey.of("Akcent - Przekorny los"), new DjLibraryService.Match(LibraryMark.SIMILAR, null, null)));
        // the update runs on the party, as the service does under the row's lock
        doAnswer(call -> {
            Consumer<PartySettingsEntity> change = call.getArgument(1);
            change.accept(party);
            return party;
        }).when(commands).updateSettings(eq(PARTY), any());
    }

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    private DjWishesController controller(PlanService plans) {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        PartyStaffService.Access access = new PartyStaffService.Access(party, true, StaffPermission.all());
        when(sessionHelper.access(any(), any(), any())).thenReturn(access);
        when(sessionHelper.require(eq(PARTY), eq(StaffPermission.WISHES), any(), any())).thenReturn(party);
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        DjWishesController controller = new DjWishesController(wishes, sessionHelper, plans, djService, library, commands, messages);
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "https://www.scan2play.com.pl/");
        return controller;
    }

    private String render(PlanService plans, Long opened, Map<String, Object> flash) {
        LocaleContextHolder.setLocale(PL);
        ConcurrentModel model = new ConcurrentModel();
        model.addAllAttributes(flash);
        String view = controller(plans).page(PARTY, opened, model, user("owner"), new MockHttpSession());
        assertThat(view).isEqualTo("wishes");
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(model.asMap());
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        return engine.process(view, context);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    @Test
    void thePage_listsTheEvents_andTheOneOpened_withItsWishesMarked() throws IOException {
        String html = render(Plans.off(), 7L, Map.of());
        assertThat(html).doesNotContain("??");
        assertThat(html).contains(">Życzenia przed imprezą<", "id=\"wishNewForm\"", "action=\"/dj/wishes/create\"", "Otwarte: 1 z 5.",
                ">Osiemnastka Oli<", ">zakończone<", ">Wesele Ani i Kuby<", ">zbiera<", "życzeń: 23");
        // the event opened: its link, the invitation's print, its buttons
        assertThat(html).contains("value=\"https://www.scan2play.com.pl/w/Wish_tok3n-AbCdEfGhIjKl\"",
                "href=\"/dj/qr-print?party=WISH1&amp;wish=7&amp;layout=cards\"", ">Zakończ zbieranie<", ">Nowy link<", ">Usuń wydarzenie<",
                "piosenek: 5", "nie masz w bibliotece: 1");
        // the most wished first, marked by the library, the tempo beside "✓"; the one the hosts' list names already said so
        assertThat(html.indexOf("Wilki - Baśka")).isLessThan(html.indexOf("Sanah - Szampan"));
        assertThat(html).contains("data-library=\"have\"", ">128 BPM · 5A<", "data-library=\"missing\"", "data-library=\"similar\"",
                ">⭐ Na liście<", ">⭐ Do „Koniecznie zagrać”<", ">⭐ Dodaj 10 najczęstszych<");
        String akcent = html.substring(html.indexOf("Akcent - Przekorny los"), html.indexOf("Dawid Podsiadło"));
        assertThat(akcent).as("\"Akcent\" on the list names every song of Akcent").contains("⭐ Na liście").doesNotContain("name=\"song\"");
        // written without the artist: apart, to check — no star, no mark
        assertThat(html).contains(">Do sprawdzenia<", ">coś od zenka<");
        assertThat(html).contains("href=\"/dj/wishes/7/csv?party=WISH1\"");
        // "⭐ Dodaj 10 najczęstszych" says first which it adds: the guests wrote them, the list lets them past the AI — not "Akcent"'s,
        // which the list names already
        assertThat(html).contains("data-confirm=\"Dodać do „Koniecznie zagrać”: Wilki - Baśka; Sanah - Szampan; Dawid Podsiadło - "
                + "Małomiasteczkowy (a long name that wraps on a phone, to see it does)?\"");
        DashboardPageRenderTest.assertNothingInline(html);
        write("wishes.html", html);
    }

    /**
     * The look of the event's guests' page, its own: picked when it is made (Klasyczny first), changed on the event — not the party's
     * "Motyw dla gości", which a DJ changes during a party.
     */
    @Test
    void theEventsLook_isPickedWhenMade_andChangedOnTheEvent() throws IOException {
        String html = render(Plans.off(), 7L, Map.of());
        String made = html.substring(html.indexOf("id=\"wishNewForm\""), html.indexOf("</form>", html.indexOf("id=\"wishNewForm\"")));
        assertThat(made).contains("name=\"theme\"", "value=\"CLASSIC\" selected=\"selected\"", "value=\"WEDDING\"", ">Weselny<", ">Straszny<");
        String opened = html.substring(html.indexOf("id=\"wishThemeForm\""), html.indexOf("</form>", html.indexOf("id=\"wishThemeForm\"")));
        assertThat(opened).contains("value=\"CLASSIC\" selected=\"selected\"", "value=\"CONFETTI\"");
        assertThat(html).contains("action=\"/dj/wishes/7/theme\"");

        RedirectAttributesModelMap answer = new RedirectAttributesModelMap();
        when(wishes.setTheme(PARTY, 7L, com.scan2play.model.GuestTheme.CONFETTI)).thenReturn(true);
        assertThat(controller(Plans.off()).theme(7L, PARTY, "CONFETTI", user("owner"), new MockHttpSession(), answer))
                .isEqualTo("redirect:/dj/wishes?party=WISH1&event=7");
        verify(wishes).setTheme(PARTY, 7L, com.scan2play.model.GuestTheme.CONFETTI);
        assertThat(answer.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("theme");
        assertThat(render(Plans.off(), 7L, Map.of(ViewAttributes.WISH_DONE, "theme", ViewAttributes.WISH_DONE_ARG, new String[0])))
                .contains("Wygląd strony dla gości zmieniony.");
    }

    /** Every one of the most wished on the list already: no "⭐ Dodaj 10 najczęstszych", it would add nothing. */
    @Test
    void theTopButton_isGone_whenTheListHasThemAll() throws IOException {
        party.setHostWanted("Akcent\nWilki\nSanah\nDawid Podsiadło");
        assertThat(render(Plans.off(), 7L, Map.of())).doesNotContain("id=\"wishTopBtn\"").contains(">⭐ Na liście<");
    }

    @Test
    void withoutPro_thePageOffersIt_andMakesNothing() throws IOException {
        String html = render(Plans.enforced(), 7L, Map.of());
        assertThat(html).contains("data-pro-offer", "Życzenia gości przed imprezą — w planie Pro.")
                .doesNotContain("id=\"wishNewForm\"", "id=\"wishLinkInput\"", ">⭐ Do „Koniecznie zagrać”<");
        assertThat(html).as("an event made before may be deleted").contains(">Usuń wydarzenie<");
        assertThatThrownBy(() -> controller(Plans.enforced()).create(PARTY, "Wesele", WishService.today().toString(), null, user("owner"),
                new MockHttpSession(), new RedirectAttributesModelMap())).isInstanceOf(PlanRequiredException.class)
                .extracting("feature").isEqualTo(PlanFeature.WISHES);
        verify(wishes, never()).create(anyString(), anyString(), any(), any());
    }

    @Test
    void aFormsAnswer_isSaid_andAnEmptyEvent_tellsWhatToDo() throws IOException {
        when(wishes.wishes(7L)).thenReturn(List.of());
        when(wishes.songs(7L)).thenReturn(0);
        String html = render(Plans.off(), 7L, Map.of(ViewAttributes.WISH_DONE, "created", ViewAttributes.WISH_DONE_ARG,
                new String[]{"Wesele Ani i Kuby"}));
        assertThat(html).contains("id=\"wishDone\"", "Gotowe: „Wesele Ani i Kuby”. Skopiuj link albo wydrukuj zaproszenia.", "id=\"wishEmpty\"")
                .doesNotContain("id=\"wishCsv\"", "id=\"wishRows\"");
        write("wishes-empty.html", html);
        assertThat(render(Plans.off(), null, Map.of(ViewAttributes.WISH_DONE, "deleted", ViewAttributes.WISH_DONE_ARG, new String[0])))
                .contains("Usunięte razem z życzeniami.").doesNotContain("id=\"wishEvent\"");
    }

    @Test
    void create_needsANameAndADayAhead() {
        DjWishesController controller = controller(Plans.off());
        RedirectAttributesModelMap answer = new RedirectAttributesModelMap();
        assertThat(controller.create(PARTY, "  ", WishService.today().toString(), null, user("owner"), new MockHttpSession(), answer))
                .isEqualTo("redirect:/dj/wishes?party=WISH1");
        assertThat(answer.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("invalid");
        controller.create(PARTY, "Wesele", WishService.today().minusDays(1).toString(), null, user("owner"), new MockHttpSession(), answer);
        controller.create(PARTY, "Wesele", "14.06.2027", null, user("owner"), new MockHttpSession(), answer);
        verify(wishes, never()).create(anyString(), anyString(), any(), any());

        when(wishes.create(PARTY, "Wesele Ani i Kuby", WishService.today().plusDays(3), com.scan2play.model.GuestTheme.WEDDING)).thenReturn(Optional.of(event));
        assertThat(controller.create(PARTY, "  Wesele\tAni  i Kuby ", WishService.today().plusDays(3).toString(), "WEDDING", user("owner"),
                new MockHttpSession(), answer)).isEqualTo("redirect:/dj/wishes?party=WISH1&event=7");
    }

    /**
     * The two limits said: twenty events kept — no form, the page says to delete one; five open — "Wznów" refused and said, a
     * refused "Utwórz" says which limit it met.
     */
    @Test
    void theLimits_areSaid_twentyKept_andFiveOpenWhenReopening() {
        List<WishService.Event> twenty = new java.util.ArrayList<>();
        for (int i = 0; i < WishService.MAX_EVENTS; i++) {
            twenty.add(new WishService.Event(100 + i, PARTY, "Wish_tok3n-" + i, "Impreza " + i, WishService.today().plusDays(i), true, 0,
                    Instant.now(), com.scan2play.model.GuestTheme.CLASSIC));
        }
        when(wishes.eventsOf(PARTY)).thenReturn(twenty);
        String html = render(Plans.off(), null, Map.of());
        assertThat(html).doesNotContain("id=\"wishNewForm\"")
                .contains("Wydarzeń jest już 20 (razem z zakończonymi) — usuń któreś, żeby dodać kolejne.");

        DjWishesController controller = controller(Plans.off());
        RedirectAttributesModelMap created = new RedirectAttributesModelMap();
        when(wishes.create(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(wishes.events(PARTY)).thenReturn(WishService.MAX_EVENTS);
        controller.create(PARTY, "Wesele", WishService.today().toString(), null, user("owner"), new MockHttpSession(), created);
        assertThat(created.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("too_many_kept");
        when(wishes.events(PARTY)).thenReturn(WishService.MAX_OPEN_EVENTS);
        controller.create(PARTY, "Wesele", WishService.today().toString(), null, user("owner"), new MockHttpSession(), created);
        assertThat(created.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("too_many");

        RedirectAttributesModelMap reopened = new RedirectAttributesModelMap();
        when(wishes.reopen(PARTY, 7L)).thenReturn(WishService.Reopened.TOO_MANY);
        assertThat(controller.closed(7L, PARTY, false, user("owner"), new MockHttpSession(), reopened))
                .isEqualTo("redirect:/dj/wishes?party=WISH1&event=7");
        assertThat(reopened.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("reopen_too_many");
        assertThat(render(Plans.off(), 7L, Map.of(ViewAttributes.WISH_DONE, "reopen_too_many", ViewAttributes.WISH_DONE_ARG,
                new String[]{"5"}))).contains("Otwartych wydarzeń jest już 5 — zakończ zbieranie w jednym, żeby wznowić to.");
    }

    @Test
    void mustPlay_addsTheEventsSongs_neverAnotherText_andSaysWhatDidNotFit() {
        DjWishesController controller = controller(Plans.off());
        RedirectAttributesModelMap answer = new RedirectAttributesModelMap();
        controller.mustPlay(7L, PARTY, List.of("Wilki - Baśka", "Some text the event never had", "Akcent - Przekorny los"), false,
                user("owner"), new MockHttpSession(), answer);
        assertThat(party.getHostWanted()).isEqualTo("Akcent\nWilki - Baśka");
        assertThat(answer.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("must_play");
        assertThat((String[]) answer.getFlashAttributes().get(ViewAttributes.WISH_DONE_ARG)).containsExactly("1", "0");

        controller.mustPlay(7L, PARTY, null, true, user("owner"), new MockHttpSession(), answer);
        assertThat(party.getHostWanted().split("\n")).containsExactly("Akcent", "Wilki - Baśka", "Sanah - Szampan",
                "Dawid Podsiadło - Małomiasteczkowy (a long name that wraps on a phone, to see it does)");

        // a list of 99 other songs: of the four named ones one fits, the other three are said
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 99; i++) {
            full.append("Artysta ").append(i).append(" - Piosenka\n");
        }
        party.setHostWanted(full.toString().strip());
        RedirectAttributesModelMap later = new RedirectAttributesModelMap();
        controller.mustPlay(7L, PARTY, null, true, user("owner"), new MockHttpSession(), later);
        assertThat(party.getHostWanted().split("\n")).hasSize(100);
        assertThat(later.getFlashAttributes().get(ViewAttributes.WISH_DONE)).isEqualTo("must_play_full");
        assertThat((String[]) later.getFlashAttributes().get(ViewAttributes.WISH_DONE_ARG)).containsExactly("1", "3");
    }

    @Test
    void theCsv_hasEverySong_andTheLibrarysMark() {
        var response = controller(Plans.off()).csv(7L, PARTY, user("owner"), new MockHttpSession(), PL);
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Piosenka;Ile osób;W bibliotece\r\n")
                .contains("\"Wilki - Baśka\";7;\"tak\"\r\n", "\"Sanah - Szampan\";4;\"nie\"\r\n", "\"coś od zenka\";2;\r\n");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("scan2play-zyczenia-" + event.date() + ".csv");
    }
}
