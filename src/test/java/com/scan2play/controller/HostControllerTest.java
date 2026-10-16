package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The hosts' page (V29, {@code /h/{token}}): opened by its secret alone, an unknown or old secret is a 404. */
class HostControllerTest {

    private static final String PARTY = "ABC12";
    private static final String TOKEN = "AbC_123-xyzAbC_123-xyz";

    private PartySettingsRepository repository;
    private PartySettingsCommandService settingsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(PartySettingsRepository.class);
        settingsService = mock(PartySettingsCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HostController(repository, settingsService, com.scan2play.service.Plans.off())).build();
        when(repository.findByHostToken(anyString())).thenReturn(Optional.empty());
        when(repository.findByHostToken(TOKEN)).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode(PARTY)
                .djName("DJ Koko").hostBlocked("Akcent").hostToken(TOKEN).hostTokenMadeAt(java.time.Instant.now()).build()));
    }

    @Test
    void theHostsLink_showsTheirLists() throws Exception {
        mockMvc.perform(get("/h/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(view().name("host"))
                .andExpect(model().attribute("hostBlocked", "Akcent"))
                .andExpect(model().attribute("djName", "DJ Koko"))
                .andExpect(model().attribute("hostToken", TOKEN))
                .andExpect(model().attribute("songListMax", com.scan2play.util.SongList.MAX_LINES));
    }

    /** A list longer than the limit: the first {@code MAX_LINES} are kept, and the page says the rest did not fit (not only "saved"). */
    @Test
    void aListLongerThanTheLimit_keepsTheFirstOnes_andSaysTheRestDidNotFit() throws Exception {
        String many = java.util.stream.IntStream.rangeClosed(1, 120).mapToObj(i -> "Song " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        mockMvc.perform(post("/h/" + TOKEN).param("blocked", many).param("wanted", "Perfect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("hostSaved", true))
                .andExpect(flash().attribute("hostDropped", com.scan2play.util.SongList.MAX_LINES));
    }

    @Test
    void anUnknownOrOldLink_isNotFound_andNothingIsSaved() throws Exception {
        mockMvc.perform(get("/h/old-link")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h/old-link").param("blocked", "Akcent")).andExpect(status().isNotFound());
        mockMvc.perform(get("/h/" + "x".repeat(40))).andExpect(status().isNotFound());

        verify(settingsService, never()).updateSettings(any(), any());
    }

    /** The link works 30 days from when the DJ made it (V37, the review of 2026-10-14): then it is a 404, as an old one. */
    @Test
    void aLinkMadeMoreThan30DaysAgo_isNotFound_andNothingIsSaved() throws Exception {
        when(repository.findByHostToken(TOKEN)).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode(PARTY)
                .hostToken(TOKEN).hostTokenMadeAt(java.time.Instant.now().minus(java.time.Duration.ofDays(31))).build()));

        mockMvc.perform(get("/h/" + TOKEN)).andExpect(status().isNotFound());
        mockMvc.perform(post("/h/" + TOKEN).param("blocked", "Akcent")).andExpect(status().isNotFound());
        verify(settingsService, never()).updateSettings(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHostsSave_keepsOneEntryPerLine_andSaysItIsSaved() throws Exception {
        mockMvc.perform(post("/h/" + TOKEN).param("blocked", "").param("wanted", " Hej sokoły \n\nPerfect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/h/" + TOKEN))
                .andExpect(flash().attribute("hostSaved", true))
                .andExpect(flash().attributeCount(1));

        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).hostToken(TOKEN).hostBlocked("Akcent").build();
        updater.getValue().accept(party);
        assertThat(party.getHostBlocked()).isNull();
        assertThat(party.getHostWanted()).isEqualTo("Hej sokoły\nPerfect");
    }

    /**
     * An organiser without Pro, the plans enforced (V35): the hosts' link is off as an old one — a 404, nothing saved; the lists stay
     * for when Pro is back. With Pro, the link works.
     */
    @Test
    void withoutPro_theHostsLink_isNotFound_andNothingIsSaved() throws Exception {
        MockMvc free = MockMvcBuilders.standaloneSetup(new HostController(repository, settingsService,
                com.scan2play.service.Plans.enforced())).build();
        free.perform(get("/h/" + TOKEN)).andExpect(status().isNotFound());
        free.perform(post("/h/" + TOKEN).param("blocked", "Akcent")).andExpect(status().isNotFound());
        org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never()).updateSettings(anyString(), any());

        repository.findByHostToken(TOKEN).orElseThrow().setOwnerId("owner");
        MockMvc pro = MockMvcBuilders.standaloneSetup(new HostController(repository, settingsService,
                com.scan2play.service.Plans.enforced("owner"))).build();
        pro.perform(get("/h/" + TOKEN)).andExpect(status().isOk());
    }
}
