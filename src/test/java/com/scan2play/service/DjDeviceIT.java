package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Program na komputerze" (V78) on a real PostgreSQL: a code pairs once, only the token's hash is kept, the token opens its party,
 * "Odłącz" kills it, a party keeps its 3 computers seen last, and the computers go with the party.
 */
class DjDeviceIT extends PostgresIntegrationTest {

    @Autowired DjDeviceService devices;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private String party() {
        String code = newPartyCode('D');
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("dev-" + code).active(true).build());
        return code;
    }

    private String pair(String party, String name, Instant at) {
        return devices.pair(devices.newCode(party), name, "it-" + party, at).token();
    }

    @Test
    void aCode_pairsOnce_andTheTokenOpensItsParty() {
        String party = party();
        String code = devices.newCode(party);

        DjDeviceService.Pairing first = devices.pair(code, "Laptop\n DJ-a", "it-client", NOW);
        DjDeviceService.Pairing again = devices.pair(code, "Inny", "it-client", NOW);

        assertThat(first.token()).hasSizeGreaterThan(40);
        assertThat(again.refusal()).isEqualTo(DjDeviceService.PairRefusal.WRONG_CODE);
        assertThat(devices.device(first.token(), NOW)).hasValueSatisfying(paired -> assertThat(paired.partyCode()).isEqualTo(party));
        assertThat(devices.device(first.token() + "x", NOW)).isEmpty();
        assertThat(devices.devices(party)).singleElement().satisfies(device -> assertThat(device.name()).isEqualTo("Laptop  DJ-a"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dj_device WHERE token_hash = ?", Integer.class, first.token()))
                .as("the token itself is never stored").isZero();
    }

    @Test
    void aNewCode_killsThePartysEarlierOne_andNoOtherPartys() {
        String party = party();
        String other = party();
        String first = devices.newCode(party);
        String othersCode = devices.newCode(other);
        String second = devices.newCode(party);

        assertThat(devices.pair(first, "Stary", "it-" + party, NOW).refusal()).isEqualTo(DjDeviceService.PairRefusal.WRONG_CODE);
        assertThat(devices.pair(second, "Nowy", "it-" + party, NOW).token()).isNotNull();
        assertThat(devices.pair(othersCode, "Cudzy", "it-" + other, NOW).token()).isNotNull();
    }

    @Test
    void theLastCall_isNotedAtMostOnceAMinute() {
        String party = party();
        String token = pair(party, "PC", NOW);

        devices.device(token, NOW.plusSeconds(30));
        assertThat(devices.devices(party).getFirst().lastSeenAt()).isEqualTo(NOW);
        devices.device(token, NOW.plusSeconds(90));
        assertThat(devices.devices(party).getFirst().lastSeenAt()).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void odlacz_killsTheToken_onlyForItsOwnParty() {
        String party = party();
        String other = party();
        String token = pair(party, "PC", NOW);
        long id = devices.devices(party).getFirst().id();

        assertThat(devices.remove(other, id)).isFalse();
        assertThat(devices.device(token, NOW)).isPresent();
        assertThat(devices.remove(party, id)).isTrue();
        assertThat(devices.device(token, NOW)).isEmpty();
    }

    @Test
    void aFourthComputer_forgetsTheOneSeenLongestAgo() {
        String party = party();
        String oldest = pair(party, "Stary", NOW.minus(Duration.ofDays(3)));
        String second = pair(party, "Drugi", NOW.minus(Duration.ofDays(2)));
        String third = pair(party, "Trzeci", NOW.minus(Duration.ofDays(1)));
        String fourth = pair(party, "Nowy", NOW);

        assertThat(devices.devices(party)).extracting(DjDeviceService.Device::name).containsExactly("Nowy", "Trzeci", "Drugi");
        assertThat(devices.device(oldest, NOW)).isEmpty();
        assertThat(devices.device(second, NOW)).isPresent();
        assertThat(devices.device(third, NOW)).isPresent();
        assertThat(devices.device(fourth, NOW)).isPresent();
    }

    @Test
    void aCodeOfAPartyGoneMeanwhile_pairsNothing() {
        String party = party();
        String code = devices.newCode(party);
        jdbc.update("DELETE FROM party_settings WHERE party_code = ?", party);

        assertThat(devices.pair(code, "PC", "it-gone", NOW).refusal()).isEqualTo(DjDeviceService.PairRefusal.WRONG_CODE);
    }

    @Test
    void theComputers_goWithTheParty() {
        String party = party();
        String token = pair(party, "PC", NOW);

        jdbc.update("DELETE FROM party_settings WHERE party_code = ?", party);

        assertThat(devices.device(token, NOW)).isEmpty();
    }
}
