package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.SiteCounter;
import com.scan2play.service.SiteCounter.Event;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The site's counter for the owner (V40) and the organisers new this week with their address (V48): their account only — anyone else,
 * and everyone while it is unset, a 404.
 */
class StatsControllerTest {

    private static final String OWNER = "107402337416904056873";

    private final SiteCounter counter = mock(SiteCounter.class);
    private final PartySettingsRepository parties = mock(PartySettingsRepository.class);

    private static OAuth2AuthenticationToken login(String sub) {
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(), Map.of("sub", sub), "sub");
        return new OAuth2AuthenticationToken(user, List.of(), "google");
    }

    private static SiteCounter.Day day(String date, int landingPl, int landingEn, int start, int login, int newDj, int requests) {
        Map<Event, Integer> counts = new EnumMap<>(Event.class);
        counts.put(Event.LANDING_PL, landingPl);
        counts.put(Event.LANDING_EN, landingEn);
        counts.put(Event.START, start);
        counts.put(Event.LOGIN, login);
        counts.put(Event.NEW_DJ, newDj);
        counts.put(Event.GUEST_REQUEST, requests);
        counts.put(Event.DEMO, 0);
        return new SiteCounter.Day(LocalDate.parse(date), counts);
    }

    @Test
    void theOwner_seesTheDays_oneLineEach() {
        when(counter.lastDays(StatsController.DAYS)).thenReturn(List.of(day("2026-10-24", 15, 2, 2, 1, 1, 3),
                day("2026-10-23", 4, 0, 0, 0, 0, 0)));

        String text = new StatsController(counter, parties, OWNER).stats(login(OWNER));

        assertThat(text).contains("landing PL", "landing EN", "start", "login", "new DJ", "guest requests", "demo");
        assertThat(text.lines().filter(line -> line.startsWith("2026-10-24")).findFirst().orElseThrow()).matches(
                "2026-10-24\\s+15\\s+2\\s+2\\s+1\\s+1\\s+3\\s+0");
        assertThat(text.indexOf("2026-10-24")).isLessThan(text.indexOf("2026-10-23"));
    }

    @Test
    void anotherAccount_orNoOwnerSet_isA404() {
        assertThatThrownBy(() -> new StatsController(counter, parties, OWNER).stats(login("someone-else")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> new StatsController(counter, parties, "").stats(login(OWNER)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new StatsController(counter, parties, " ").stats(login(" ")))
                .as("a blank id is no owner").isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(counter, parties);
    }

    @Test
    void theOwner_seesTheNewOrganisers_withTheirAddress_fromTheLastWeek_atMostFifty() {
        when(counter.lastDays(StatsController.DAYS)).thenReturn(List.of());
        when(parties.newOrganisers(any(), any())).thenReturn(List.of(PartySettingsEntity.builder().partyCode("WED01")
                .ownerEmail("ola@gmail.com").ownerName("Ola Kowalska").createdAt(Instant.parse("2026-10-27T18:04:00Z")).build()));

        String text = new StatsController(counter, parties, OWNER).stats(login(OWNER));

        assertThat(text).contains("New organisers");
        assertThat(text.lines().filter(line -> line.contains("ola@gmail.com")).findFirst().orElseThrow())
                .matches("27\\.10\\.2026 19:04:00\\s+WED01\\s+ola@gmail\\.com\\s+Ola Kowalska");
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(parties).newOrganisers(since.capture(), eq(Limit.of(StatsController.NEW_MAX)));
        assertThat(Duration.between(since.getValue(), Instant.now())).isBetween(Duration.ofDays(7), Duration.ofDays(7).plusMinutes(1));
    }

    @Test
    void nobodyNew_saysSo() {
        assertThat(StatsController.renderNew(List.of())).contains("(nobody new)");
    }

    @Test
    void nothingCountedYet_saysSo() {
        assertThat(StatsController.render(List.of())).contains("(nothing counted yet)");
    }
}
