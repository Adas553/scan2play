package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DJ's logo (V45) on a real PostgreSQL: saved, replaced, deleted — the row and the settings' version always naming the same
 * picture, also with two uploads at once —, kept through a save of the settings, and gone with the account.
 */
class PartyLogoIT extends PostgresIntegrationTest {

    @Autowired PartyLogoService logos;
    @Autowired PartySettingsQueryService settings;
    @Autowired PartySettingsRepository parties;
    @Autowired AccountDeletionService deletion;
    @Autowired JdbcTemplate jdbc;

    private String party() {
        String code = "G" + ThreadLocalRandom.current().nextInt(1000, 10000);
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("logo-" + code).active(true).build());
        return code;
    }

    private int rows(String partyCode) {
        return jdbc.queryForObject("SELECT count(*) FROM party_logo WHERE party_code = ?", Integer.class, partyCode);
    }

    @Test
    void aLogo_isSaved_replaced_andDeleted_theSettingsKnowingItsAddress() throws Exception {
        String code = party();
        assertThat(settings.getSettings(code).logoPath()).isNull();

        LogoImage.Logo first = LogoImage.reencode(TestImages.png(100, 50));
        logos.replace(code, first);
        assertThat(logos.find(code)).get().extracting(PartyLogoService.Stored::version).isEqualTo(first.version());
        assertThat(settings.getSettings(code).getLogoVersion()).isEqualTo(first.version());
        assertThat(settings.getSettings(code).logoPath()).isEqualTo("/p/" + code + "/logo.png?v=" + first.version().substring(0, 16));

        LogoImage.Logo second = LogoImage.reencode(TestImages.jpeg(300, 300));
        logos.replace(code, second);
        assertThat(rows(code)).isEqualTo(1);
        assertThat(logos.find(code).orElseThrow().png()).isEqualTo(second.png());
        assertThat(settings.getSettings(code).getLogoVersion()).isEqualTo(second.version());

        logos.delete(code);
        assertThat(rows(code)).isZero();
        assertThat(logos.find(code)).isEmpty();
        assertThat(settings.getSettings(code).logoPath()).isNull();
    }

    /** The version is the logo service's alone: a save of the party's settings (JPA) never writes it back. */
    @Test
    void aSaveOfTheSettings_keepsTheLogo() throws Exception {
        String code = party();
        PartySettingsEntity before = parties.findByPartyCode(code).orElseThrow();   // read before the logo, saved after it
        LogoImage.Logo logo = LogoImage.reencode(TestImages.png(64, 64));
        logos.replace(code, logo);

        before.setDjName("DJ Koko");
        parties.saveAndFlush(before);

        assertThat(jdbc.queryForObject("SELECT logo_version FROM party_settings WHERE party_code = ?", String.class, code))
                .isEqualTo(logo.version());
    }

    @Test
    void twoUploadsAtOnce_endAsOneOfThem() throws Exception {
        String code = party();
        List<LogoImage.Logo> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(LogoImage.reencode(TestImages.png(40 + i, 40)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> all = new ArrayList<>();
            candidates.forEach(logo -> all.add(pool.submit(() -> logos.replace(code, logo))));
            for (Future<?> one : all) {
                one.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(rows(code)).isEqualTo(1);
        String kept = logos.find(code).orElseThrow().version();
        assertThat(jdbc.queryForObject("SELECT logo_version FROM party_settings WHERE party_code = ?", String.class, code))
                .isEqualTo(kept);
    }

    @Test
    void theLogo_goesWithTheAccount() throws Exception {
        String code = party();
        logos.replace(code, LogoImage.reencode(TestImages.png(64, 64)));

        deletion.deleteAllUserData("logo-" + code);

        assertThat(rows(code)).isZero();
    }
}
