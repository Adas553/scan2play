package com.scan2play.repository;

import com.scan2play.entity.SongRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SongRequestRepository extends JpaRepository<SongRequestEntity, Long> {
}