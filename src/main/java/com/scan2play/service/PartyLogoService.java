package com.scan2play.service;

import com.scan2play.service.LogoImage.Logo;
import com.scan2play.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The DJ's logo (V45, plan Pro): one PNG per party in {@code party_logo}, its version mirrored in {@code party_settings.logo_version}
 * so the pages read it from the cached settings ({@code PartySettingsEntity.logoPath}). Plain SQL, as the library: a picture written
 * whole and read by its key — nothing an entity would add.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartyLogoService {

    /** The logo as served: its PNG and version (the ETag). */
    public record Stored(byte[] png, String version) {
    }

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;

    /**
     * The party's logo is now {@code logo}. Under the party row's lock, so two uploads at once end as one of them, and the row and the
     * settings' version always name the same picture.
     */
    @Transactional
    public void replace(String partyCode, Logo logo) {
        jdbc.queryForObject("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode);
        jdbc.update("DELETE FROM party_logo WHERE party_code = ?", partyCode);
        jdbc.update("INSERT INTO party_logo (party_code, png, width, height, version, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                partyCode, logo.png(), logo.width(), logo.height(), logo.version(), Timestamp.from(Instant.now()));
        jdbc.update("UPDATE party_settings SET logo_version = ? WHERE party_code = ?", logo.version(), partyCode);
        evictAfterCommit(partyCode);
        log.info("Party [{}]: logo uploaded ({} × {} px, {} bytes)", partyCode, logo.width(), logo.height(), logo.png().length);
    }

    @Transactional
    public void delete(String partyCode) {
        jdbc.queryForObject("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode);
        if (jdbc.update("DELETE FROM party_logo WHERE party_code = ?", partyCode) > 0) {
            log.info("Party [{}]: logo deleted", partyCode);
        }
        jdbc.update("UPDATE party_settings SET logo_version = NULL WHERE party_code = ?", partyCode);
        evictAfterCommit(partyCode);
    }

    /** The party's logo to send; empty without one. */
    public Optional<Stored> find(String partyCode) {
        return jdbc.query("SELECT png, version FROM party_logo WHERE party_code = ?",
                (row, n) -> new Stored(row.getBytes(1), row.getString(2)), partyCode).stream().findFirst();
    }

    /** After the commit: a page that read the settings while the change was still open would put the old version back. */
    private void evictAfterCommit(String partyCode) {
        AfterCommit.run(() -> Objects.requireNonNull(cacheManager.getCache(PartySettingsQueryService.CACHE),
                PartySettingsQueryService.CACHE).evict(partyCode));
    }
}
