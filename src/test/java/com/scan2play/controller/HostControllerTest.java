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
        mockMvc = MockMvcBuilders.standaloneSetup(new HostController(repository, settingsService)).build();
        when(repository.findByHostToken(anyString())).thenReturn(Optional.empty());
        when(repository.findByHostToken(TOKEN)).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode(PARTY)
                .djName("DJ Koko").hostBlocked("Akcent").hostToken(TOKEN).build()));
    }

    @Test
    void theHostsLink_showsTheirLists() throws Exception {
        mockMvc.perform(get("/h/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(view().name("host"))
                .andExpect(model().attribute("hostBlocked", "Akcent"))
                .andExpect(model().attribute("djName", "DJ Koko"))
                .andExpect(model().attribute("hostToken", TOKEN));
    }

    @Test
    void anUnknownOrOldLink_isNotFound_andNothingIsSaved() throws Exception {
        mockMvc.perform(get("/h/old-link")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h/old-link").param("blocked", "Akcent")).andExpect(status().isNotFound());
        mockMvc.perform(get("/h/" + "x".repeat(40))).andExpect(status().isNotFound());

        verify(settingsService, never()).updateSettings(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHostsSave_keepsOneEntryPerLine_andSaysItIsSaved() throws Exception {
        mockMvc.perform(post("/h/" + TOKEN).param("blocked", "").param("wanted", " Hej sokoły \n\nPerfect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/h/" + TOKEN))
                .andExpect(flash().attribute("hostSaved", true));

        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).hostBlocked("Akcent").build();
        updater.getValue().accept(party);
        assertThat(party.getHostBlocked()).isNull();
        assertThat(party.getHostWanted()).isEqualTo("Hej sokoły\nPerfect");
    }
}
