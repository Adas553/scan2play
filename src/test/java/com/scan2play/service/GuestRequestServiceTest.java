package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.VibeType;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The parts of a guest's request that are words, not limits: the wait as people say it, the style the AI judges by. The way of a
 * request itself — its limits in their order — is tested through the party page ({@code GuestControllerTest}) and the kiosk
 * ({@code KioskWebTest}).
 */
class GuestRequestServiceTest {

    private final GuestRequestService service = new GuestRequestService(mock(SongEvaluationService.class),
            mock(GuestSessionService.class), mock(GuestVoteService.class), mock(GuestRequestLimiter.class), mock(MessageSource.class),
            mock(SiteCounter.class));

    @Test
    void theWait_isSaidInWholeMinutes_roundedUp_andInSecondsBelowAMinute() {
        assertThat(GuestRequestService.waitText(1)).isEqualTo("1 s");
        assertThat(GuestRequestService.waitText(59)).isEqualTo("59 s");
        assertThat(GuestRequestService.waitText(60)).isEqualTo("1 min");
        assertThat(GuestRequestService.waitText(61)).isEqualTo("2 min");
        assertThat(GuestRequestService.waitText(117)).isEqualTo("2 min");
        assertThat(GuestRequestService.waitText(600)).isEqualTo("10 min");
    }

    /** The guests pick no vibe: without the DJ's vibe the AI judges by "any" (and the DJ's note, if any). */
    @Test
    void withoutTheDjsVibe_theStyleIsAny() {
        PartySettingsEntity settings = PartySettingsEntity.builder().partyCode("ABC12").globalVibe(VibeType.ANY).build();
        assertThat(service.styleOf(settings, Locale.ENGLISH)).isEqualTo("ANY");
        settings.setGlobalVibe(null);
        assertThat(service.styleOf(settings, Locale.ENGLISH)).as("no vibe saved yet").isEqualTo("ANY");
    }
}
