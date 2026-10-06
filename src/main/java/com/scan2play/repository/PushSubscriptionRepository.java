package com.scan2play.repository;

import com.scan2play.entity.PushSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** The deletes run in a transaction of their own when the caller has none (PushNotificationService's sending thread). */
@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, Long> {

    /** The DJ's devices, newest first — bounded: a DJ keeps at most {@code PushSubscriptionService.MAX_DEVICES}. */
    List<PushSubscriptionEntity> findTop20ByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    /** Switched off on that device, or the push service said the address is gone. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PushSubscriptionEntity p WHERE p.endpoint = :endpoint")
    int deleteByEndpoint(@Param("endpoint") String endpoint);

    /** Only the DJ's own device: the address comes from the DJ's browser, a forged one must not remove another DJ's. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PushSubscriptionEntity p WHERE p.ownerId = :ownerId AND p.endpoint = :endpoint")
    int deleteByOwnerIdAndEndpoint(@Param("ownerId") String ownerId, @Param("endpoint") String endpoint);

    /** With the DJ's account (AccountDeletionService). */
    @Modifying
    @Transactional
    @Query("DELETE FROM PushSubscriptionEntity p WHERE p.ownerId = :ownerId")
    int deleteByOwnerId(@Param("ownerId") String ownerId);
}
