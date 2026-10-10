package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PartyStaffService.JoinOutcome;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.entity.StaffInvitationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The staff's invitation — by the link with its role (V33) and by e-mail (V34) —, the panel switcher, leaving a staff and the owner's
 * page "Obsługa" (V30, V32).
 */
class StaffControllerTest {

    private static final String TOKEN = "invite-token";
    private static final String PUB = "PUB01";

    private PartyStaffService staff;
    private PartySettingsCommandService settings;
    private DjSessionHelper sessionHelper;
    private StaffInvitationService invitations;
    private MockMvc mockMvc;
    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId("pub-owner").djName("Klub Ola")
            .ownerName("Ola Kowalska").build();

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffService.class);
        settings = mock(PartySettingsCommandService.class);
        sessionHelper = mock(DjSessionHelper.class);
        invitations = mock(StaffInvitationService.class);
        // views under a prefix of their own: the view "staff" of /dj/staff is not the handler's own address again
        mockMvc = MockMvcBuilders.standaloneSetup(new StaffController(staff, settings, sessionHelper, invitations))
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver("/WEB-INF/views/", ".html")).build();
        when(staff.partyOfLink(anyString())).thenReturn(Optional.empty());
        when(staff.partyOfLink(TOKEN)).thenReturn(Optional.of(pub));
        when(sessionHelper.switchTo(eq(PUB), any(), any())).thenReturn(pub);
    }

    private static OAuth2AuthenticationToken user(String id) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", id, "name", "Kasia"), "sub"), AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    /** A logged-in account with the address Google gives ({@code email_verified} as Google's OpenID user says it: a boolean). */
    private static OAuth2AuthenticationToken user(String id, String email, Object verified) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", id, "name", "Kasia", "email", email, "email_verified", verified), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    // ---------------------------------------------------------------- the invitation

    @Test
    void theInvitation_namesThePartyAndWhoInvites_keepsTheTokenForTheLogin_andLeadsThroughGoogle() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/join/" + TOKEN).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("join"))
                .andExpect(model().attribute("joinPartyName", "Klub Ola"))
                .andExpect(model().attribute("joinOwnerName", "Ola Kowalska"))
                .andExpect(model().attribute("joinRole", StaffRole.QUEUE))
                .andExpect(model().attribute("joinPermissions", List.copyOf(StaffRole.QUEUE.permissions())))
                .andExpect(model().attribute("joinLoggedIn", false));

        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isEqualTo(TOKEN);
        verify(staff, never()).join(any(), any(), any());
    }

    /**
     * Logged in, opening the link only asks (the review, 2026-10-10: the panel joined whoever had the token in the session — two
     * navigations of a foreign site could join a logged-in DJ to its party).
     */
    @Test
    void loggedInAlready_theInvitationAsks_andJoinsNothingByItself() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(StaffController.SESSION_PENDING_INVITATION, TOKEN);

        mockMvc.perform(get("/join/" + TOKEN).principal(user("kasia")).session(session))
                .andExpect(view().name("join"))
                .andExpect(model().attribute("joinLoggedIn", true))
                .andExpect(model().attribute("joinToken", TOKEN));

        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).as("asked now: nothing waits").isNull();
        verify(staff, never()).join(any(), any(), any());
    }

    @Test
    void join_joinsAndOpensTheParty_withANote() throws Exception {
        when(staff.join(TOKEN, "kasia", "Kasia")).thenReturn(new PartyStaffService.Joined(JoinOutcome.JOINED, pub));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/join/" + TOKEN).principal(user("kasia")).session(session))
                .andExpect(redirectedUrl("/dj/dashboard?party=PUB01"));

        verify(sessionHelper).switchTo(eq(PUB), any(), eq(session));
        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.staff.joined", "Klub Ola", false));
    }

    @Test
    void join_withoutALogin_goesThroughGoogleFirst() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/join/" + TOKEN).session(session)).andExpect(redirectedUrl("/start"));

        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isEqualTo(TOKEN);
        verify(staff, never()).join(any(), any(), any());
    }

    @Test
    void aFullStaff_andAnOldLink_sayWhatHappened() throws Exception {
        when(staff.join(TOKEN, "kasia", "Kasia")).thenReturn(new PartyStaffService.Joined(JoinOutcome.FULL, pub));
        mockMvc.perform(post("/join/" + TOKEN).principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(status().isConflict())
                .andExpect(view().name("join"))
                .andExpect(model().attribute("joinProblem", "join.problem.full"))
                .andExpect(model().attribute("joinPartyName", "Klub Ola"));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/join/old-token").session(session))
                .andExpect(status().isNotFound())
                .andExpect(model().attribute("joinProblem", "join.problem.old_link"));
        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isNull();
        verify(sessionHelper, never()).switchTo(any(), any(), any());
    }

    /** A link pasted in the app (its browser has a login of its own): a party's link goes to its invitation, which asks. */
    @Test
    void aPastedLink_leadsToItsInvitation_whateverShapeItIsPastedIn() throws Exception {
        when(staff.partyOfLink("AbC_12-x")).thenReturn(Optional.of(pub));
        for (String link : new String[]{"https://www.scan2play.com.pl/join/AbC_12-x", "  http://localhost:8080/join/AbC_12-x/  ", "AbC_12-x"}) {
            mockMvc.perform(post("/dj/join").param("link", link).principal(user("kasia")).session(new MockHttpSession()))
                    .andExpect(redirectedUrl("/join/AbC_12-x"));
        }
    }

    /** Anything else back to the panel with what it was: a guests' link told apart (it never was an invitation). */
    @Test
    void aPastedGuestsLink_orAnOldOne_isBackOnThePanel_withWhatItWas() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dj/join").param("link", "https://www.scan2play.com.pl/p/AB12C").principal(user("kasia")).session(session))
                .andExpect(redirectedUrl("/dj/dashboard"));
        mockMvc.perform(post("/dj/join").param("link", "https://www.scan2play.com.pl/join/old-token").principal(user("kasia")).session(session))
                .andExpect(redirectedUrl("/dj/dashboard"));

        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.join.guests_link", null, true));
        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.staff.old_link", null, true));
    }

    /** "Masz zaproszenie…? Dołącz →" on the landing page: a party's link goes to Google's login; anything else back, before a login. */
    @Test
    void theLinkPastedOnTheLandingPage_goesToTheLogin_orBackWithANote() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/join").param("link", " https://www.scan2play.com.pl/join/" + TOKEN + " ").session(session))
                .andExpect(redirectedUrl("/start"));
        assertThat(session.getAttribute(StaffController.SESSION_PENDING_INVITATION)).isEqualTo(TOKEN);

        for (String bad : new String[]{"https://www.scan2play.com.pl/join/old-token", "not a link at all ?", ""}) {
            MockHttpSession other = new MockHttpSession();
            mockMvc.perform(post("/join").param("link", bad).session(other)).andExpect(redirectedUrl("/?staffLink=invalid"));
            assertThat(other.getAttribute(StaffController.SESSION_PENDING_INVITATION)).as(bad).isNull();
        }
        mockMvc.perform(post("/join").param("link", "https://www.scan2play.com.pl/p/AB12C").session(new MockHttpSession()))
                .andExpect(redirectedUrl("/?staffLink=guests"));
    }

    /** The link says the role it was made with (V33): "Podgląd", or the permissions ticked one by one. */
    @Test
    void theInvitation_saysTheRoleOfItsLink() throws Exception {
        pub.setStaffLinkPermissions(EnumSet.of(StaffPermission.HISTORY));
        mockMvc.perform(get("/join/" + TOKEN).session(new MockHttpSession()))
                .andExpect(model().attribute("joinRole", StaffRole.VIEWER))
                .andExpect(model().attribute("joinPermissions", List.of(StaffPermission.HISTORY)));

        pub.setStaffLinkPermissions(EnumSet.of(StaffPermission.SUMMARY, StaffPermission.QUEUE));
        mockMvc.perform(get("/join/" + TOKEN).session(new MockHttpSession()))
                .andExpect(model().attribute("joinRole", StaffRole.CUSTOM))
                .andExpect(model().attribute("joinPermissions", List.of(StaffPermission.QUEUE, StaffPermission.SUMMARY)));
        pub.setStaffLinkPermissions(null);
    }

    // ---------------------------------------------------------------- the invitation by e-mail (V34)

    /** Only an address Google verified counts: an unverified one could be anyone's. */
    @Test
    void theAddress_countsOnlyWhenGoogleVerifiedIt() {
        assertThat(StaffController.verifiedAddress(user("kasia", "kasia@gmail.com", true))).isEqualTo("kasia@gmail.com");
        assertThat(StaffController.verifiedAddress(user("kasia", "kasia@gmail.com", "true"))).isEqualTo("kasia@gmail.com");
        assertThat(StaffController.verifiedAddress(user("kasia", "kasia@gmail.com", false))).isNull();
        assertThat(StaffController.verifiedAddress(user("kasia"))).as("no address given").isNull();
        assertThat(StaffController.verifiedAddress(null)).isNull();
    }

    private static StaffInvitationEntity invitation(long id, Set<StaffPermission> permissions) {
        return StaffInvitationEntity.builder().id(id).partyCode(PUB).email("kasia@gmail.com").emailKey("kasia@gmail.com")
                .permissions(permissions).invitedAt(Instant.now()).build();
    }

    /** The panel sends a person with an invitation waiting here: the party, who invites, the role; answered by a POST. */
    @Test
    void anInvitationByEmail_asks_withItsRole() throws Exception {
        when(invitations.waitingFor("kasia@gmail.com")).thenReturn(Optional.of(
                new StaffInvitationService.Waiting(invitation(3L, StaffRole.CO_ORGANISER.permissions()), pub)));

        mockMvc.perform(get("/dj/invitation").principal(user("kasia", "kasia@gmail.com", true)).session(new MockHttpSession()))
                .andExpect(view().name("join"))
                .andExpect(model().attribute("joinPartyName", "Klub Ola"))
                .andExpect(model().attribute("joinOwnerName", "Ola Kowalska"))
                .andExpect(model().attribute("joinInvitationId", 3L))
                .andExpect(model().attribute("joinLoggedIn", true))
                .andExpect(model().attribute("joinRole", StaffRole.CO_ORGANISER));

        // nothing waiting (answered in another tab, an unverified address): the panel
        mockMvc.perform(get("/dj/invitation").principal(user("kasia", "kasia@gmail.com", false)).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard"));
        verify(invitations).waitingFor(null);
    }

    @Test
    void dolacz_onAnInvitationByEmail_opensTheParty_orSaysWhatHappened() throws Exception {
        when(invitations.accept(3L, "kasia@gmail.com", "kasia", "Kasia"))
                .thenReturn(new StaffInvitationService.Answer(StaffInvitationService.AnswerOutcome.JOINED, pub));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dj/invitation/accept").param("id", "3").principal(user("kasia", "kasia@gmail.com", true)).session(session))
                .andExpect(redirectedUrl("/dj/dashboard?party=PUB01"));
        verify(sessionHelper).switchTo(eq(PUB), any(), eq(session));
        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.staff.joined", "Klub Ola", false));

        when(invitations.accept(4L, "kasia@gmail.com", "kasia", "Kasia"))
                .thenReturn(new StaffInvitationService.Answer(StaffInvitationService.AnswerOutcome.GONE, null));
        MockHttpSession gone = new MockHttpSession();
        mockMvc.perform(post("/dj/invitation/accept").param("id", "4").principal(user("kasia", "kasia@gmail.com", true)).session(gone))
                .andExpect(redirectedUrl("/dj/dashboard"));
        verify(sessionHelper).note(gone, new DjSessionHelper.Note("dashboard.invitation.gone", null, true));

        when(invitations.accept(5L, "kasia@gmail.com", "kasia", "Kasia"))
                .thenReturn(new StaffInvitationService.Answer(StaffInvitationService.AnswerOutcome.FULL, pub));
        mockMvc.perform(post("/dj/invitation/accept").param("id", "5").principal(user("kasia", "kasia@gmail.com", true))
                        .session(new MockHttpSession()))
                .andExpect(status().isConflict())
                .andExpect(model().attribute("joinProblem", "join.problem.full"));
    }

    /** "Nie, dziękuję": the invitation goes; the panel says so (and makes no DJ's party for someone who came to help). */
    @Test
    void nieDziekuje_declinesTheInvitation() throws Exception {
        when(invitations.decline(3L, "kasia@gmail.com")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dj/invitation/decline").param("id", "3").principal(user("kasia", "kasia@gmail.com", true)).session(session))
                .andExpect(redirectedUrl("/dj/dashboard"));
        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.invitation.declined", null, false));
    }

    // ---------------------------------------------------------------- the person of the staff

    /** "Opuść obsługę": a person of the staff leaves the party the page shows; the owner of it leaves nothing. */
    @Test
    void leavingTheStaff_isForTheStaff_ofThePartyThePageShows() throws Exception {
        when(sessionHelper.access(eq(PUB), any(), any())).thenReturn(new PartyStaffService.Access(pub, false, StaffRole.QUEUE.permissions()));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dj/staff/leave").param("partyCode", PUB).principal(user("kasia")).session(session))
                .andExpect(redirectedUrl("/dj/dashboard"));
        verify(staff).leave(PUB, "kasia");
        // no longer her default panel: the next one says she left, not that the organiser took the access away
        verify(sessionHelper).forget(PUB, session);
        verify(sessionHelper).note(session, new DjSessionHelper.Note("dashboard.staff.left", "Klub Ola", false));

        when(sessionHelper.access(eq(PUB), any(), any())).thenReturn(new PartyStaffService.Access(pub, true, StaffPermission.all()));
        mockMvc.perform(post("/dj/staff/leave").param("partyCode", PUB).principal(user("pub-owner")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard"));
        verify(staff, never()).leave(PUB, "pub-owner");
        verify(sessionHelper, never()).switchToOwnParty(any(), any());
    }

    @Test
    void theSwitcher_opensAPartyTheyWorkAt_orMakesTheirOwn() throws Exception {
        when(sessionHelper.switchToOwnParty(any(), any())).thenReturn(PartySettingsEntity.builder().partyCode("OWN01").build());

        mockMvc.perform(post("/dj/panel").param("party", PUB).principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard?party=PUB01"));
        mockMvc.perform(post("/dj/panel").principal(user("kasia")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/dashboard?party=OWN01"));

        verify(sessionHelper).switchTo(eq(PUB), any(), any());
        verify(sessionHelper).switchToOwnParty(any(), any());
    }

    // ---------------------------------------------------------------- the owner's page

    @Test
    void theStaffPage_isTheOwners() throws Exception {
        when(sessionHelper.requireOwner(eq(PUB), any(), any())).thenReturn(pub);
        mockMvc.perform(get("/dj/staff").param("party", PUB).principal(user("pub-owner")).session(new MockHttpSession()))
                .andExpect(view().name("staff"))
                .andExpect(model().attribute("partyCode", PUB))
                .andExpect(model().attribute("staffRoles", StaffRole.values()));

        doThrow(new AccessDeniedException("staff")).when(sessionHelper).requireOwner(eq(PUB), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(get("/dj/staff").param("party", PUB).principal(user("kasia")).session(new MockHttpSession())))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    /** A role gives its set; "Własne" the ticked permissions; the owner's alone, a row of their own party only (the service checks it). */
    @Test
    void thePermissions_areARolesSet_orTheTickedOnes_savedByTheOwnerOnly() throws Exception {
        when(staff.setPermissions(eq(PUB), anyLong(), any())).thenReturn(true);
        mockMvc.perform(post("/dj/staff/permissions").param("partyCode", PUB).param("id", "7").param("role", "VIEWER")
                        .param("permissions", "QUEUE", "SUMMARY").principal(user("pub-owner")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        mockMvc.perform(post("/dj/staff/permissions").param("partyCode", PUB).param("id", "7").param("role", "CUSTOM")
                        .param("permissions", "QUEUE", "SUMMARY").principal(user("pub-owner")).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        mockMvc.perform(post("/dj/staff/permissions").param("partyCode", PUB).param("id", "7").param("role", "CUSTOM")
                .principal(user("pub-owner")).session(new MockHttpSession()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<StaffPermission>> granted = ArgumentCaptor.forClass(Set.class);
        verify(staff, times(3)).setPermissions(eq(PUB), eq(7L), granted.capture());
        assertThat(granted.getAllValues()).containsExactly(EnumSet.of(StaffPermission.HISTORY),
                EnumSet.of(StaffPermission.QUEUE, StaffPermission.SUMMARY), EnumSet.noneOf(StaffPermission.class));
        verify(sessionHelper, times(3)).requireOwner(eq(PUB), any(), any());

        doThrow(new AccessDeniedException("staff")).when(sessionHelper).requireOwner(eq("OTHER"), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(post("/dj/staff/permissions").param("partyCode", "OTHER").param("id", "8")
                .param("role", "CO_ORGANISER").principal(user("kasia")).session(new MockHttpSession())))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verify(staff, never()).setPermissions(eq("OTHER"), anyLong(), any());
    }

    /** The invitation link: the owner's, a new secret each time, "off" none, anything else a 400. */
    @Test
    @SuppressWarnings("unchecked")
    void theInvitationLink_isANewSecretEachTime_offTakesItAway() throws Exception {
        for (String link : new String[]{"new", "off"}) {
            mockMvc.perform(post("/dj/staff/link").param("partyCode", PUB).param("link", link).principal(user("pub-owner"))
                    .session(new MockHttpSession())).andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        }
        mockMvc.perform(post("/dj/staff/link").param("partyCode", PUB).param("link", "x").principal(user("pub-owner"))
                .session(new MockHttpSession())).andExpect(status().isBadRequest());

        verify(sessionHelper, times(3)).requireOwner(eq(PUB), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settings, times(2)).updateSettings(eq(PUB), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PUB).build();
        updater.getAllValues().get(0).accept(party);
        assertThat(party.getStaffToken()).hasSize(22);
        assertThat(party.getStaffLinkPermissions()).as("no role sent: the default").isEqualTo(StaffRole.DEFAULT.permissions());
        updater.getAllValues().get(1).accept(party);
        assertThat(party.getStaffToken()).isNull();
        assertThat(party.getStaffLinkPermissions()).isNull();
    }

    /** A new link carries the role picked beside it (V33): a role's set, or with "Własne" the ticked permissions. */
    @Test
    @SuppressWarnings("unchecked")
    void aNewLink_carriesTheRolePickedWithIt() throws Exception {
        mockMvc.perform(post("/dj/staff/link").param("partyCode", PUB).param("link", "new").param("role", "VIEWER")
                .param("permissions", "QUEUE").principal(user("pub-owner")).session(new MockHttpSession()));
        mockMvc.perform(post("/dj/staff/link").param("partyCode", PUB).param("link", "new").param("role", "CUSTOM")
                .param("permissions", "QUEUE", "SUMMARY").principal(user("pub-owner")).session(new MockHttpSession()));

        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settings, times(2)).updateSettings(eq(PUB), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PUB).build();
        updater.getAllValues().get(0).accept(party);
        assertThat(party.getStaffLinkPermissions()).containsExactly(StaffPermission.HISTORY);
        String first = party.getStaffToken();
        updater.getAllValues().get(1).accept(party);
        assertThat(party.getStaffLinkPermissions()).containsExactly(StaffPermission.QUEUE, StaffPermission.SUMMARY);
        assertThat(party.getStaffToken()).as("another role is another link").isNotEqualTo(first);
    }

    /** "Zaproś": the owner's; the address and the role go to the service; the page says what came of it (the address kept). */
    @Test
    void zapros_invitesAnAddress_withTheRole_andThePageSaysWhatCameOfIt() throws Exception {
        when(invitations.invite(eq(PUB), any(), any())).thenReturn(StaffInvitationService.InviteOutcome.INVITED);
        when(invitations.invite(PUB, "ola", StaffRole.VIEWER.permissions())).thenReturn(StaffInvitationService.InviteOutcome.INVALID_ADDRESS);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dj/staff/invite").param("partyCode", PUB).param("email", " Ola@Gmail.com ").param("role", "CUSTOM")
                        .param("permissions", "HISTORY", "SUMMARY").principal(user("pub-owner")).session(session))
                .andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        verify(invitations).invite(PUB, " Ola@Gmail.com ", EnumSet.of(StaffPermission.HISTORY, StaffPermission.SUMMARY));
        assertThat(session.getAttribute(StaffController.SESSION_STAFF_INVITED))
                .isEqualTo(new StaffController.InviteResult("INVITED", "Ola@Gmail.com", false));

        mockMvc.perform(post("/dj/staff/invite").param("partyCode", PUB).param("email", "ola").param("role", "VIEWER")
                .principal(user("pub-owner")).session(session));
        assertThat(session.getAttribute(StaffController.SESSION_STAFF_INVITED))
                .as("a wrong address stays in the field").isEqualTo(new StaffController.InviteResult("INVALID_ADDRESS", "ola", true));

        // the page shows it once
        when(sessionHelper.requireOwner(eq(PUB), any(), any())).thenReturn(pub);
        mockMvc.perform(get("/dj/staff").param("party", PUB).principal(user("pub-owner")).session(session))
                .andExpect(model().attribute("staffInviteResult", new StaffController.InviteResult("INVALID_ADDRESS", "ola", true)));
        assertThat(session.getAttribute(StaffController.SESSION_STAFF_INVITED)).isNull();

        doThrow(new AccessDeniedException("staff")).when(sessionHelper).requireOwner(eq("OTHER"), any(), any());
        assertThatThrownBy(() -> mockMvc.perform(post("/dj/staff/invite").param("partyCode", "OTHER").param("email", "x@y.pl")
                .principal(user("kasia")).session(new MockHttpSession()))).hasRootCauseInstanceOf(AccessDeniedException.class);
        verify(invitations, never()).invite(eq("OTHER"), any(), any());
    }

    @Test
    void cancellingAnInvitation_isTheOwners_ofTheirOwnParty() throws Exception {
        mockMvc.perform(post("/dj/staff/invitation/cancel").param("partyCode", PUB).param("id", "3").principal(user("pub-owner"))
                .session(new MockHttpSession())).andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        verify(invitations).cancel(PUB, 3L);
    }

    /** Taking a person's access away: checked as the owner's, then only a row of the owner's own party. */
    @Test
    void removingAccess_isTheOwners_andOfTheirOwnParty() throws Exception {
        doThrow(new AccessDeniedException("staff")).when(sessionHelper).requireOwner(eq("OTHER"), any(), any());

        mockMvc.perform(post("/dj/staff/remove").param("partyCode", PUB).param("id", "7").principal(user("pub-owner"))
                .session(new MockHttpSession())).andExpect(redirectedUrl("/dj/staff?party=PUB01"));
        assertThatThrownBy(() -> mockMvc.perform(post("/dj/staff/remove")
                .param("partyCode", "OTHER").param("id", "8").principal(user("kasia")).session(new MockHttpSession())))
                .hasRootCauseInstanceOf(AccessDeniedException.class);

        verify(staff).remove(PUB, 7L);
        verify(staff, never()).remove(eq("OTHER"), anyLong());
    }
}
