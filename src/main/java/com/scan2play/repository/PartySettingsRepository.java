package com.scan2play.repository;

import com.scan2play.entity.PartySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartySettingsRepository extends JpaRepository<PartySettingsEntity, Long> {

    /**
     * Finds party settings by its unique 5-character code.
     *
     * @param partyCode The unique code of the party.
     * @return An Optional containing the PartySettingsEntity if found.
     */
    Optional<PartySettingsEntity> findByPartyCode(String partyCode);

    /**
     * Finds party settings by the owner's unique ID (OAuth2 ID).
     *
     * @param ownerId The unique identifier of the DJ/Owner.
     * @return An Optional containing the PartySettingsEntity if found.
     */
    Optional<PartySettingsEntity> findByOwnerId(String ownerId);

    /**
     * The raw column values of the Spotify tokens still kept in plain text (written before the encryption, review 5.3): rows of
     * {@code [id, access token, refresh token]}, at most {@code limit}. Native SQL: through the entity the converter would hand
     * back plain text whatever the column holds.
     */
    @Query(value = """
            SELECT id, spotify_access_token, spotify_refresh_token FROM party_settings
            WHERE spotify_access_token NOT LIKE 'enc:v1:%' OR spotify_refresh_token NOT LIKE 'enc:v1:%'
            ORDER BY id LIMIT :limit""", nativeQuery = true)
    List<Object[]> findPlainSpotifyTokens(@Param("limit") int limit);

    /** Writes a party's Spotify token columns as they are given (already encrypted, see {@link #findPlainSpotifyTokens}). */
    @Modifying
    @Query(value = "UPDATE party_settings SET spotify_access_token = :accessToken, spotify_refresh_token = :refreshToken WHERE id = :id",
            nativeQuery = true)
    void writeSpotifyTokenColumns(@Param("id") Long id, @Param("accessToken") String accessToken,
                                  @Param("refreshToken") String refreshToken);
}
