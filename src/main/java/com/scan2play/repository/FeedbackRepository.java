package com.scan2play.repository;

import com.scan2play.entity.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {

    /**
     * Deletes all feedback entries submitted by a specific DJ.
     * Used during account deletion to comply with GDPR / Google API data deletion requirements.
     *
     * One {@code DELETE} (a derived query read the rows and deleted each on its own).
     *
     * @param ownerId The OAuth2 owner ID of the DJ.
     * @return the number of entries deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FeedbackEntity f WHERE f.ownerId = :ownerId")
    int deleteByOwnerId(@Param("ownerId") String ownerId);
}
