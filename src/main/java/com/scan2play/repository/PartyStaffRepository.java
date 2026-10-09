package com.scan2play.repository;

import com.scan2play.entity.PartyStaffEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The parties' staff (V30). Every read is bounded: a party has at most 10, a person works at a few. */
@Repository
public interface PartyStaffRepository extends JpaRepository<PartyStaffEntity, Long> {

    /** The party's staff, the first to join first (the owner's list; the app lets 10 join — 20 read, so two joining at once are both shown). */
    List<PartyStaffEntity> findTop20ByPartyCodeOrderByJoinedAtAscIdAsc(String partyCode);

    /** The parties this person works at (the panel switcher). */
    List<PartyStaffEntity> findTop20ByMemberIdOrderByJoinedAtAscIdAsc(String memberId);

    /** Whether this person is on the party's staff: checked on every request of a staff member (the unique index). */
    boolean existsByPartyCodeAndMemberId(String partyCode, String memberId);

    long countByPartyCode(String partyCode);

    /** The owner takes one person's access away: only a row of the owner's own party. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PartyStaffEntity s WHERE s.id = :id AND s.partyCode = :partyCode")
    int deleteFromParty(@Param("id") long id, @Param("partyCode") String partyCode);

    /** The person leaves one party's staff ("Opuść obsługę"). */
    @Modifying
    @Transactional
    @Query("DELETE FROM PartyStaffEntity s WHERE s.partyCode = :partyCode AND s.memberId = :memberId")
    int deleteMember(@Param("partyCode") String partyCode, @Param("memberId") String memberId);

    /** A deleted account: wherever the person worked. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PartyStaffEntity s WHERE s.memberId = :memberId")
    int deleteByMember(@Param("memberId") String memberId);

    /** A deleted party: its staff (the foreign key would do it too — explicit, before the party goes). */
    @Modifying
    @Transactional
    @Query("DELETE FROM PartyStaffEntity s WHERE s.partyCode = :partyCode")
    int deleteByParty(@Param("partyCode") String partyCode);
}
