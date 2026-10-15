package com.scan2play.repository;

import com.scan2play.entity.DjPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The DJs' plans (V35), by the organiser's Google subject; and the accounts that had a trial (V36, by its key). */
public interface DjPlanRepository extends JpaRepository<DjPlanEntity, String> {

    /** Whether the Google account of this key ({@code PlanService.trialKey}) had a trial — also one whose account was deleted since. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM trial_used WHERE owner_hash = :key)", nativeQuery = true)
    boolean trialUsed(@Param("key") String key);

    /** Remembers that the Google account of this key had its trial (kept when the account is deleted, V36). */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO trial_used (owner_hash) VALUES (:key) ON CONFLICT DO NOTHING", nativeQuery = true)
    int markTrialUsed(@Param("key") String key);
}
