package com.scan2play.repository;

import com.scan2play.entity.PartySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
