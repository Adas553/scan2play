package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The organisers new this week with their address (V48, {@code /dj/stats}) on a real PostgreSQL: the account's day comes from the
 * database when the party is made and no save of the settings changes it; the list is the last 7 days, those with an address, the
 * newest first, bounded.
 */
class NewOrganisersIT extends PostgresIntegrationTest {

    @Autowired PartySettingsRepository parties;
    @Autowired PartySettingsCommandService commands;
    @Autowired JdbcTemplate jdbc;

    private PartySettingsEntity newOrganiser(String email) {
        String owner = "new-" + System.nanoTime();
        PartySettingsEntity party = commands.getOrCreatePartyForDj(owner);
        return commands.updateSettings(party.getPartyCode(), s -> s.setOwnerEmail(email));
    }

    private void madeDaysAgo(PartySettingsEntity party, int days) {
        jdbc.update("UPDATE party_settings SET created_at = now() - make_interval(days => ?) WHERE party_code = ?", days,
                party.getPartyCode());
    }

    private List<String> newThisWeek() {
        return parties.newOrganisers(Instant.now().minus(Duration.ofDays(7)), Limit.of(50)).stream()
                .map(PartySettingsEntity::getOwnerEmail).toList();
    }

    @Test
    void aNewParty_getsItsDayFromTheDatabase_andASaveOfTheSettingsKeepsIt() {
        PartySettingsEntity party = newOrganiser("day." + System.nanoTime() + "@gmail.com");
        Instant made = parties.findByPartyCode(party.getPartyCode()).orElseThrow().getCreatedAt();
        assertThat(made).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));

        commands.updateSettings(party.getPartyCode(), s -> {
            s.setDjName("DJ Ola");
            s.setWelcomeDismissedAt(Instant.now());
        });

        PartySettingsEntity saved = parties.findByPartyCode(party.getPartyCode()).orElseThrow();
        assertThat(saved.getCreatedAt()).isEqualTo(made);
        assertThat(saved.getWelcomeDismissedAt()).isNotNull();
        assertThat(saved.getOwnerEmail()).isEqualTo(party.getOwnerEmail());
    }

    @Test
    void theLastWeek_withAnAddress_theNewestFirst() {
        String stamp = String.valueOf(System.nanoTime());
        PartySettingsEntity today = newOrganiser("today." + stamp + "@gmail.com");
        PartySettingsEntity sixDays = newOrganiser("six." + stamp + "@gmail.com");
        madeDaysAgo(sixDays, 6);
        PartySettingsEntity eightDays = newOrganiser("eight." + stamp + "@gmail.com");
        madeDaysAgo(eightDays, 8);
        PartySettingsEntity noAddress = newOrganiser(null);
        PartySettingsEntity ofBefore = newOrganiser("before." + stamp + "@gmail.com");
        jdbc.update("UPDATE party_settings SET created_at = NULL WHERE party_code = ?", ofBefore.getPartyCode());

        List<String> mine = newThisWeek().stream().filter(email -> email.endsWith(stamp + "@gmail.com")).toList();

        assertThat(mine).containsExactly(today.getOwnerEmail(), sixDays.getOwnerEmail());
        assertThat(newThisWeek()).doesNotContainNull();
        assertThat(parties.findByPartyCode(noAddress.getPartyCode()).orElseThrow().getCreatedAt()).isNotNull();
    }

    @Test
    void theList_isBounded() {
        for (int i = 0; i < 4; i++) {
            newOrganiser("bounded." + i + "." + System.nanoTime() + "@gmail.com");
        }

        assertThat(parties.newOrganisers(Instant.now().minus(Duration.ofDays(7)), Limit.of(3))).hasSize(3);
    }
}
