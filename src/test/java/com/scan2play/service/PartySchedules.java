package com.scan2play.service;

import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;

/** {@link PartyScheduleService}s for the tests (V55): with the app's own messages, the system's clock or the test's. */
public final class PartySchedules {

    /** Friday 2026-10-30, 12:00 in Poland (CET, the clocks went back on the 25th): the moment of the tests that show the words. */
    public static final Clock FRIDAY_NOON = Clock.fixed(Instant.parse("2026-10-30T11:00:00Z"), ZoneOffset.UTC);

    private PartySchedules() {
    }

    /** The app's bundles, as Spring has them ({@code spring.messages.*}). */
    public static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    /** Writing through these commands (a mock's, to verify). */
    public static PartyScheduleService of(PartySettingsCommandService commands) {
        return new PartyScheduleService(commands, messages());
    }

    /** Writing through these commands, at the moment the clock says. */
    public static PartyScheduleService of(PartySettingsCommandService commands, Clock clock) {
        return new PartyScheduleService(commands, messages(), clock);
    }

    /** Only the words (the panel's line, the guest's "Prośby od …"): its writes go to a mock. */
    public static PartyScheduleService texts() {
        return of(mock(PartySettingsCommandService.class));
    }
}
