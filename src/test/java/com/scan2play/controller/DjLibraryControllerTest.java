package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.DjLibraryParser;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.Plans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Twoja biblioteka" in the settings (V41, Pro): the organiser sends the file as it is (the request's body, not a form — so no
 * upload is read before the login and the CSRF token are checked), the server reads it and answers in one sentence. The staff
 * never: the library is the organiser's.
 */
class DjLibraryControllerTest {

    private static final String PUB = "PUB01";
    private static final String ORGANISER = "pub-owner";

    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId(ORGANISER).active(true).build();
    private DjLibraryService libraries;
    private MockMvc mvc;

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @BeforeEach
    void setUp() {
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.partiesOf(anyString())).thenReturn(List.of(pub));
        when(staff.accessOf(pub, ORGANISER)).thenReturn(Optional.of(new PartyStaffService.Access(pub, true, StaffPermission.all())));
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.of(new PartyStaffService.Access(pub, false, StaffPermission.all())));
        PartySettingsQueryService query = mock(PartySettingsQueryService.class);
        when(query.getSettings(PUB)).thenReturn(pub);
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByOwnerId(anyString())).thenReturn(Optional.empty());
        DjSessionHelper helper = new DjSessionHelper(query, mock(PartySettingsCommandService.class), parties, staff);
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        libraries = mock(DjLibraryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DjLibraryController(libraries, helper, Plans.off(), messages, 8192)).build();
    }

    private String upload(String who, byte[] file, int status) throws Exception {
        return mvc.perform(post("/dj/library").param("partyCode", PUB).contentType(MediaType.APPLICATION_OCTET_STREAM).content(file)
                        .principal(user(who)).session(new MockHttpSession()))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void anM3u_isTheNewLibrary() throws Exception {
        String answer = upload(ORGANISER, "#EXTM3U\n#EXTINF:1,Wilki - Baśka\nC:\\a.mp3\nABBA - Dancing Queen.mp3\n"
                .getBytes(StandardCharsets.UTF_8), 200);

        ArgumentCaptor<Library> saved = ArgumentCaptor.forClass(Library.class);
        verify(libraries).replace(eq(PUB), saved.capture());
        assertThat(saved.getValue().tracks()).isEqualTo(2);
        assertThat(saved.getValue().keys()).contains(com.scan2play.util.LibraryKey.of("Wilki - Baśka"), com.scan2play.util.LibraryKey.of("ABBA - Dancing Queen"));
        assertThat(answer).isEqualTo("settings.library.uploaded");
    }

    @Test
    void anotherFile_isRefused_withItsReason() throws Exception {
        assertThat(upload(ORGANISER, "<html></html>".getBytes(StandardCharsets.UTF_8), 422))
                .isEqualTo("settings.library.error." + DjLibraryParser.UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
        verify(libraries, never()).replace(anyString(), any());
    }

    /** Past the most bytes the reading stops: nothing is saved, whatever the file says it is (here: a body of no declared size). */
    @Test
    void aFileTooLarge_isRefused() throws Exception {
        assertThat(upload(ORGANISER, ("#EXTM3U\n" + "Wilki - Baśka.mp3\n".repeat(1000)).getBytes(StandardCharsets.UTF_8), 413))
                .isEqualTo("settings.library.error.TOO_LARGE");
        StringBuilder collection = new StringBuilder("<DJ_PLAYLISTS><COLLECTION>");
        for (int i = 0; i < 500; i++) {
            collection.append("<TRACK Name=\"Song ").append(i).append("\" Artist=\"Artist\"/>");
        }
        assertThat(upload(ORGANISER, collection.append("</COLLECTION></DJ_PLAYLISTS>").toString().getBytes(StandardCharsets.UTF_8), 413))
                .as("a collection, read by StAX").isEqualTo("settings.library.error.TOO_LARGE");
        verify(libraries, never()).replace(anyString(), any());
    }

    @Test
    void theStaff_neverUploadNorDelete() {
        assertThatThrownBy(() -> upload("kasia", "#EXTM3U\nA - B.mp3\n".getBytes(StandardCharsets.UTF_8), 200))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mvc.perform(post("/dj/library/delete").param("partyCode", PUB).principal(user("kasia"))
                .session(new MockHttpSession()))).hasRootCauseInstanceOf(AccessDeniedException.class);
        verify(libraries, never()).replace(anyString(), any());
        verify(libraries, never()).delete(anyString());
    }

    @Test
    void delete_goesBackToTheSettings() throws Exception {
        mvc.perform(post("/dj/library/delete").param("partyCode", PUB).principal(user(ORGANISER)).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/settings?party=PUB01#libraryCard"));
        verify(libraries).delete(PUB);
    }

    @Test
    void theAnswer_isPlainText() throws Exception {
        mvc.perform(post("/dj/library").param("partyCode", PUB).contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("#EXTM3U\nA - B.mp3\n".getBytes(StandardCharsets.UTF_8)).principal(user(ORGANISER)).session(new MockHttpSession()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    /** One playlist of a rekordbox collection (V42): named in the address, its tracks alone kept; one not in the file is said so. */
    @Test
    void onePlaylist_ofACollection() throws Exception {
        byte[] collection = new org.springframework.core.io.ClassPathResource("library/rekordbox.xml").getContentAsByteArray();
        mvc.perform(post("/dj/library").param("partyCode", PUB).param("playlist", "Kluby / Piątek")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(collection).principal(user(ORGANISER)).session(new MockHttpSession()))
                .andExpect(status().isOk());
        ArgumentCaptor<Library> saved = ArgumentCaptor.forClass(Library.class);
        verify(libraries).replace(eq(PUB), saved.capture());
        assertThat(saved.getValue().playlist()).isEqualTo("Kluby / Piątek");
        assertThat(saved.getValue().tracks()).isEqualTo(2);

        String answer = mvc.perform(post("/dj/library").param("partyCode", PUB).param("playlist", "Sylwester")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(collection).principal(user(ORGANISER)).session(new MockHttpSession()))
                .andExpect(status().is(422)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(answer).isEqualTo("settings.library.error.NO_PLAYLIST");
    }
}
