package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Nie wysyłaj mi tego" (V64): the link only asks — a mail filter that opens it switches nothing off —, the "Wyłącz" it shows (or the
 * mail program's one-click POST) switches the morning summaries off, by the secret alone; an unknown secret is a 404.
 */
class EmailStopControllerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private final PartySettingsRepository parties = mock(PartySettingsRepository.class);
    private final PartySettingsCommandService commands = mock(PartySettingsCommandService.class);
    private final EmailStopController controller = new EmailStopController(parties, commands);

    private PartySettingsEntity party(boolean morningSummary) {
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode("ABC12").ownerId("o").morningSummary(morningSummary).build();
        when(parties.findByEmailToken(TOKEN)).thenReturn(Optional.of(party));
        return party;
    }

    @Test
    void theLink_onlyAsks_andSwitchesNothingOff() {
        PartySettingsEntity party = party(true);

        assertThat(controller.ask(TOKEN)).isEqualTo("email-stop");
        assertThat(party.isMorningSummary()).isTrue();
        verify(commands, never()).updateSettings(any(), any());
    }

    @Test
    void theLink_ofSummariesAlreadyOff_saysSo() {
        party(false);
        assertThat(controller.ask(TOKEN)).isEqualTo("email-stopped");
        verify(commands, never()).updateSettings(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePost_switchesTheMorningSummaryOff_once() {
        PartySettingsEntity party = party(true);
        when(commands.updateSettings(any(), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(party);
            return party;
        });

        assertThat(controller.stop(TOKEN)).isEqualTo("email-stopped");
        assertThat(party.isMorningSummary()).isFalse();
        // a second "Wyłącz" (or the mail program's own "Unsubscribe") finds it off and writes nothing
        assertThat(controller.stop(TOKEN)).isEqualTo("email-stopped");
        verify(commands).updateSettings(any(), any());
    }

    @Test
    void anUnknownSecret_isA404() {
        when(parties.findByEmailToken(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.ask(TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.stop(TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.stop("short")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(parties, never()).findByEmailToken("short");
        verify(commands, never()).updateSettings(any(), any());
    }
}
