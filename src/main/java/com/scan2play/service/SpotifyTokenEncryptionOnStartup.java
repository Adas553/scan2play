package com.scan2play.service;

import com.scan2play.entity.SpotifyTokenConverter;
import com.scan2play.repository.PartySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Encrypts, once the application has started, the Spotify tokens written in plain text before {@link SpotifyTokenConverter}
 * (review 5.3). The converter reads such a value as it is and would encrypt it only at the party's next save — an unused party
 * would keep its tokens readable for ever. Nothing to do after the first start with the key: the query finds no rows.
 * <p>
 * Only the two columns are written (native SQL), so the cached settings stay right: in memory a token is plain text either way.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyTokenEncryptionOnStartup {

    /** Rows per round; the parties connected to Spotify are few, so usually one round. */
    static final int BATCH = 100;

    private final PartySettingsRepository partySettingsRepository;
    private final SpotifyTokenConverter converter;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void encryptPlainTokens() {
        int encrypted = 0;
        List<Object[]> rows;
        do {
            rows = partySettingsRepository.findPlainSpotifyTokens(BATCH);
            for (Object[] row : rows) {
                Long id = ((Number) row[0]).longValue();
                partySettingsRepository.writeSpotifyTokenColumns(id, encrypt((String) row[1]), encrypt((String) row[2]));
            }
            encrypted += rows.size();
        } while (rows.size() == BATCH);
        if (encrypted > 0) {
            log.info("Encrypted the Spotify tokens of {} part{} kept in plain text", encrypted, encrypted == 1 ? "y" : "ies");
        }
    }

    /** A column value encrypted; none, or one encrypted already (the row's other token), stays as it is. */
    private String encrypt(String column) {
        return column == null || column.startsWith(SpotifyTokenConverter.PREFIX) ? column : converter.convertToDatabaseColumn(column);
    }
}
