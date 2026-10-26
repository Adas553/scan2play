package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.LogoImage;
import com.scan2play.service.PartyLogoService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.TestImages;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DJ's logo (V45, Pro): the organiser sends the picture as the request's body and hears one sentence; the staff never. The
 * guests' pages get it from our address with its version as the ETag — a 304 without reading it, a 404 without a logo or without
 * the plan that shows it.
 */
class PartyLogoControllerTest {

    private static final String PUB = "PUB01";
    private static final String ORGANISER = "pub-owner";
    private static final String VERSION = "a".repeat(64);

    private final PartyLogoService logos = mock(PartyLogoService.class);

    private static OAuth2AuthenticationToken user(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", sub), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    private MockMvc mvc(PartySettingsEntity pub, PlanService plans) {
        PartyStaffService staff = mock(PartyStaffService.class);
        when(staff.partiesOf(anyString())).thenReturn(List.of(pub));
        when(staff.accessOf(pub, ORGANISER)).thenReturn(Optional.of(new PartyStaffService.Access(pub, true, StaffPermission.all())));
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.of(new PartyStaffService.Access(pub, false, StaffPermission.all())));
        PartySettingsQueryService query = mock(PartySettingsQueryService.class);
        when(query.getSettings(PUB)).thenReturn(pub);
        when(query.getSettings("NONE0")).thenThrow(new IllegalArgumentException("no party"));
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByOwnerId(anyString())).thenReturn(Optional.empty());
        DjSessionHelper helper = new DjSessionHelper(query, mock(PartySettingsCommandService.class), parties, staff);
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        return MockMvcBuilders.standaloneSetup(new PartyLogoController(logos, query, helper, plans, messages)).build();
    }

    private static PartySettingsEntity pub(String logoVersion) {
        return PartySettingsEntity.builder().partyCode(PUB).ownerId(ORGANISER).active(true).logoVersion(logoVersion).build();
    }

    private String upload(MockMvc mvc, String who, byte[] file, int status) throws Exception {
        return mvc.perform(post("/dj/logo").param("partyCode", PUB).contentType(MediaType.APPLICATION_OCTET_STREAM).content(file)
                        .principal(user(who)).session(new MockHttpSession()))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void aPicture_isTheNewLogo_asOurOwnPng() throws Exception {
        String answer = upload(mvc(pub(null), Plans.off()), ORGANISER, TestImages.jpeg(1024, 512), 200);

        ArgumentCaptor<LogoImage.Logo> saved = ArgumentCaptor.forClass(LogoImage.Logo.class);
        verify(logos).replace(eq(PUB), saved.capture());
        assertThat(saved.getValue().width()).isEqualTo(512);
        assertThat(saved.getValue().height()).isEqualTo(256);
        assertThat(answer).isEqualTo("settings.logo.uploaded");
    }

    @Test
    void anotherFile_isRefused_withItsReason_andNothingIsSaved() throws Exception {
        MockMvc mvc = mvc(pub(null), Plans.off());
        assertThat(upload(mvc, ORGANISER, "<svg/>".getBytes(StandardCharsets.UTF_8), 422)).isEqualTo("settings.logo.error.NOT_AN_IMAGE");
        assertThat(upload(mvc, ORGANISER, TestImages.pngHeaderOnly(50_000, 50_000), 422)).isEqualTo("settings.logo.error.TOO_MANY_PIXELS");
        assertThat(upload(mvc, ORGANISER, new byte[LogoImage.MAX_BYTES + 10], 413)).isEqualTo("settings.logo.error.TOO_LARGE");
        verify(logos, never()).replace(anyString(), any());
    }

    @Test
    void theStaff_neverUploadOrDelete() {
        MockMvc mvc = mvc(pub(null), Plans.off());
        assertThatThrownBy(() -> upload(mvc, "kasia", TestImages.png(10, 10), 200)).hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mvc.perform(post("/dj/logo/delete").param("partyCode", PUB).principal(user("kasia"))
                .session(new MockHttpSession()))).hasRootCauseInstanceOf(AccessDeniedException.class);
        verify(logos, never()).replace(anyString(), any());
        verify(logos, never()).delete(anyString());
    }

    @Test
    void delete_removesTheLogo_andComesBackToItsCard() throws Exception {
        mvc(pub(VERSION), Plans.enforced()).perform(post("/dj/logo/delete").param("partyCode", PUB).principal(user(ORGANISER))
                        .session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/settings?party=" + PUB + "#logoCard"));
        verify(logos).delete(PUB);
    }

    @Test
    void theLogo_isServedWithItsVersion_andAPageShownAgainGetsA304() throws Exception {
        byte[] png = TestImages.png(8, 8);
        when(logos.find(PUB)).thenReturn(Optional.of(new PartyLogoService.Stored(png, VERSION)));
        MockMvc mvc = mvc(pub(VERSION), Plans.off());

        mvc.perform(get("/p/" + PUB + "/logo.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().string("ETag", "\"" + VERSION + "\""))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"));

        mvc.perform(get("/p/" + PUB + "/logo.png").header("If-None-Match", "\"" + VERSION + "\""))
                .andExpect(status().isNotModified());
        verify(logos).find(PUB);   // once: the 304 never read the picture
    }

    @Test
    void noLogo_noParty_orAPlanWithoutIt_isA404() throws Exception {
        mvc(pub(null), Plans.off()).perform(get("/p/" + PUB + "/logo.png")).andExpect(status().isNotFound());
        mvc(pub(VERSION), Plans.off()).perform(get("/p/NONE0/logo.png")).andExpect(status().isNotFound());
        mvc(pub(VERSION), Plans.enforced()).perform(get("/p/" + PUB + "/logo.png")).andExpect(status().isNotFound());
        verify(logos, never()).find(anyString());
    }
}
