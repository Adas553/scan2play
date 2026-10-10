package com.scan2play.template;

import com.scan2play.controller.DjSessionHelper;
import com.scan2play.controller.StaffController;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.entity.StaffInvitationEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.ui.ConcurrentModel;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's page "Obsługa" (V32) and the page of someone with no panel at all, as the real controller and templates make them —
 * {@code staff.html} and {@code no-panel.html} for the browser tests ({@code target/browser-harness/}).
 */
class StaffPageRenderTest {

    private static final String PARTY = "HARN1";
    private static final Path OUT = Path.of("target", "browser-harness");
    private static final Locale PL = Locale.forLanguageTag("pl");

    private static SpringTemplateEngine engine;

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

    private static String render(String view, Map<String, Object> model, Locale locale) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model);
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "harness-csrf-token"));
        return engine.process(view, context);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    /**
     * Kasia with the role "Obsługa kolejki", Tomek with permissions ticked one by one, Ola invited by e-mail and waiting (V34); the
     * invitation link made with "Podgląd" (V33).
     */
    @Test
    void theStaffPage_listsWhatEachMayDo_theInvitations_andTheLink() throws IOException {
        PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").djName("Klub Ola")
                .staffToken("Inv_123-xyzInv_123-xyz").staffLinkPermissions(StaffRole.VIEWER.permissions()).build();
        DjSessionHelper helper = mock(DjSessionHelper.class);
        when(helper.requireOwner(any(), any(), any())).thenReturn(pub);
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.staffOf(PARTY)).thenReturn(List.of(
                PartyStaffEntity.builder().id(7L).partyCode(PARTY).memberId("kasia").memberName("Kasia")
                        .joinedAt(Instant.parse("2026-10-10T18:00:00Z")).permissions(StaffRole.QUEUE.permissions()).build(),
                PartyStaffEntity.builder().id(8L).partyCode(PARTY).memberId("tomek").memberName(null)
                        .joinedAt(Instant.parse("2026-10-09T18:00:00Z"))
                        .permissions(EnumSet.of(StaffPermission.QUEUE, StaffPermission.SUMMARY)).build()));
        when(staff.placesTaken(PARTY)).thenReturn(3L);
        StaffInvitationService invitations = mock(StaffInvitationService.class);
        when(invitations.waitingAt(PARTY)).thenReturn(List.of(StaffInvitationEntity.builder().id(3L).partyCode(PARTY)
                .email("ola.kowalska@gmail.com").emailKey("olakowalska@gmail.com").permissions(StaffRole.CO_ORGANISER.permissions())
                .invitedAt(Instant.parse("2026-10-10T08:00:00Z")).build()));
        StaffController controller = new StaffController(staff, mock(PartySettingsCommandService.class), helper, invitations);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("staffSaved", 7L);
        MockHttpServletRequest panelRequest = new MockHttpServletRequest();
        panelRequest.setServerName("127.0.0.1");
        panelRequest.setServerPort(8080);

        ConcurrentModel model = new ConcurrentModel();
        assertThat(controller.staffPage(PARTY, model, null, session, panelRequest)).isEqualTo("staff");
        String html = render("staff", model.asMap(), PL);
        write("staff.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("<h1 class=\"h3 text-white mb-1\">Obsługa imprezy</h1>", "Osoby, które pomagają Ci przy imprezie. Każdej wybierasz, co może",
                ">Kasia<", "w obsłudze od 10.10", ">Bez imienia<", "w obsłudze od 09.10",
                "action=\"/dj/staff/permissions\"", "name=\"partyCode\" value=\"HARN1\"", "name=\"id\" value=\"7\"",
                ">Podgląd<", ">Obsługa kolejki<", ">Współorganizator<", ">Własne<",
                "data-permissions=\"QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY\"",
                ">Zagrane, Pomiń, Cofnij, Przywróć<", ">Podsumowanie wieczoru<",
                "data-staff-saved", ">✓ Zapisano<",
                "action=\"/dj/staff/remove\"", "data-confirm=\"Usunąć dostęp: Kasia?\"",
                // the invitation follows the panel's address (Google's login works only there)
                "value=\"http://127.0.0.1:8080/join/Inv_123-xyzInv_123-xyz\"", "data-copy-target=\"staffLinkInput\"",
                "action=\"/dj/staff/link\"", ">Wyłącz link (osoby z listy zostają)<",
                "href=\"/dj/dashboard?party=HARN1\"", "/js/staff.js", "/js/dj-nav.js",
                // the invitation by e-mail waiting: the address, since when, the role, "Cofnij zaproszenie" (asks first)
                "data-invitation-id=\"3\"", ">ola.kowalska@gmail.com<", ">zaproszono 10.10 · czeka na zalogowanie<",
                ">Rola: Współorganizator<", "action=\"/dj/staff/invitation/cancel\"",
                "data-confirm=\"Cofnąć zaproszenie: ola.kowalska@gmail.com?\"", ">Cofnij zaproszenie<",
                // "Zaproś": the address and the role (Obsługa kolejki, as a joiner had it), one action button on the page
                "action=\"/dj/staff/invite\"", "type=\"email\"", "name=\"email\"", "id=\"role-invite\"", ">Zaproś<",
                // the link says its role, and a new one is made with the role picked beside it
                ">Ten link: Podgląd<", "id=\"role-link\"", ">Rola nowego linku (stary przestanie działać)<");
        assertThat(html.split("btn-action", -1)).as("one action button on the page").hasSize(2);
        String invite = html.substring(html.indexOf("id=\"staffInviteForm\""), html.indexOf("id=\"staffLinkCard\""));
        assertThat(invite).containsPattern("<option value=\"QUEUE\"[^>]*selected");
        String link = html.substring(html.indexOf("id=\"staffLinkForm\""));
        assertThat(link).containsPattern("<option value=\"VIEWER\"[^>]*selected")
                .containsPattern("value=\"HISTORY\"[^>]*checked").doesNotContainPattern("value=\"QUEUE\"[^>]*checked");
        assertThat(html).doesNotContain("id=\"staffFull\"", "id=\"staffInviteResult\"");
        // Kasia's role is picked; Tomek's ticks are no role's: "Własne", the ticks unfolded
        String kasia = html.substring(html.indexOf("data-staff-id=\"7\""), html.indexOf("data-staff-id=\"8\""));
        assertThat(kasia).containsPattern("<option value=\"QUEUE\"[^>]*selected").doesNotContain("<details class=\"mb-2 s2p-staff-perms\" open");
        String tomek = html.substring(html.indexOf("data-staff-id=\"8\""));
        assertThat(tomek).containsPattern("<option value=\"CUSTOM\"[^>]*selected").contains("<details class=\"mb-2 s2p-staff-perms\" open")
                .doesNotContain("data-staff-saved");
        assertThat(session.getAttribute("staffSaved")).as("\"Zapisano\" once").isNull();
    }

    /**
     * "Zaproś" with a typo, every place taken (V34): the page says why, keeps the address in the field to correct; no link made yet —
     * its role is the default one; nobody on the list yet.
     */
    @Test
    void theStaffPage_afterAWrongAddress_keepsIt_andSaysThePlacesAreTaken() throws IOException {
        PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").djName("Klub Ola").build();
        DjSessionHelper helper = mock(DjSessionHelper.class);
        when(helper.requireOwner(any(), any(), any())).thenReturn(pub);
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.placesTaken(PARTY)).thenReturn((long) PartyStaffService.MAX_STAFF);
        StaffController controller = new StaffController(staff, mock(PartySettingsCommandService.class), helper,
                mock(StaffInvitationService.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("staffInvited", new StaffController.InviteResult("INVALID_ADDRESS", "ola@gmail", true));

        ConcurrentModel model = new ConcurrentModel();
        controller.staffPage(PARTY, model, null, session, new MockHttpServletRequest());
        String html = render("staff", model.asMap(), PL);
        write("staff-invite-problem.html", html);

        assertThat(html).doesNotContain("??", "id=\"staffLinkInput\"", "id=\"staffLinkRole\"", "id=\"staffList\"");
        assertThat(html).contains("id=\"staffEmpty\"", "To nie wygląda na adres e-mail: ola@gmail", "alert alert-warning",
                "value=\"ola@gmail\"", "id=\"staffFull\"", "Zajęte 10 z 10 miejsc", ">Utwórz link zaproszenia<",
                ">Co może osoba, która dołączy przez link<");
        String link = html.substring(html.indexOf("id=\"staffLinkForm\""));
        assertThat(link).containsPattern("<option value=\"QUEUE\"[^>]*selected").doesNotContain("data-confirm");
    }

    /** No panel at all (the access taken away, no party of their own): what happened, an invitation to paste, a party on purpose. */
    @Test
    void noPanel_saysWhatHappened_andOffersTheWaysOn() throws IOException {
        String html = render("no-panel", Map.of("panelNote", new DjSessionHelper.Note("dashboard.staff.removed", "Klub Ola", true)), PL);
        write("no-panel.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("Organizator usunął Twój dostęp do imprezy: Klub Ola.", "Nie obsługujesz teraz żadnej imprezy",
                "action=\"/dj/join\"", "action=\"/dj/panel\"", ">Załóż własną imprezę<", "action=\"/dj/logout\"");
    }
}
