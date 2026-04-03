package com.scan2play.repository;

import com.scan2play.entity.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {

    /**
     * Deletes all feedback entries submitted by a specific DJ.
     * Used during account deletion to comply with GDPR / Google API data deletion requirements.
     *
     * @param ownerId The OAuth2 owner ID of the DJ.
     */
    void deleteByOwnerId(String ownerId);
}
