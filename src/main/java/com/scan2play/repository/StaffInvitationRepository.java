package com.scan2play.repository;

import com.scan2play.entity.StaffInvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The invitations to a party's staff by e-mail (V34). Every read is bounded and takes only the invitations still waiting — made
 * after {@code since} (now minus {@code StaffInvitationEntity.MAX_AGE_DAYS}): an older one is as good as gone before the night's
 * cleanup deletes it.
 */
@Repository
public interface StaffInvitationRepository extends JpaRepository<StaffInvitationEntity, Long> {

    /** The party's invitations waiting, the oldest first (the organiser's list; a party has at most 10 places — 20 read). */
    List<StaffInvitationEntity> findTop20ByPartyCodeAndInvitedAtAfterOrderByInvitedAtAscIdAsc(String partyCode, Instant since);

    /** The invitations waiting for this address (the login's lookup, the index on email_key), the oldest first. */
    List<StaffInvitationEntity> findTop10ByEmailKeyAndInvitedAtAfterOrderByInvitedAtAscIdAsc(String emailKey, Instant since);

    /** The party's invitation of this address, waiting or not (an organiser inviting the same address again). */
    Optional<StaffInvitationEntity> findByPartyCodeAndEmailKey(String partyCode, String emailKey);

    long countByPartyCodeAndInvitedAtAfter(String partyCode, Instant since);

    /** Whether the invitation still waits for this address — asked again under the party's lock when it is answered. */
    boolean existsByIdAndEmailKeyAndInvitedAtAfter(long id, String emailKey, Instant since);

    /** The organiser cancels an invitation: only one of their own party. */
    @Modifying
    @Transactional
    @Query("DELETE FROM StaffInvitationEntity i WHERE i.id = :id AND i.partyCode = :partyCode")
    int deleteFromParty(@Param("id") long id, @Param("partyCode") String partyCode);

    /** The person answers an invitation (joins or declines): only one made for their address. */
    @Modifying
    @Transactional
    @Query("DELETE FROM StaffInvitationEntity i WHERE i.id = :id AND i.emailKey = :emailKey")
    int deleteForAddress(@Param("id") long id, @Param("emailKey") String emailKey);

    /** A deleted party: its invitations (the foreign key would do it too — explicit, before the party goes). */
    @Modifying
    @Transactional
    @Query("DELETE FROM StaffInvitationEntity i WHERE i.partyCode = :partyCode")
    int deleteByParty(@Param("partyCode") String partyCode);

    /** The nightly cleanup: the invitations no one answered in time. */
    @Modifying
    @Transactional
    @Query("DELETE FROM StaffInvitationEntity i WHERE i.invitedAt <= :before")
    int deleteMadeBefore(@Param("before") Instant before);
}
