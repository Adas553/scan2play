package com.scan2play.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/**
 * For a {@code @WebMvcTest} of a guest's page: the {@link PartyScheduleService} with the app's words, on Friday 2026-10-30 at noon
 * ({@link PartySchedules#FRIDAY_NOON}) — its writes go to a mock.
 */
@TestConfiguration
public class PartySchedulesConfig {

    @Bean
    PartyScheduleService partyScheduleService() {
        return PartySchedules.of(mock(PartySettingsCommandService.class), PartySchedules.FRIDAY_NOON);
    }
}
