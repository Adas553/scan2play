package com.scan2play.repository;

import com.scan2play.entity.SongRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SongRequestRepository extends JpaRepository<SongRequestEntity, Long> {
    
    // Finds recent requests regardless of status
    List<SongRequestEntity> findAllByOrderByRequestedAtDesc();

    // Finds only accepted songs for the public queue view (Social Proof)
    List<SongRequestEntity> findTop5ByDecisionOrderByRequestedAtDesc(String decision);
}
