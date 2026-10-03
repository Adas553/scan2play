package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.SpotifyTokenEncryptionOnStartup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spotify tokens on a real PostgreSQL (review 5.3): encrypted in the column, plain in the entity; tokens written before the
 * encryption are encrypted at start; a value the key cannot open is read as none.
 */
class SpotifyTokenEncryptionIT extends PostgresIntegrationTest {

    private static final String ACCESS = "BQD-access-token-of-a-dj";
    private static final String REFRESH = "AQB-refresh-token-of-a-dj";

    @Autowired PartySettingsRepository parties;
    @Autowired SpotifyTokenEncryptionOnStartup startup;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theColumnsHoldNoPlainToken() {
        String party = saveParty(ACCESS, REFRESH);

        assertThat(column(party, "spotify_access_token")).startsWith("enc:v1:").doesNotContain(ACCESS);
        assertThat(column(party, "spotify_refresh_token")).startsWith("enc:v1:").doesNotContain(REFRESH);
        PartySettingsEntity read = parties.findByPartyCode(party).orElseThrow();
        assertThat(read.getSpotifyAccessToken()).isEqualTo(ACCESS);
        assertThat(read.getSpotifyRefreshToken()).isEqualTo(REFRESH);
    }

    @Test
    void tokensWrittenBeforeTheEncryptionAreEncryptedAtStart() {
        String party = saveParty(null, null);
        String noTokens = saveParty(null, null);
        jdbc.update("UPDATE party_settings SET spotify_access_token = ?, spotify_refresh_token = ? WHERE party_code = ?",
                ACCESS, REFRESH, party);
        assertThat(parties.findByPartyCode(party).orElseThrow().getSpotifyAccessToken()).as("a plain one is read as it is").isEqualTo(ACCESS);

        startup.encryptPlainTokens();

        assertThat(column(party, "spotify_access_token")).startsWith("enc:v1:");
        assertThat(column(party, "spotify_refresh_token")).startsWith("enc:v1:");
        assertThat(column(noTokens, "spotify_access_token")).isNull();
        PartySettingsEntity read = parties.findByPartyCode(party).orElseThrow();
        assertThat(read.getSpotifyAccessToken()).isEqualTo(ACCESS);
        assertThat(read.getSpotifyRefreshToken()).isEqualTo(REFRESH);
        assertThat(parties.findPlainSpotifyTokens(10)).isEmpty();
    }

    @Test
    void aTokenTheKeyCannotOpenIsReadAsNone() {
        String party = saveParty(ACCESS, REFRESH);
        // as if written with another key: the same form, other bytes
        jdbc.update("UPDATE party_settings SET spotify_access_token = ? WHERE party_code = ?",
                "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", party);

        PartySettingsEntity read = parties.findByPartyCode(party).orElseThrow();
        assertThat(read.getSpotifyAccessToken()).isNull();
        assertThat(read.getSpotifyRefreshToken()).as("the other one still opens").isEqualTo(REFRESH);
    }

    private String saveParty(String accessToken, String refreshToken) {
        String code = newPartyCode();
        parties.save(PartySettingsEntity.builder().partyCode(code).ownerId("owner-" + code)
                .spotifyAccessToken(accessToken).spotifyRefreshToken(refreshToken).build());
        return code;
    }

    private String column(String party, String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM party_settings WHERE party_code = ?", String.class, party);
    }
}
