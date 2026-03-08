package com.scan2play.repository;

import com.scan2play.entity.PartySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartySettingsRepository extends JpaRepository<PartySettingsEntity, Long> {
}